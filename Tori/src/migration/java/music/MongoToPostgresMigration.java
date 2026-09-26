package music;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.*;

/** One-shot, source-read-only copy of the exact live Mongo collections to an empty PostgreSQL schema. */
public final class MongoToPostgresMigration {
    private static final List<String> COLLECTION_ORDER = List.of(
        "bot_stats_context", "bot_events", "guild_prefixes", "moderation_cases", "guild_languages",
        "guild_ticket_order_config", "guild_ticket_order_counters", "guild_orders", "guild_order_status_events",
        "guild_tickets", "guild_ticket_events", "guild_ticket_transcripts");
    private static final Set<String> COLLECTIONS = Set.copyOf(COLLECTION_ORDER);
    private static final String[] TABLES = {
        "bot_stats_context", "bot_events", "guild_prefixes", "moderation_cases", "guild_languages",
        "guild_orders", "guild_order_status_events", "guild_order_assignment_events", "guild_tickets",
        "guild_ticket_members", "guild_ticket_events", "guild_ticket_transcripts", "guild_ticket_order_config",
        "guild_ticket_order_counters", "mongo_import_archive"
    };
    private record Link(String guild, long order, long ticket) {}
    private MongoToPostgresMigration() {}

    public static void main(String[] args) {
        BotConfig config = BotConfig.load();
        String sourceUri = config.required("TORI_MONGO_SOURCE_URI");
        String sourceDb = config.get("TORI_MONGO_SOURCE_DATABASE", "tori_main");
        if (!sourceDb.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("TORI_MONGO_SOURCE_DATABASE is invalid.");
        try (var target = PostgresDatabase.fromConfig(config);
             MongoClient source = MongoClients.create(sourceUri)) {
            var database = source.getDatabase(sourceDb);
            for (String name : database.listCollectionNames()) {
                if (name.startsWith("system.")) continue;
                if (!COLLECTIONS.contains(name) && database.getCollection(name).countDocuments() > 0)
                    throw new IllegalStateException("Unexpected nonempty source collection found; migration aborted for review.");
            }
            try (Connection connection = target.connection()) {
                connection.setAutoCommit(false);
                try {
                    requireEmpty(connection);
                    Map<String, Long> counts = new LinkedHashMap<>();
                    List<Link> orderLinks = new ArrayList<>(), ticketLinks = new ArrayList<>();
                    for (String collection : COLLECTION_ORDER) {
                        long count;
                        try (MongoCursor<Document> cursor = database.getCollection(collection).find().iterator()) {
                            count = copyCollection(connection, cursor, collection, orderLinks, ticketLinks);
                        }
                        counts.put(collection, count);
                    }
                    copySymmetricLinks(connection, orderLinks, ticketLinks);
                    repairCounters(connection);
                    connection.commit();
                    long total = counts.values().stream().mapToLong(Long::longValue).sum();
                    System.out.println("Mongo-to-PostgreSQL copy completed; " + total + " source documents archived and mapped. Source unchanged.");
                } catch (Exception ex) {
                    connection.rollback();
                    // Keep output safe: driver messages may contain connection credentials.
                    throw new IllegalStateException("Mongo-to-PostgreSQL copy failed; target transaction rolled back. Check the source snapshot and schema mapping.");
                }
            }
        } catch (IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("Cannot connect to the Mongo source or PostgreSQL target. Check configuration and service availability."); }
    }

    private static void requireEmpty(Connection c) throws SQLException {
        for (String table : TABLES) {
            try (var sql = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM " + table + " LIMIT 1)"); var rows = sql.executeQuery()) {
                rows.next(); if (rows.getBoolean(1)) throw new IllegalStateException("PostgreSQL target is not empty; migration refused.");
            }
        }
    }

    private static long copyCollection(Connection c, Iterator<Document> documents, String collection,
                                       List<Link> orderLinks, List<Link> ticketLinks) throws Exception {
        long count = 0;
        while (documents.hasNext()) {
            Document d = documents.next();
            archive(c, collection, d);
            switch (collection) {
                case "bot_stats_context" -> insert(c, """
                    INSERT INTO bot_stats_context(bot_id,guild_id,channel_id,guild_name,channel_name,updated_at,schema_version)
                    VALUES (?,?,?,?,?,?,?)
                    """, d.getString("bot_id"), d.getString("guild_id"), d.getString("channel_id"), d.getString("guild_name"),
                    d.getString("channel_name"), instant(d,"updated_at"), number(d,"schema_version",1));
                case "bot_events" -> insert(c, "INSERT INTO bot_events(session_id,event_type,reason,occurred_at,started_at,schema_version) VALUES (?,?,?,?,?,?)",
                    d.getString("session_id"),d.getString("event_type"),d.getString("reason"),instant(d,"occurred_at"),instant(d,"started_at"),number(d,"schema_version",1));
                case "guild_prefixes" -> insert(c,"INSERT INTO guild_prefixes(guild_id,prefix,schema_version) VALUES (?,?,?)",
                    d.getString("guild_id"),d.getString("prefix"),number(d,"schema_version",1));
                case "guild_languages" -> insert(c,"INSERT INTO guild_languages(guild_id,language,schema_version) VALUES (?,?,?)",
                    d.getString("guild_id"),d.getString("language"),number(d,"schema_version",1));
                case "moderation_cases" -> insert(c,"""
                    INSERT INTO moderation_cases(guild_id,case_id,action,guild_name,channel_id,moderator_id,moderator_name,target,reason,result,
                      occurred_at,language,webhook_status,webhook_attempts,webhook_http_status,webhook_error,webhook_updated_at,schema_version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,d.getString("guild_id"),d.getString("case_id"),d.getString("action"),d.getString("guild_name"),d.getString("channel_id"),
                    d.getString("moderator_id"),d.getString("moderator_name"),d.getString("target"),d.getString("reason"),d.getString("result"),
                    instant(d,"occurred_at"),d.getString("language"),d.getString("webhook_status"),number(d,"webhook_attempts",0),
                    numberOrNull(d,"webhook_http_status"),d.getString("webhook_error"),instant(d,"webhook_updated_at"),number(d,"schema_version",1));
                case "guild_ticket_order_config" -> copyConfig(c,d);
                case "guild_ticket_order_counters" -> insert(c,"INSERT INTO guild_ticket_order_counters(guild_id,counter_type,value,schema_version) VALUES (?,?,?,?)",
                    d.getString("guild_id"),d.getString("counter_type"),number(d,"value",0),number(d,"schema_version",1));
                case "guild_orders" -> {
                    Long ticket=numberOrNull(d,"ticket_id");
                    if(ticket!=null)orderLinks.add(new Link(d.getString("guild_id"),number(d,"order_id",0),ticket));
                    insert(c,"""
                        INSERT INTO guild_orders(guild_id,order_id,customer_id,product,description,assigned_staff_id,status,created_at,updated_at,
                          ticket_id,post_channel_id,post_message_id,schema_version) VALUES (?,?,?,?,?,?,?,?,?,NULL,?,?,?)
                        """,d.getString("guild_id"),number(d,"order_id",0),d.getString("customer_id"),d.getString("product"),d.getString("description"),
                        d.getString("assigned_staff_id"),d.getString("status"),instant(d,"created_at"),instant(d,"updated_at"),
                        d.getString("post_channel_id"),d.getString("post_message_id"),number(d,"schema_version",1));
                    copyStatusEvents(c,d);
                    copyAssignmentEvents(c,d);
                }
                case "guild_order_status_events" -> copyStatusEvent(c,d);
                case "guild_tickets" -> {
                    Long order=numberOrNull(d,"order_id");
                    if(order!=null)ticketLinks.add(new Link(d.getString("guild_id"),order,number(d,"ticket_id",0)));
                    insert(c,"""
                        INSERT INTO guild_tickets(guild_id,ticket_id,creator_id,category,channel_id,status,assigned_staff_id,created_at,closed_at,deleted_at,order_id,schema_version)
                        VALUES (?,?,?,?,?,?,?,?,?,?,NULL,?)
                        """,d.getString("guild_id"),number(d,"ticket_id",0),d.getString("creator_id"),d.getString("category"),d.getString("channel_id"),
                        d.getString("status"),d.getString("assigned_staff_id"),instant(d,"created_at"),instant(d,"closed_at"),instant(d,"deleted_at"),number(d,"schema_version",1));
                    for(Object member:list(d,"member_ids"))insert(c,"INSERT INTO guild_ticket_members(guild_id,ticket_id,member_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        d.getString("guild_id"),number(d,"ticket_id",0),string(member));
                    for(Document event:documents(d,"event_history"))copyTicketEvent(c,event);
                }
                case "guild_ticket_events" -> copyTicketEvent(c,d);
                case "guild_ticket_transcripts" -> insert(c,"""
                    INSERT INTO guild_ticket_transcripts(guild_id,ticket_id,source_channel_id,archived_by,archived_at,message_count,content,schema_version)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,d.getString("guild_id"),number(d,"ticket_id",0),d.getString("source_channel_id"),d.getString("archived_by"),
                    instant(d,"archived_at"),number(d,"message_count",0),d.getString("content"),number(d,"schema_version",1));
                default -> throw new IllegalStateException("No mapping for source collection.");
            }
            count++;
        }
        return count;
    }

    private static void copyConfig(Connection c,Document d) throws SQLException {
        insert(c,"""
            INSERT INTO guild_ticket_order_config(guild_id,order_staff_role_id,order_channel_id,ticket_category_id,ticket_panel_channel_id,queue_mode,
              ticket_staff_role_id,ticket_panel_title,ticket_panel_body,ticket_button_label,ticket_welcome_body,ticket_claim_label,ticket_close_label,ticket_delete_label,schema_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,d.getString("guild_id"),d.getString("order_staff_role_id"),d.getString("order_channel_id"),d.getString("ticket_category_id"),
            d.getString("ticket_panel_channel_id"),Optional.ofNullable(d.getString("queue_mode")).orElse("SEQUENTIAL"),d.getString("ticket_staff_role_id"),
            d.getString("ticket_panel_title"),d.getString("ticket_panel_body"),d.getString("ticket_button_label"),d.getString("ticket_welcome_body"),
            d.getString("ticket_claim_label"),d.getString("ticket_close_label"),d.getString("ticket_delete_label"),number(d,"schema_version",1));
    }
    private static void copyStatusEvents(Connection c,Document order) throws SQLException {
        for(Document event:documents(order,"status_history")) copyStatusEvent(c,event);
    }
    private static void copyStatusEvent(Connection c,Document d) throws SQLException {
        String guild=d.getString("guild_id");
        long order=number(d,"order_id",0);
        String previous=Optional.ofNullable(d.getString("previous_status")).orElse("UNKNOWN");
        String next=d.getString("new_status");
        String actor=d.getString("actor_id");
        Instant occurred=instant(d,"occurred_at");
        String interaction=Optional.ofNullable(d.getString("interaction_id")).orElseGet(() -> stableKey(
            new Document("guild_id",guild).append("order_id",order).append("previous_status",previous)
                .append("new_status",next).append("actor_id",actor).append("occurred_at",occurred.toString())));
        insert(c,"""
            INSERT INTO guild_order_status_events(guild_id,interaction_id,order_id,previous_status,new_status,actor_id,occurred_at,schema_version)
            VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(guild_id,interaction_id) DO NOTHING
            """,guild,interaction,order,previous,next,actor,occurred,number(d,"schema_version",1));
    }
    private static void copyAssignmentEvents(Connection c,Document order) throws SQLException {
        for(Document event:documents(order,"assignment_history")) {
            var joined=new Document(event).append("guild_id",order.getString("guild_id")).append("order_id",order.get("order_id"));
            copyAssignmentEvent(c,joined);
        }
    }
    private static void copyAssignmentEvent(Connection c,Document d) throws SQLException {
        insert(c,"INSERT INTO guild_order_assignment_events(guild_id,order_id,actor_id,staff_id,occurred_at) VALUES (?,?,?,?,?)",
            d.getString("guild_id"),number(d,"order_id",0),d.getString("actor_id"),d.getString("staff_id"),instant(d,"occurred_at"));
    }
    private static void copyTicketEvent(Connection c,Document d) throws SQLException {
        String guild=d.getString("guild_id"); long id=number(d,"ticket_id",0); String type=d.getString("event_type");
        String actor=Optional.ofNullable(d.getString("actor_id")).orElse("unknown"); String detail=Optional.ofNullable(d.getString("detail")).orElse("");
        Instant occurred=instant(d,"occurred_at");
        String key=stableKey(new Document("guild_id",guild).append("ticket_id",id).append("event_type",type)
            .append("actor_id",actor).append("detail",detail).append("occurred_at",occurred.toString()));
        insert(c,"INSERT INTO guild_ticket_events(event_key,guild_id,ticket_id,event_type,actor_id,detail,occurred_at,schema_version) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING",
            key,guild,id,type,actor,detail,occurred,number(d,"schema_version",1));
    }
    private static void archive(Connection c,String collection,Document d) throws Exception {
        String id=String.valueOf(d.get("_id"));
        insert(c,"INSERT INTO mongo_import_archive(collection_name,document_key,payload) VALUES (?,?,?::jsonb)",collection,id,d.toJson());
    }
    private static void copySymmetricLinks(Connection c,List<Link> orders,List<Link> tickets) throws SQLException {
        Set<Link> fromOrders=new HashSet<>(orders),fromTickets=new HashSet<>(tickets);
        if(!fromOrders.equals(fromTickets))
            throw new SQLException("Source contains missing or asymmetric ticket/order links; migration requires review.");
        for(Link link:fromOrders) {
            try(var p=c.prepareStatement("UPDATE guild_orders SET ticket_id=? WHERE guild_id=? AND order_id=? AND ticket_id IS NULL")){
                p.setLong(1,link.ticket());p.setString(2,link.guild());p.setLong(3,link.order());if(p.executeUpdate()!=1)throw new SQLException("Order link target missing or duplicated.");}
            try(var p=c.prepareStatement("UPDATE guild_tickets SET order_id=? WHERE guild_id=? AND ticket_id=? AND order_id IS NULL")){
                p.setLong(1,link.order());p.setString(2,link.guild());p.setLong(3,link.ticket());if(p.executeUpdate()!=1)throw new SQLException("Asymmetric ticket/order relation.");}
        }
    }
    private static void repairCounters(Connection c) throws SQLException {
        try (var sql = c.prepareStatement("""
            INSERT INTO guild_ticket_order_counters(guild_id,counter_type,value)
            SELECT guild_id,'order',MAX(order_id) FROM guild_orders GROUP BY guild_id
            ON CONFLICT(guild_id,counter_type) DO UPDATE
            SET value=GREATEST(guild_ticket_order_counters.value,excluded.value)
            """)) { sql.executeUpdate(); }
        try (var sql = c.prepareStatement("""
            INSERT INTO guild_ticket_order_counters(guild_id,counter_type,value)
            SELECT guild_id,'ticket',MAX(ticket_id) FROM guild_tickets GROUP BY guild_id
            ON CONFLICT(guild_id,counter_type) DO UPDATE
            SET value=GREATEST(guild_ticket_order_counters.value,excluded.value)
            """)) { sql.executeUpdate(); }
    }
    private static void insert(Connection c,String query,Object... values) throws SQLException {
        try(PreparedStatement p=c.prepareStatement(query)) {
            for(int i=0;i<values.length;i++) {
                Object value=values[i];int index=i+1;
                if(value==null)p.setNull(index,Types.NULL);
                else if(value instanceof Instant instant)p.setObject(index,instant.atOffset(java.time.ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
                else if(value instanceof Integer n)p.setInt(index,n);
                else if(value instanceof Long n)p.setLong(index,n);
                else p.setObject(index,value);
            }
            p.executeUpdate();
        }
    }
    private static long number(Document d,String key,long fallback) { Long value=numberOrNull(d,key);return value==null?fallback:value; }
    private static Long numberOrNull(Document d,String key) {
        Object value=d.get(key); if(value==null)return null;if(value instanceof Number n)return n.longValue();
        if(value instanceof String s)return Long.parseLong(s);throw new IllegalArgumentException("Expected a numeric source field.");
    }
    private static Instant instant(Document d,String key) {
        Object value=d.get(key);if(value==null)return null;
        if(value instanceof Date date)return date.toInstant();
        if(value instanceof String text)return Instant.parse(text);
        throw new IllegalArgumentException("Expected an ISO timestamp in the source document.");
    }
    private static List<?> list(Document d,String key) { Object value=d.get(key);return value instanceof List<?> items?items:List.of(); }
    private static List<Document> documents(Document d,String key) {
        var result=new ArrayList<Document>();for(Object value:list(d,key))if(value instanceof Document row)result.add(row);return result;
    }
    private static String string(Object value) { return value instanceof String s?s:String.valueOf(value); }
    private static String stableKey(Document d) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(d.toJson().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }catch(Exception ex){throw new IllegalStateException("Cannot create deterministic migration key.");}
    }
}
