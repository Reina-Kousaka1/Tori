package music;

import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** PostgreSQL persistence for tickets and real-service orders, scoped by guild. */
final class TicketOrderStore {
    record Order(long id, String guildId, String customerId, String product, String description,
                 String assignedStaffId, String status, Instant createdAt, Instant updatedAt,
                 Long ticketId, String postChannelId, String postMessageId) {}
    record Ticket(long id, String guildId, String creatorId, String category, String channelId,
                  String status, String assignedStaffId, Instant createdAt, Instant closedAt, Long orderId) {}
    record Config(String guildId, String staffRoleId, String orderChannelId, String ticketCategoryId,
                  String ticketPanelChannelId, String queueMode, String ticketStaffRoleId,
                  String panelTitle, String panelBody, String buttonLabel, String welcomeBody,
                  String claimLabel, String closeLabel, String deleteLabel) {}
    record StatusEvent(String previousStatus, String newStatus, String actorId, Instant occurredAt) {}

    private final PostgresDatabase database;
    TicketOrderStore(PostgresDatabase database) { this.database = Objects.requireNonNull(database); }

    private static void time(PreparedStatement sql, int index, Instant value) throws SQLException {
        sql.setObject(index, value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }
    private static Instant instant(ResultSet rows, String column) throws SQLException {
        OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }
    private static String nullableString(ResultSet rows, String column) throws SQLException { return rows.getString(column); }
    private static Order order(ResultSet r) throws SQLException {
        return new Order(r.getLong("order_id"), r.getString("guild_id"), r.getString("customer_id"),
            r.getString("product"), r.getString("description"), r.getString("assigned_staff_id"),
            r.getString("status"), instant(r,"created_at"), instant(r,"updated_at"), nullableLong(r,"ticket_id"),
            r.getString("post_channel_id"), r.getString("post_message_id"));
    }
    private static Ticket ticket(ResultSet r) throws SQLException {
        return new Ticket(r.getLong("ticket_id"), r.getString("guild_id"), r.getString("creator_id"),
            r.getString("category"), r.getString("channel_id"), r.getString("status"),
            r.getString("assigned_staff_id"), instant(r,"created_at"), instant(r,"closed_at"), nullableLong(r,"order_id"));
    }
    private static SQLException failed(SQLException ex) {
        // Never forward JDBC URLs, driver text, or SQL parameters to users/logs.
        return new SQLException("Ticket/order PostgreSQL operation failed.", ex.getSQLState());
    }

    private long next(Connection c, String guild, String type) throws SQLException {
        try (var sql = c.prepareStatement("""
            INSERT INTO guild_ticket_order_counters(guild_id,counter_type,value) VALUES (?,?,1)
            ON CONFLICT(guild_id,counter_type) DO UPDATE SET value=guild_ticket_order_counters.value+1
            RETURNING value
            """)) {
            sql.setString(1,guild); sql.setString(2,type);
            try (var rows=sql.executeQuery()) { if(!rows.next()) throw new SQLException("Counter did not return a value."); return rows.getLong(1); }
        }
    }
    long reserveTicketId(String guild) throws SQLException {
        try(var c=database.connection()) { return next(c,guild,"ticket"); }
        catch(SQLException ex) { throw failed(ex); }
    }

    Config config(String guild) throws SQLException {
        try(var c=database.connection(); var sql=c.prepareStatement("SELECT * FROM guild_ticket_order_config WHERE guild_id=?")) {
            sql.setString(1,guild);
            try(var r=sql.executeQuery()) {
                if(!r.next()) return new Config(guild,null,null,null,null,"SEQUENTIAL",null,null,null,null,null,null,null,null);
                return new Config(guild,r.getString("order_staff_role_id"),r.getString("order_channel_id"),r.getString("ticket_category_id"),
                    r.getString("ticket_panel_channel_id"),Optional.ofNullable(r.getString("queue_mode")).orElse("SEQUENTIAL"),
                    r.getString("ticket_staff_role_id"),r.getString("ticket_panel_title"),r.getString("ticket_panel_body"),
                    r.getString("ticket_button_label"),r.getString("ticket_welcome_body"),r.getString("ticket_claim_label"),
                    r.getString("ticket_close_label"),r.getString("ticket_delete_label"));
            }
        } catch(SQLException ex) { throw failed(ex); }
    }
    void configure(String guild,String field,String value) throws SQLException {
        if(!Set.of("order_staff_role_id","order_channel_id","queue_mode","ticket_panel_channel_id").contains(field))
            throw new IllegalArgumentException("Unsupported setting.");
        if(field.equals("queue_mode")&&!Set.of("SEQUENTIAL","PARALLEL").contains(value)) throw new IllegalArgumentException("Invalid queue mode.");
        String sql="INSERT INTO guild_ticket_order_config(guild_id,"+field+") VALUES (?,?) ON CONFLICT(guild_id) DO UPDATE SET "+field+"=excluded."+field;
        try(var c=database.connection();var p=c.prepareStatement(sql)){p.setString(1,guild);p.setString(2,value);p.executeUpdate();}
        catch(SQLException ex){throw failed(ex);}
    }
    void configureTicket(String guild,Map<String,String> fields) throws SQLException {
        if(fields.isEmpty()) return;
        Set<String> allowed=Set.of("ticket_staff_role_id","ticket_category_id","ticket_panel_title","ticket_panel_body",
            "ticket_button_label","ticket_welcome_body","ticket_claim_label","ticket_close_label","ticket_delete_label");
        if(!allowed.containsAll(fields.keySet())) throw new IllegalArgumentException("Unsupported ticket setting.");
        String columns=String.join(",",fields.keySet());
        String values=String.join(",",Collections.nCopies(fields.size()+1,"?"));
        String conflict=fields.keySet().stream().map(k->k+"=excluded."+k).collect(java.util.stream.Collectors.joining(","));
        String query="INSERT INTO guild_ticket_order_config(guild_id,"+columns+") VALUES ("+values+") ON CONFLICT(guild_id) DO UPDATE SET "+conflict;
        try(var c=database.connection();var p=c.prepareStatement(query)){p.setString(1,guild);int i=2;for(String key:fields.keySet())p.setString(i++,fields.get(key));p.executeUpdate();}
        catch(SQLException ex){throw failed(ex);}
    }
    void resetTicketText(String guild) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("""
            UPDATE guild_ticket_order_config SET ticket_panel_title=NULL,ticket_panel_body=NULL,ticket_button_label=NULL,
              ticket_welcome_body=NULL,ticket_claim_label=NULL,ticket_close_label=NULL,ticket_delete_label=NULL WHERE guild_id=?
            """)){p.setString(1,guild);p.executeUpdate();} catch(SQLException ex){throw failed(ex);}
    }

    Order createOrder(String guild,String customer,String product,String description,String staff) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()) {
            c.setAutoCommit(false);
            try {
                long id=next(c,guild,"order");
                try(var p=c.prepareStatement("""
                    INSERT INTO guild_orders(guild_id,order_id,customer_id,product,description,assigned_staff_id,status,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,'NOTED',?,?)
                    """)) {p.setString(1,guild);p.setLong(2,id);p.setString(3,customer);p.setString(4,product);p.setString(5,description);
                    p.setString(6,staff);time(p,7,now);time(p,8,now);p.executeUpdate();}
                c.commit();return order(guild,id);
            } catch(SQLException ex){c.rollback();throw ex;}
        } catch(SQLException ex){throw failed(ex);}
    }
    void postOrder(String guild,long id,String channel,String message) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("UPDATE guild_orders SET post_channel_id=?,post_message_id=?,updated_at=? WHERE guild_id=? AND order_id=?")){
            p.setString(1,channel);p.setString(2,message);time(p,3,Instant.now());p.setString(4,guild);p.setLong(5,id);p.executeUpdate();
        }catch(SQLException ex){throw failed(ex);}
    }
    Order order(String guild,long id) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT * FROM guild_orders WHERE guild_id=? AND order_id=?")){
            p.setString(1,guild);p.setLong(2,id);try(var r=p.executeQuery()){return r.next()?order(r):null;}
        }catch(SQLException ex){throw failed(ex);}
    }
    List<Order> orders(String guild,boolean activeOnly) throws SQLException {
        String query="SELECT * FROM guild_orders WHERE guild_id=?"+(activeOnly?" AND status NOT IN ('DONE','CANCELLED')":"")+" ORDER BY created_at,order_id";
        try(var c=database.connection();var p=c.prepareStatement(query)){p.setString(1,guild);try(var r=p.executeQuery()){var out=new ArrayList<Order>();while(r.next())out.add(order(r));return List.copyOf(out);}}
        catch(SQLException ex){throw failed(ex);}
    }
    boolean transition(String guild,long id,String expected,String next,String actor,String interactionId) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()) {
            c.setAutoCommit(false);
            try {
                try(var p=c.prepareStatement("SELECT status FROM guild_orders WHERE guild_id=? AND order_id=? FOR UPDATE")){
                    p.setString(1,guild);p.setLong(2,id);try(var r=p.executeQuery()){if(!r.next()||!expected.equals(r.getString(1))){c.rollback();return false;}}
                }
                try(var p=c.prepareStatement("INSERT INTO guild_order_status_events(guild_id,interaction_id,order_id,previous_status,new_status,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(guild_id,interaction_id) DO NOTHING")){
                    p.setString(1,guild);p.setString(2,interactionId);p.setLong(3,id);p.setString(4,expected);p.setString(5,next);p.setString(6,actor);time(p,7,now);
                    if(p.executeUpdate()!=1){c.rollback();return false;}
                }
                try(var p=c.prepareStatement("UPDATE guild_orders SET status=?,updated_at=? WHERE guild_id=? AND order_id=? AND status=?")){
                    p.setString(1,next);time(p,2,now);p.setString(3,guild);p.setLong(4,id);p.setString(5,expected);
                    if(p.executeUpdate()!=1){c.rollback();return false;}
                }
                c.commit();return true;
            }catch(SQLException ex){c.rollback();throw ex;}
        }catch(SQLException ex){throw failed(ex);}
    }
    boolean assign(String guild,long id,String staff,String actor) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()) {c.setAutoCommit(false);
            try(var p=c.prepareStatement("UPDATE guild_orders SET assigned_staff_id=?,updated_at=? WHERE guild_id=? AND order_id=? AND status NOT IN ('DONE','CANCELLED')")){
                p.setString(1,staff);time(p,2,now);p.setString(3,guild);p.setLong(4,id);if(p.executeUpdate()!=1){c.rollback();return false;}
            }
            try(var p=c.prepareStatement("INSERT INTO guild_order_assignment_events(guild_id,order_id,actor_id,staff_id,occurred_at) VALUES (?,?,?,?,?)")){
                p.setString(1,guild);p.setLong(2,id);p.setString(3,actor);p.setString(4,staff);time(p,5,now);p.executeUpdate();}
            c.commit();return true;
        }catch(SQLException ex){throw failed(ex);}
    }
    Order editOrder(String guild,long id,String product,String description) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("UPDATE guild_orders SET product=?,description=?,updated_at=? WHERE guild_id=? AND order_id=? AND status NOT IN ('DONE','CANCELLED')")){
            p.setString(1,product);p.setString(2,description);time(p,3,Instant.now());p.setString(4,guild);p.setLong(5,id);if(p.executeUpdate()==0)return null;
        }catch(SQLException ex){throw failed(ex);}return order(guild,id);
    }
    List<StatusEvent> history(String guild,long id) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT previous_status,new_status,actor_id,occurred_at FROM guild_order_status_events WHERE guild_id=? AND order_id=? ORDER BY occurred_at,interaction_id")){
            p.setString(1,guild);p.setLong(2,id);try(var r=p.executeQuery()){var result=new ArrayList<StatusEvent>();while(r.next())result.add(new StatusEvent(r.getString(1),r.getString(2),r.getString(3),instant(r,"occurred_at")));return List.copyOf(result);}
        }catch(SQLException ex){throw failed(ex);}
    }

    Ticket createTicket(String guild,long id,String creator,String category,String channel) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()){c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO guild_tickets(guild_id,ticket_id,creator_id,category,channel_id,status,created_at) VALUES (?,?,?,?,?,'OPEN',?)")){
                p.setString(1,guild);p.setLong(2,id);p.setString(3,creator);p.setString(4,category);p.setString(5,channel);time(p,6,now);p.executeUpdate();}
            try(var p=c.prepareStatement("INSERT INTO guild_ticket_members(guild_id,ticket_id,member_id) VALUES (?,?,?)")){p.setString(1,guild);p.setLong(2,id);p.setString(3,creator);p.executeUpdate();}
            insertTicketEvent(c,guild,id,"OPENED",creator,"",now);
            c.commit();
        }catch(SQLException ex){throw failed(ex);}return ticket(guild,id);
    }
    private static void insertTicketEvent(Connection c,String guild,long id,String type,String actor,String detail,Instant now) throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO guild_ticket_events(event_key,guild_id,ticket_id,event_type,actor_id,detail,occurred_at) VALUES (?,?,?,?,?,?,?)")){
            p.setString(1,UUID.randomUUID().toString());p.setString(2,guild);p.setLong(3,id);p.setString(4,type);p.setString(5,actor);p.setString(6,detail);time(p,7,now);p.executeUpdate();}
    }
    void archiveTranscript(String guild,long id,String channel,String actor,String content,int messageCount) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("""
            INSERT INTO guild_ticket_transcripts(guild_id,ticket_id,source_channel_id,archived_by,archived_at,message_count,content)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT(guild_id,ticket_id) DO UPDATE SET source_channel_id=excluded.source_channel_id,
            archived_by=excluded.archived_by,archived_at=excluded.archived_at,message_count=excluded.message_count,content=excluded.content
            """)){
            p.setString(1,guild);p.setLong(2,id);p.setString(3,channel);p.setString(4,actor);time(p,5,Instant.now());p.setInt(6,messageCount);p.setString(7,content);p.executeUpdate();
        }catch(SQLException ex){throw failed(ex);}
    }
    String archivedTranscript(String guild,long id) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT content FROM guild_ticket_transcripts WHERE guild_id=? AND ticket_id=?")){
            p.setString(1,guild);p.setLong(2,id);try(var r=p.executeQuery()){return r.next()?r.getString(1):null;}
        }catch(SQLException ex){throw failed(ex);}
    }
    Ticket ticket(String guild,long id) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT * FROM guild_tickets WHERE guild_id=? AND ticket_id=?")){
            p.setString(1,guild);p.setLong(2,id);try(var r=p.executeQuery()){return r.next()?ticket(r):null;}
        }catch(SQLException ex){throw failed(ex);}
    }
    Ticket ticketByChannel(String guild,String channel) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT * FROM guild_tickets WHERE guild_id=? AND channel_id=?")){
            p.setString(1,guild);p.setString(2,channel);try(var r=p.executeQuery()){return r.next()?ticket(r):null;}
        }catch(SQLException ex){throw failed(ex);}
    }
    Ticket activeTicket(String guild,String creator,String category) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT * FROM guild_tickets WHERE guild_id=? AND creator_id=? AND category=? AND status='OPEN' ORDER BY ticket_id LIMIT 1")){
            p.setString(1,guild);p.setString(2,creator);p.setString(3,category);try(var r=p.executeQuery()){return r.next()?ticket(r):null;}
        }catch(SQLException ex){throw failed(ex);}
    }
    List<String> ticketMembers(String guild,long id) throws SQLException {
        try(var c=database.connection();var p=c.prepareStatement("SELECT member_id FROM guild_ticket_members WHERE guild_id=? AND ticket_id=? ORDER BY member_id")){
            p.setString(1,guild);p.setLong(2,id);try(var r=p.executeQuery()){var result=new ArrayList<String>();while(r.next())result.add(r.getString(1));return List.copyOf(result);}
        }catch(SQLException ex){throw failed(ex);}
    }
    boolean ticketStatus(String guild,long id,String expected,String next,String actor) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()){c.setAutoCommit(false);
            String closed=next.equals("CLOSED")?"?":"NULL";
            try(var p=c.prepareStatement("UPDATE guild_tickets SET status=?,closed_at="+closed+" WHERE guild_id=? AND ticket_id=? AND status=?")){
                p.setString(1,next);int index=2;if(next.equals("CLOSED"))time(p,index++,now);p.setString(index++,guild);p.setLong(index++,id);p.setString(index,expected);
                if(p.executeUpdate()!=1){c.rollback();return false;}}
            insertTicketEvent(c,guild,id,next,actor,"",now);c.commit();return true;
        }catch(SQLException ex){throw failed(ex);}
    }
    boolean ticketMember(String guild,long id,String member,String actor,boolean add) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()){c.setAutoCommit(false);
            try(var check=c.prepareStatement("SELECT 1 FROM guild_tickets WHERE guild_id=? AND ticket_id=? AND status='OPEN' FOR UPDATE")){
                check.setString(1,guild);check.setLong(2,id);try(var r=check.executeQuery()){if(!r.next()){c.rollback();return false;}}}
            int changed;
            if(add){try(var p=c.prepareStatement("INSERT INTO guild_ticket_members(guild_id,ticket_id,member_id) VALUES (?,?,?) ON CONFLICT DO NOTHING")){p.setString(1,guild);p.setLong(2,id);p.setString(3,member);changed=p.executeUpdate();}}
            else {try(var p=c.prepareStatement("DELETE FROM guild_ticket_members WHERE guild_id=? AND ticket_id=? AND member_id=?")){p.setString(1,guild);p.setLong(2,id);p.setString(3,member);changed=p.executeUpdate();}}
            if(changed==1)insertTicketEvent(c,guild,id,add?"MEMBER_ADDED":"MEMBER_REMOVED",actor,member,now);
            c.commit();return changed==1;
        }catch(SQLException ex){throw failed(ex);}
    }
    boolean claimTicket(String guild,long id,String staff) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()){c.setAutoCommit(false);
            try(var p=c.prepareStatement("UPDATE guild_tickets SET assigned_staff_id=? WHERE guild_id=? AND ticket_id=? AND status='OPEN' AND assigned_staff_id IS NULL")){
                p.setString(1,staff);p.setString(2,guild);p.setLong(3,id);if(p.executeUpdate()!=1){c.rollback();return false;}}
            insertTicketEvent(c,guild,id,"CLAIMED",staff,"",now);c.commit();return true;
        }catch(SQLException ex){throw failed(ex);}
    }

    /** Links both sides atomically; inconsistent or cross-guild IDs are rejected. */
    boolean linkTicketOrder(String guild,long ticketId,long orderId) throws SQLException {
        try(var c=database.connection()){c.setAutoCommit(false);
            try(var p=c.prepareStatement("SELECT order_id FROM guild_tickets WHERE guild_id=? AND ticket_id=? FOR UPDATE")){
                p.setString(1,guild);p.setLong(2,ticketId);try(var r=p.executeQuery()){if(!r.next()){c.rollback();return false;}Long current=nullableLong(r,"order_id");if(current!=null&&current!=orderId){c.rollback();return false;}}}
            try(var p=c.prepareStatement("SELECT ticket_id FROM guild_orders WHERE guild_id=? AND order_id=? FOR UPDATE")){
                p.setString(1,guild);p.setLong(2,orderId);try(var r=p.executeQuery()){if(!r.next()){c.rollback();return false;}Long current=nullableLong(r,"ticket_id");if(current!=null&&current!=ticketId){c.rollback();return false;}}}
            try(var p=c.prepareStatement("UPDATE guild_tickets SET order_id=? WHERE guild_id=? AND ticket_id=?")){p.setLong(1,orderId);p.setString(2,guild);p.setLong(3,ticketId);p.executeUpdate();}
            try(var p=c.prepareStatement("UPDATE guild_orders SET ticket_id=? WHERE guild_id=? AND order_id=?")){p.setLong(1,ticketId);p.setString(2,guild);p.setLong(3,orderId);p.executeUpdate();}
            c.commit();return true;
        }catch(SQLException ex){throw failed(ex);}
    }
    boolean linkOrderTicket(String guild,long orderId,long ticketId) throws SQLException { return linkTicketOrder(guild,ticketId,orderId); }
    boolean markTicketDeleted(String guild,long id,String actor) throws SQLException {
        Instant now=Instant.now();
        try(var c=database.connection()){c.setAutoCommit(false);
            try(var p=c.prepareStatement("UPDATE guild_tickets SET status='DELETED',deleted_at=? WHERE guild_id=? AND ticket_id=? AND status='CLOSED'")){
                time(p,1,now);p.setString(2,guild);p.setLong(3,id);if(p.executeUpdate()!=1){c.rollback();return false;}}
            insertTicketEvent(c,guild,id,"DELETED",actor,"Discord channel deleted; record retained",now);c.commit();return true;
        }catch(SQLException ex){throw failed(ex);}
    }
}
