package music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/**
 * Private economy API client. Read paths are individually routed behind an
 * explicit opt-in; mutations are retained for isolated contract tests.
 */
public final class EconomyV2Client {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String secret;
    private final Duration requestTimeout;

    public EconomyV2Client(HttpClient http, URI baseUri, String secret) {
        this(http, baseUri, secret, Duration.ofSeconds(3));
    }

    EconomyV2Client(HttpClient http, URI baseUri, String secret, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http);
        this.endpoint = Objects.requireNonNull(baseUri).resolve("/internal/economy/v1/execute");
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        if (requestTimeout.isZero() || requestTimeout.isNegative())
            throw new IllegalArgumentException("Request timeout must be positive");
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("Economy API secret must have at least 32 characters");
        }
        this.secret = secret;
    }

    public record Request(String requestId, String idempotencyKey, String operation,
                          Map<String, String> context, Map<String, Object> args) {
        public Request {
            UUID.fromString(Objects.requireNonNull(requestId));
            Objects.requireNonNull(operation);
            context = Map.copyOf(context);
            args = Map.copyOf(args);
            for (String key : new String[]{"actor_user_id", "guild_id", "channel_id"}) {
                if (!context.containsKey(key) || !context.get(key).matches("[0-9]{1,32}")) {
                    throw new IllegalArgumentException("Invalid Discord context");
                }
            }
            if (idempotencyKey != null && !idempotencyKey.matches("discord-interaction:[0-9]{1,32}")) {
                throw new IllegalArgumentException("Invalid interaction key");
            }
        }

        public static Request mutation(String interactionId, String operation,
                                       Map<String, String> context, Map<String, Object> args) {
            if (interactionId == null || !interactionId.matches("[0-9]{1,32}")) {
                throw new IllegalArgumentException("Invalid interaction ID");
            }
            return new Request(UUID.randomUUID().toString(), "discord-interaction:" + interactionId,
                    operation, context, args);
        }
    }

    public record Response(int httpStatus, JsonNode body) {
        public String errorCode() {
            return body.path("error").path("code").asText("");
        }
    }

    public record InventoryItem(String itemId, long quantity) {}
    public record Inventory(String userId, List<InventoryItem> items) {
        public Inventory { items = List.copyOf(items); }
    }
    public record Product(String id, String name, String description, String category, long currentPrice,
                         long effectivePrice, long stock, boolean available, String rarity) {}
    public record Sale(String productId, String category, int discountPercent, long endsAtEpoch) {}
    public record ShopCatalog(List<Product> products, List<Sale> sales) {
        public ShopCatalog { products = List.copyOf(products); sales = List.copyOf(sales); }
    }
    public record LeaderboardEntry(String userId, long balance) {}

    public Response execute(Request request) throws IOException, InterruptedException {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("request_id", request.requestId());
        if (request.idempotencyKey() != null) payload.put("idempotency_key", request.idempotencyKey());
        payload.put("operation", request.operation());
        payload.put("context", request.context());
        payload.put("args", request.args());

        byte[] bytes = JSON.writeValueAsBytes(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + secret)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 400 &&
                response.statusCode() != 401 && response.statusCode() != 403 && response.statusCode() != 409 &&
                response.statusCode() != 503) {
            throw new IOException("Unexpected economy HTTP status: " + response.statusCode());
        }
        JsonNode body = JSON.readTree(response.body());
        if (!body.isObject() || !body.path("status").isTextual()) {
            throw new IOException("Invalid economy response");
        }
        return new Response(response.statusCode(), body);
    }

    public long balance(String actorId, String guildId, String channelId, String targetId)
            throws IOException, InterruptedException {
        Request request = readRequest("wallet.balance", actorId, guildId, channelId, targetId, Map.of());
        return decimal(readResult(request, "wallet_balance", targetId).path("balance"), "balance");
    }

    public Inventory inventory(String actorId, String guildId, String channelId, String targetId)
            throws IOException, InterruptedException {
        Request request = readRequest("inventory.list", actorId, guildId, channelId, targetId, Map.of());
        JsonNode result = readResult(request, "inventory", targetId);
        JsonNode items = result.path("items");
        if (!items.isArray()) throw new IOException("Invalid economy inventory response");
        var parsed = new ArrayList<InventoryItem>();
        for (JsonNode item : items) {
            String id = text(item, "item_id");
            parsed.add(new InventoryItem(id, decimal(item.path("quantity"), "quantity")));
        }
        return new Inventory(text(result, "user_id"), parsed);
    }

    public ShopCatalog shopCatalog(String actorId, String guildId, String channelId, String category)
            throws IOException, InterruptedException {
        Request request = readRequest("shop.catalog", actorId, guildId, channelId, null,
                Map.of("category", category));
        JsonNode result = readResult(request, "shop_catalog", null);
        JsonNode products = result.path("products");
        JsonNode sales = result.path("sales");
        if (!products.isArray() || !sales.isArray()) throw new IOException("Invalid economy catalog response");
        var parsedProducts = new ArrayList<Product>();
        for (JsonNode product : products) {
            parsedProducts.add(new Product(text(product, "product_id"), text(product, "name"),
                    text(product, "description"), text(product, "category"),
                    decimal(product.path("current_price"), "current_price"),
                    decimal(product.path("effective_price"), "effective_price"),
                    signedDecimal(product.path("stock"), "stock"), bool(product, "available"),
                    text(product, "rarity")));
        }
        var parsedSales = new ArrayList<Sale>();
        for (JsonNode sale : sales) {
            JsonNode productId = sale.path("product_id");
            JsonNode productCategory = sale.path("category");
            if (!(productId.isNull() || productId.isTextual()) ||
                    !(productCategory.isNull() || productCategory.isTextual()))
                throw new IOException("Invalid economy sale response");
            parsedSales.add(new Sale(productId.isNull() ? null : productId.asText(),
                    productCategory.isNull() ? null : productCategory.asText(),
                    integer(sale.path("discount_percent"), "discount_percent"),
                    decimal(sale.path("ends_at_epoch"), "ends_at_epoch")));
        }
        return new ShopCatalog(parsedProducts, parsedSales);
    }

    public List<LeaderboardEntry> leaderboard(String actorId, String guildId, String channelId, int limit)
            throws IOException, InterruptedException {
        Request request = readRequest("wallet.leaderboard", actorId, guildId, channelId, null,
                Map.of("limit", limit));
        JsonNode entries = readResult(request, "leaderboard", null).path("entries");
        if (!entries.isArray()) throw new IOException("Invalid economy leaderboard response");
        var result = new ArrayList<LeaderboardEntry>();
        for (JsonNode entry : entries)
            result.add(new LeaderboardEntry(text(entry, "user_id"), decimal(entry.path("balance"), "balance")));
        return List.copyOf(result);
    }

    private Request readRequest(String operation, String actorId, String guildId, String channelId,
                                String targetId, Map<String, Object> args) {
        var context = new java.util.LinkedHashMap<String, String>();
        context.put("actor_user_id", actorId);
        context.put("guild_id", guildId);
        context.put("channel_id", channelId);
        if (targetId != null) context.put("target_user_id", targetId);
        return new Request(UUID.randomUUID().toString(), null, operation, context, args);
    }

    private JsonNode readResult(Request request, String type, String expectedUserId)
            throws IOException, InterruptedException {
        Response response = execute(request);
        JsonNode body = response.body();
        JsonNode result = body.path("result");
        if (response.httpStatus() != 200 || !"ok".equals(body.path("status").asText()) ||
                !request.requestId().equals(body.path("request_id").asText()) ||
                !type.equals(result.path("type").asText()) ||
                expectedUserId != null && !expectedUserId.equals(result.path("user_id").asText()))
            throw new IOException("Invalid economy " + type + " response");
        return result;
    }

    private static String text(JsonNode object, String field) throws IOException {
        JsonNode value = object.path(field);
        if (!value.isTextual()) throw new IOException("Invalid economy response field: " + field);
        return value.asText();
    }

    private static long decimal(JsonNode value, String field) throws IOException {
        if (!value.isTextual() || !value.asText().matches("0|[1-9][0-9]*"))
            throw new IOException("Invalid economy response field: " + field);
        try { return Long.parseLong(value.asText()); }
        catch (NumberFormatException ex) { throw new IOException("Economy value is out of range: " + field, ex); }
    }

    private static int integer(JsonNode value, String field) throws IOException {
        if (!value.isIntegralNumber() || !value.canConvertToInt())
            throw new IOException("Invalid economy response field: " + field);
        return value.intValue();
    }

    private static boolean bool(JsonNode object, String field) throws IOException {
        JsonNode value = object.path(field);
        if (!value.isBoolean()) throw new IOException("Invalid economy response field: " + field);
        return value.booleanValue();
    }

    private static long signedDecimal(JsonNode value, String field) throws IOException {
        if (!value.isTextual() || !value.asText().matches("-?(0|[1-9][0-9]*)"))
            throw new IOException("Invalid economy response field: " + field);
        try { return Long.parseLong(value.asText()); }
        catch (NumberFormatException ex) { throw new IOException("Economy value is out of range: " + field, ex); }
    }
}
