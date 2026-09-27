package music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class GeneralBot extends CommandListener {
    private final StatusRotation rotation;
    private final long configuredOwnerId;
    private final Runnable restart;
    private Runnable shutdown;
    private final Instant startedAt;
    private final Clock clock;
    private BotStore statsStore;
    private CurrencyStore currency;
    private PostgresMarketStore market;
    private ToriPersona persona = ToriPersona.defaults();
    private String creator;
    private PrefixSettings prefixes;
    GeneralBot withPrefixes(PrefixSettings prefixes) { this.prefixes = prefixes; return this; }
    GeneralBot withPersona(ToriPersona persona) { this.persona = Objects.requireNonNull(persona); return this; }
    GeneralBot withCurrency(CurrencyStore currency) { this.currency = Objects.requireNonNull(currency); return this; }
    GeneralBot withMarket(PostgresMarketStore market) { this.market = Objects.requireNonNull(market); return this; }
    GeneralBot withShutdown(Runnable shutdown) { this.shutdown = Objects.requireNonNull(shutdown); return this; }
    GeneralBot withStats(BotStore store, String creator) {
        this.statsStore = Objects.requireNonNull(store);
        this.creator = creator;
        return this;
    }
    // Guarded by rotation's monitor, alongside its scheduled presence updates.
    private boolean defaultRotation;
    private boolean closed;
    public GeneralBot(LanguageStore languages) { this(languages, new StatusRotation(), BotConfig.load().ownerId(), null); }
    GeneralBot(LanguageStore languages, StatusRotation rotation) {
        this(languages, rotation, 0, null);
    }
    GeneralBot(LanguageStore languages, StatusRotation rotation, long ownerId, Runnable restart) {
        this(languages, rotation, ownerId, restart, Instant.now());
    }
    GeneralBot(LanguageStore languages, StatusRotation rotation, long ownerId, Runnable restart, Instant startedAt) {
        this(languages, rotation, ownerId, restart, startedAt, Clock.systemUTC());
    }
    GeneralBot(LanguageStore languages, StatusRotation rotation, long ownerId, Runnable restart, Instant startedAt, Clock clock) {
        super(Set.of("prefix", "language", "help", "ping", "stats", "status", "restart", "shutdown", "uptime", "avatar",
            "balance", "daily", "shop", "buy", "sell", "inventory", "equip", "tools", "fish", "mine", "chop",
            "craft", "repair", "opencrate", "market", "iteminfo", "pricehistory"), languages);
        this.rotation = rotation;
        this.configuredOwnerId = ownerId;
        this.restart = restart;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public void onReady(ReadyEvent event) { refreshDefaultStatus(event.getJDA()); }
    @Override public void onGuildJoin(GuildJoinEvent event) { refreshDefaultStatus(event.getJDA()); }
    @Override public void onGuildLeave(GuildLeaveEvent event) { refreshDefaultStatus(event.getJDA()); }

    void refreshDefaultStatus(JDA jda) {
        synchronized (rotation) {
            if (!closed && !rotation.snapshot().running()) startDefaultStatus(jda);
        }
    }

    private void startDefaultStatus(JDA jda) {
        rotation.start(persona.statuses(), StatusRotation.DEFAULT_INTERVAL_MS, slot -> applyDefaultStatus(jda, slot));
        defaultRotation = true;
    }

    private void applyDefaultStatus(JDA jda, String slot) {
        var manager = jda.getShardManager();
        long servers = manager == null ? jda.getGuildCache().size() : manager.getGuildCache().size();
        int shards = jda.getShardInfo().getShardTotal();
        var activity = persona.statusActivity(slot, servers, shards);
        applyPresence(jda, OnlineStatus.ONLINE, activity);
    }

    private static void applyPresence(JDA jda, OnlineStatus status, Activity activity) {
        var manager = jda.getShardManager();
        if (manager == null) jda.getPresence().setPresence(status, activity);
        else manager.setPresence(status, activity);
    }
    public static List<CommandData> commands() {
        return LocalizedCommands.apply(List.of(
            Commands.slash("language", "Show or set the server language")
                .addOptions(new OptionData(OptionType.STRING, "code", "Language")
                    .addChoice("Deutsch", "de").addChoice("English", "en").addChoice("Nederlands", "nl")),
            Commands.slash("help", "Show commands and usage"),
            Commands.slash("avatar", "Show a user's profile picture by Discord ID")
                .addOption(OptionType.STRING, "user_id", "Discord user ID", true),
            Commands.slash("restart", "Restart the bot (configured bot owner only)"),
            Commands.slash("shutdown", "Shut down the bot (bot owner only)"),
            Commands.slash("uptime", "Show session uptime (bot owner only)"),
            Commands.slash("ping", "Show WebSocket and bot REST latency in milliseconds"),
            Commands.slash("prefix", "Show or change this server's command prefix")
                .addOption(OptionType.STRING, "value", "New prefix", false),
            Commands.slash("stats", "Show uptime, servers, members and bot version"),
            Commands.slash("balance", "Show your Tori credits"),
            Commands.slash("daily", "Claim your daily credits"),
            Commands.slash("shop", "Browse the current Tori market")
                .addOption(OptionType.STRING, "category", "Market category (all, common, tools, collectibles, utility)", false),
            Commands.slash("iteminfo", "Inspect a market product")
                .addOption(OptionType.STRING, "item", "Product ID", true),
            Commands.slash("pricehistory", "View recorded market price changes")
                .addOption(OptionType.STRING, "item", "Product ID", true),
            Commands.slash("market", "Manage Tori's market products")
                .addSubcommands(new SubcommandData("add", "Add a product to the market")
                    .addOption(OptionType.STRING, "product_id", "Stable product ID", true)
                    .addOption(OptionType.STRING, "product_name", "Product name", true)
                    .addOption(OptionType.STRING, "product_category", "Market category", true)
                    .addOptions(new OptionData(OptionType.INTEGER, "base_price", "Base price in credits", true)
                        .setRequiredRange(1, 1_000_000_000))
                    .addOption(OptionType.STRING, "product_description", "Product description", true)
                    .addOptions(new OptionData(OptionType.INTEGER, "product_stock", "-1 for unlimited; otherwise available stock", false)
                        .setRequiredRange(-1, 1_000_000)))
                .addSubcommands(new SubcommandData("stock", "Set a product's remaining stock")
                    .addOption(OptionType.STRING, "product_id", "Product ID", true)
                    .addOptions(new OptionData(OptionType.INTEGER, "product_stock", "-1 for unlimited; otherwise remaining stock", true)
                        .setRequiredRange(-1, 1_000_000)))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)),
            Commands.slash("buy", "Buy an item from the market")
                .addOption(OptionType.STRING, "item", "Market item ID", true)
                .addOption(OptionType.INTEGER, "quantity", "Quantity (1–100)", false),
            Commands.slash("sell", "Sell an item from your inventory")
                .addOption(OptionType.STRING, "item", "Item ID", true)
                .addOption(OptionType.INTEGER, "quantity", "Quantity (1–100)", false),
            Commands.slash("inventory", "Show your collected items"),
            Commands.slash("equip", "Equip a fishing rod, pickaxe, axe, or wrench")
                .addOption(OptionType.STRING, "item", "Tool item ID", true),
            Commands.slash("tools", "Show your equipped tools and durability"),
            Commands.slash("fish", "Fish with your equipped rod"),
            Commands.slash("mine", "Mine with your equipped pickaxe"),
            Commands.slash("chop", "Gather wood with your equipped axe"),
            Commands.slash("craft", "Craft a tool using an equipped wrench")
                .addOption(OptionType.STRING, "item", "Recipe item ID", true),
            Commands.slash("repair", "Repair a tool using an equipped wrench")
                .addOption(OptionType.STRING, "item", "Tool item ID", true),
            Commands.slash("opencrate", "Open a gathered crate with a crate key")
                .addOption(OptionType.STRING, "item", "Crate item ID", true),
            Commands.slash("status", "Manage rotating bot status (bot owner only)")
                .addOptions(
                    new OptionData(OptionType.STRING, "action", "Start, stop or show the rotation")
                        .addChoice("Start", "start").addChoice("Stop", "stop").addChoice("Show", "show"),
                    new OptionData(OptionType.STRING, "texts", "One status or multiple texts separated by | or line breaks")
                        .setMaxLength(2000),
                    new OptionData(OptionType.INTEGER, "interval_ms", "Rotation interval in milliseconds")
                        .setRequiredRange(StatusRotation.MIN_INTERVAL_MS, StatusRotation.MAX_INTERVAL_MS))
        ));
    }
    @Override protected String handle(CommandContext event, Language language) {
        if (Set.of("balance", "daily", "shop", "buy", "sell", "inventory", "equip", "tools", "fish", "mine", "chop",
            "craft", "repair", "opencrate", "market", "iteminfo", "pricehistory").contains(event.getName())) return economy(event, language);
        if (event.getName().equals("prefix")) {
            require(prefixes != null, "prefix.unavailable");
            var option = event.getOption("value");
            if (option == null) return Messages.text(language, "prefix.current", prefixes.get(event.getGuild().getId()));
            var member = event.getGuild().retrieveMemberById(event.getUser().getId()).complete();
            require(member.hasPermission(Permission.MANAGE_SERVER), "language.permission");
            String value = PrefixSettings.validate(option.getAsString());
            try { prefixes.set(event.getGuild().getId(), value); }
            catch (java.sql.SQLException ex) { throw new UserError("prefix.unavailable"); }
            return Messages.text(language, "prefix.changed", value);
        }
        if (event.getName().equals("help")) return help(language);
        if (event.getName().equals("uptime")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            return Messages.text(language, "stats.uptime") + ": " + sessionUptime(startedAt, clock.instant());
        }
        if (event.getName().equals("shutdown")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            require(shutdown != null, "shutdown.unavailable");
            return Messages.text(language, "shutdown.started");
        }
        if (event.getName().equals("restart")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            require(restart != null, "restart.unavailable");
            return Messages.text(language, "restart.started");
        }
        if (event.getName().equals("status")) {
            require(configuredOwnerId != 0, "owner.unconfigured");
            require(event.getUser().getIdLong() == configuredOwnerId, "owner.only");
            return status(event, language);
        }
        if (event.getName().equals("ping")) {
            long botPing = event.getJDA().getRestPing().timeout(10, TimeUnit.SECONDS).complete();
            long websocketPing = event.getJDA().getGatewayPing();
            return Messages.text(language, "ping.result", latency(language, websocketPing), latency(language, botPing));
        }
        if (event.getOption("code") == null) return Messages.text(language, "language.current", language.label);
        var member = event.getGuild().retrieveMemberById(event.getUser().getId()).complete();
        require(member.hasPermission(Permission.MANAGE_SERVER), "language.permission");
        Language selected = Language.parse(event.getOption("code").getAsString());
        try { languages.set(event.getGuild().getId(), selected); }
        catch (IOException ex) { throw new UserError("language.save.failed"); }
        return Messages.text(selected, "language.changed", selected.label);
    }

    private String economy(CommandContext event, Language language) {
        require(currency != null, "economy.unavailable");
        String userId = event.getUser().getId();
        try {
            return switch (event.getName()) {
                case "balance" -> Messages.text(language, "balance.result", event.getUser().getEffectiveName(), currency.balance(userId));
                case "daily" -> {
                    long wait = currency.daily(userId, clock.millis());
                    yield wait == 0 ? Messages.text(language, "economy.daily.claimed", 150, currency.balance(userId))
                        : Messages.text(language, "economy.daily.cooldown", Math.max(1, (wait + 59_999) / 60_000));
                }
                case "shop" -> shopText(event, language);
                case "buy" -> buyText(event, language, userId);
                case "sell" -> sellText(event, language, userId);
                case "inventory" -> inventoryText(event, language, userId);
                case "equip" -> equipText(event, language, userId);
                case "tools" -> toolsText(language, userId);
                case "fish", "mine", "chop" -> gatherText(event, language, userId);
                case "craft" -> craftText(event, language, userId);
                case "repair" -> repairText(event, language, userId);
                case "opencrate" -> crateText(event, language, userId);
                case "market" -> marketText(event, language);
                case "iteminfo" -> itemInfoText(event, language);
                case "pricehistory" -> priceHistoryText(event, language);
                default -> throw new UserError("error.unknown");
            };
        } catch (CurrencyStoreException ex) {
            throw new UserError("economy.unavailable");
        }
    }

    private String shopText(CommandContext event, Language language) throws CurrencyStoreException {
        String category = event.getOption("category") == null ? "all"
            : event.getOption("category").getAsString().strip().toLowerCase(Locale.ROOT);
        require(category.equals("all") || category.matches("[a-z0-9_-]{1,64}"), "shop.category");
        Instant now = clock.instant();
        var text = new StringBuilder(Messages.text(language, "shop.current.title"));
        if (market == null) {
            for (var item : currency.shop())
                text.append("\n`").append(item.id()).append("` — **").append(item.name()).append("** - ")
                    .append(item.price()).append(" credits (sell ").append(item.sellPrice()).append(')');
        } else {
            var available = market.products(category, now).stream().filter(DeseModels.Product::available).toList();
            for (var product : available.stream().limit(7).toList()) {
                long price = market.quote(event.getGuild().getId(), event.getUser().getId(), product.id(), now);
                text.append("\n`").append(product.id()).append("` — **").append(product.name()).append("** — ")
                    .append(price < 0 ? Messages.text(language, "market.price.unavailable") : price + " credits");
            }
            if (available.isEmpty()) text.append("\n").append(Messages.text(language, "market.products.empty"));
            if (available.size() > 7) text.append("\n").append(Messages.text(language, "market.products.more", available.size() - 7));
            for (var sale : market.activeSales(now)) {
                String target = sale.productId() == null ? sale.category() : sale.productId();
                text.append("\n").append(Messages.text(language, "market.sales.active", sale.discountPercent(), target,
                    sale.endsAt().getEpochSecond()));
            }
        }
        if (market != null) text.append("\n").append(Messages.text(language, "market.quote.note"));
        text.append("\n\n").append(Messages.text(language, "shop.current.footer"));
        return persona.decorate(text.toString(), ToriPersona.Context.SHOP_BROWSE, language);
    }

    private String buyText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var option = event.getOption("item");
        require(option != null, "error.input");
        int count = quantityOption(event);
        if (market != null) {
            var purchase = market.buy(event.getGuild().getId(), userId, event.getId(), option.getAsString(), count, clock.instant());
            return switch (purchase.state()) {
                case PURCHASED -> Messages.text(language, "shop.purchase", purchase.quantity(),
                    purchase.product() == null ? option.getAsString() : purchase.product().name(), purchase.total(), purchase.balanceAfter());
                case DUPLICATE -> Messages.text(language, "market.purchase.duplicate");
                case PRICE_CHANGED, QUOTE_REQUIRED -> Messages.text(language, "market.quote.expired", purchase.unitPrice());
                case OUT_OF_STOCK -> Messages.text(language, "market.stock.soldout");
                case INSUFFICIENT_FUNDS -> Messages.text(language, "shop.buy.failed");
                case UNAVAILABLE -> Messages.text(language, "shop.item");
            };
        }
        var purchase = currency.buy(userId, option.getAsString(), count);
        require(purchase != null, "shop.buy.failed");
        return Messages.text(language, "shop.purchase", purchase.quantity(), purchase.item().name(), purchase.total(), purchase.balance());
    }

    private String sellText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var option = event.getOption("item");
        require(option != null, "error.input");
        var sale = currency.sell(userId, option.getAsString(), quantityOption(event));
        require(sale != null, "shop.sell.failed");
        return Messages.text(language, "shop.sale", sale.quantity(), sale.item().name(), sale.total(), sale.balance());
    }

    private String inventoryText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var items = currency.inventory(userId);
        if (items.isEmpty()) return Messages.text(language, "inventory.empty", event.getUser().getEffectiveName());
        String content = String.join(", ", items.stream().map(item -> {
            var catalog = ShopCatalog.find(item.id());
            return (catalog == null ? item.id() : catalog.name()) + " × " + item.quantity();
        }).toList());
        return Messages.text(language, "inventory.result", event.getUser().getEffectiveName(), content);
    }

    private String equipText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var option = event.getOption("item");
        require(option != null, "error.input");
        var item = ShopCatalog.find(option.getAsString());
        require(item != null && item.tool(), "shop.item");
        require(currency.equip(userId, item.id()), "shop.equip.failed");
        return Messages.text(language, "shop.equip.done", item.name(), item.toolSlot());
    }

    private String toolsText(Language language, String userId) throws CurrencyStoreException {
        var items = currency.equipment(userId);
        if (items.isEmpty()) return Messages.text(language, "tools.empty");
        String content = String.join("\n", items.stream().map(tool -> {
            var item = ShopCatalog.find(tool.itemId());
            return tool.slot() + ": " + (item == null ? tool.itemId() : item.name()) + " · " + tool.remainingDurability();
        }).toList());
        return Messages.text(language, "tools.list", content);
    }

    private String gatherText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var result = currency.gather(userId, event.getName(), clock.millis());
        require(result != null, "gather.tool.required");
        if (result.cooldownRemaining() > 0)
            return Messages.text(language, "gather.cooldown", Math.max(1, (result.cooldownRemaining() + 999) / 1000));
        var item = ShopCatalog.find(result.itemId());
        String itemName = item == null ? result.itemId() : item.name();
        String response = result.broke()
            ? Messages.text(language, "gather.broke", itemName, result.credits())
            : Messages.text(language, "gather.success", itemName, result.credits(), result.remainingDurability());
        return persona.decorate(response, ToriPersona.Context.SOCIAL_FUN, language);
    }

    private String craftText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var option = event.getOption("item");
        require(option != null, "error.input");
        var recipe = ShopCatalog.recipe(option.getAsString());
        require(recipe != null, "craft.recipe.unknown");
        require(currency.craft(userId, recipe.output().id()), "craft.missing");
        return Messages.text(language, "craft.done", recipe.output().name());
    }

    private String repairText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var option = event.getOption("item");
        require(option != null, "error.input");
        var item = ShopCatalog.find(option.getAsString());
        require(item != null && item.tool(), "shop.item");
        require(currency.repair(userId, item.id()), "repair.failed");
        return Messages.text(language, "repair.done", item.name());
    }

    private String crateText(CommandContext event, Language language, String userId) throws CurrencyStoreException {
        var option = event.getOption("item");
        require(option != null, "error.input");
        var reward = currency.openCrate(userId, option.getAsString());
        require(reward != null, "crate.failed");
        var item = ShopCatalog.find(reward.itemId());
        return Messages.text(language, "crate.opened", item == null ? reward.itemId() : item.name(), reward.credits());
    }

    private String marketText(CommandContext event, Language language) throws CurrencyStoreException {
        require(market != null, "economy.unavailable");
        require(event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER), "language.permission");
        String action = event.getSubcommandName();
        if ("stock".equals(action)) {
            String id = event.getOption("product_id").getAsString().strip().toLowerCase(Locale.ROOT);
            long stock = event.getOption("product_stock").getAsLong();
            require(market.setStock(id, stock, clock.instant()), "shop.item");
            return Messages.text(language, "market.product.stock", id,
                stock < 0 ? Messages.text(language, "market.stock.unlimited") : Long.toString(stock));
        }
        if ("add".equals(action)) {
            String id = event.getOption("product_id").getAsString().strip().toLowerCase(Locale.ROOT);
            String name = event.getOption("product_name").getAsString().strip();
            String category = event.getOption("product_category").getAsString().strip().toLowerCase(Locale.ROOT);
            String description = event.getOption("product_description").getAsString().strip();
            long base = event.getOption("base_price").getAsLong();
            long stock = event.getOption("product_stock") == null ? -1 : event.getOption("product_stock").getAsLong();
            require(id.matches("[a-z0-9_-]{1,64}") && category.matches("[a-z0-9_-]{1,64}"), "shop.category");
            Instant now = clock.instant();
            long minimum = Math.max(1, Math.round(base * 0.70));
            long maximum = Math.max(base, Math.round(base * 1.30));
            var product = new DeseModels.Product(id, name, description, category, base, base, minimum,
                maximum, 0.05, stock, now, now, now.plus(PostgresMarketStore.PRICE_PERIOD),
                true, "common", List.of());
            require(market.createProduct(product), "market.product.exists");
            return Messages.text(language, "market.product.added", name, id, category, base);
        }
        throw new UserError("error.unknown");
    }

    private String itemInfoText(CommandContext event, Language language) throws CurrencyStoreException {
        require(market != null, "economy.unavailable");
        String id = event.getOption("item").getAsString();
        Instant now = clock.instant();
        var product = market.product(id, now);
        require(product != null, "shop.item");
        long price = market.quote(event.getGuild().getId(), event.getUser().getId(), product.id(), now);
        String stock = product.unlimitedStock() ? Messages.text(language, "market.stock.unlimited") : Long.toString(product.stock());
        String response = Messages.text(language, "market.product.info", product.name(), product.id(),
            product.description(), product.category(), price < 0 ? Messages.text(language, "market.price.unavailable") : price,
            stock);
        return persona.decorate(response, ToriPersona.Context.SHOP_BROWSE, language);
    }

    private String priceHistoryText(CommandContext event, Language language) throws CurrencyStoreException {
        require(market != null, "economy.unavailable");
        String id = event.getOption("item").getAsString();
        var history = market.history(id, 10);
        if (history.isEmpty()) return Messages.text(language, "market.history.empty");
        return Messages.text(language, "market.history.title", id) + "\n" + history.stream()
            .map(point -> "<t:" + point.at().getEpochSecond() + ":f> — " + point.price()
                + " credits (`" + point.reason() + "`)")
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static int quantityOption(CommandContext event) {
        var option = event.getOption("quantity");
        if (option == null) return 1;
        int count = option.getAsInt();
        require(count >= 1 && count <= 100, "error.input");
        return count;
    }
    private String status(CommandContext event, Language language) {
        var actionOption = event.getOption("action");
        String action = actionOption == null ? "show" : actionOption.getAsString();
        var textsOption = event.getOption("texts");
        var intervalOption = event.getOption("interval_ms");
        var jda = event.getJDA();
        synchronized (rotation) {
            require(!closed, "shutting.down");
            if (action.equals("show") || action.equals("stop")) {
                require(textsOption == null && intervalOption == null, "status.options");
            }
            switch (action) {
                case "start" -> {
                    var texts = StatusRotation.parseTexts(textsOption == null ? null : textsOption.getAsString());
                    long intervalMs = intervalOption == null ? StatusRotation.DEFAULT_INTERVAL_MS : intervalOption.getAsLong();
                    rotation.start(texts, intervalMs,
                        text -> applyPresence(jda, OnlineStatus.ONLINE, Activity.customStatus(text)));
                    defaultRotation = false;
                    return texts.size() == 1 ? Messages.text(language, "status.single.started")
                        : Messages.text(language, "status.started", texts.size(), intervalMs);
                }
                case "stop" -> {
                    startDefaultStatus(jda);
                    return Messages.text(language, "status.stopped");
                }
                case "show" -> {
                    if (defaultRotation) return Messages.text(language, "status.default");
                    var state = rotation.snapshot();
                    if (state.running() && state.texts().size() == 1) return Messages.text(language, "status.single.running");
                    return state.running() ? Messages.text(language, "status.running", state.texts().size(), state.intervalMs())
                        : Messages.text(language, "status.inactive");
                }
                default -> throw new UserError("status.action");
            }
        }
    }
    public static String help(Language language) {
        var embed = helpEmbed(language);
        var text = new StringBuilder(embed.getTitle());
        for (var field : embed.getFields()) text.append("\n\n").append(field.getName()).append("\n").append(field.getValue());
        return text.toString();
    }
    public static MessageEmbed helpEmbed(Language language) {
        var embed = ToriEmbeds.create(ToriEmbeds.Category.INFO, language)
            .setTitle(Messages.text(language, "help.title"));
        for (String section : List.of("music", "moderation", "general", "owner")) {
            String body = Messages.text(language, "help." + section + ".body");
            if (section.equals("general")) body += "\n" + Messages.text(language, "help.economy.body")
                + "\n" + Messages.text(language, "help.economy.market");
            embed.addField(Messages.text(language, "help." + section + ".title"), body, false);
        }
        return embed.build();
    }
    @Override protected MessageEmbed handleEmbed(CommandContext event, Language language) {
        if (event.getName().equals("help")) return helpEmbed(language);
        if (event.getName().equals("stats")) {
            var jda = event.getJDA();
            String inviteUrl = "https://discord.com/oauth2/authorize?client_id=" + jda.getSelfUser().getId()
                + "&scope=bot%20applications.commands";
            var manager = jda.getShardManager();
            var guilds = manager == null ? jda.getGuildCache().asList() : manager.getGuildCache().asList();
            long members = guilds.stream().mapToLong(guild -> Math.max(0, guild.getMemberCount())).sum();
            var embed = ToriEmbeds.create(ToriEmbeds.Category.STATS, language)
                .setTitle(Messages.text(language, "stats.title"))
                .addField(Messages.text(language, "stats.uptime"), sessionUptime(startedAt, clock.instant()), false)
                .addField(Messages.text(language, "stats.servers"), Long.toString(guilds.size()), true)
                .addField(Messages.text(language, "stats.members"), members + "\n" + Messages.text(language, "stats.members.note"), false)
                .addField(Messages.text(language, "stats.version"), BotVersion.CURRENT, true)
                .addField(Messages.text(language, "stats.invite"), "[" + Messages.text(language, "stats.invite.link") + "](" + inviteUrl + ")", false)
                ;
            if (statsStore != null) {
                String owner = configuredOwnerId == 0 ? Messages.text(language, "stats.unset") : "<@" + configuredOwnerId + ">";
                var guild = event.getGuild();
                var channel = event.getChannel();
                embed.addField(Messages.text(language, "stats.guild"), clip(guild.getName(), 200) + " (`" + guild.getId() + "`)", false)
                    .addField(Messages.text(language, "stats.channel"), "<#" + channel.getId() + "> (`" + channel.getId() + "`)", false)
                    .addField(Messages.text(language, "stats.owner"), owner, true)
                    .addField(Messages.text(language, "stats.creator"), creator == null || creator.isBlank() ? Messages.text(language, "stats.unset") : clip(creator, 200), true)
                    .addField(Messages.text(language, "stats.code"), "Java 21 · JDA · Lavalink ❤️", false)
                    .setTimestamp(clock.instant())
                    .setFooter(ToriEmbeds.footer(language, Messages.text(language, "stats.bot.message", jda.getSelfUser().getName())));
                try {
                    var saved = statsStore.stats(jda.getSelfUser().getId(), guild.getId(), guild.getName(), channel.getId(), channel.getName());
                    embed.addField(Messages.text(language, "stats.last.restart"), saved.lastRestart() == null ? "—" : "<t:" + Instant.parse(saved.lastRestart()).getEpochSecond() + ":R>", true);
                } catch (java.sql.SQLException ex) {
                    embed.addField(Messages.text(language, "stats.database"), Messages.text(language, "stats.database.failed"), false);
                }
            }
            return embed.build();
        }
        if (!event.getName().equals("avatar")) return null;
        var option = event.getOption("user_id");
        String id = avatarUserId(option == null ? null : option.getAsString());
        try {
            User user = event.getJDA().retrieveUserById(id).timeout(10, TimeUnit.SECONDS).complete();
            return avatarEmbed(user, language);
        } catch (ErrorResponseException ex) {
            if (ex.getErrorResponse() == ErrorResponse.UNKNOWN_USER) throw new UserError("avatar.not_found");
            throw ex;
        }
    }
    static String avatarUserId(String value) {
        String id = value == null ? "" : value.strip();
        require(id.matches("[0-9]{17,20}"), "avatar.invalid_id");
        try { require(Long.parseUnsignedLong(id) != 0, "avatar.invalid_id"); }
        catch (NumberFormatException ex) { throw new UserError("avatar.invalid_id"); }
        return id;
    }
    static MessageEmbed avatarEmbed(User user, Language language) {
        String url = user.getEffectiveAvatarUrl();
        url += (url.contains("?") ? "&" : "?") + "size=1024";
        return ToriEmbeds.create(ToriEmbeds.Category.INFO, language)
            .setTitle(Messages.text(language, "avatar.title", clip(user.getEffectiveName(), 100)))
            .setDescription(Messages.text(language, "avatar.link", url))
            .setImage(url).setFooter(ToriEmbeds.footer(language, "ID: " + user.getId())).build();
    }
    @Override protected Runnable afterReply(CommandContext event) {
        if (event.getName().equals("shutdown")) return shutdown;
        return event.getName().equals("restart") ? restart : super.afterReply(event);
    }
    static String latency(Language language, long milliseconds) {
        return milliseconds < 0 ? Messages.text(language, "ping.unavailable") : milliseconds + " ms";
    }
    static String sessionUptime(Instant startedAt, Instant now) {
        long seconds = Math.max(0, Duration.between(startedAt, now).getSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        if (seconds < 86400) return seconds / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
        return seconds / 86400 + "d " + seconds % 86400 / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s";
    }
    @Override public void close() {
        synchronized (rotation) {
            if (closed) return;
            closed = true;
            rotation.close();
        }
        super.close();
    }
}
