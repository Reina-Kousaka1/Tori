package music;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Guild isolated ticket and real-service order workflow. It does not access economy data. */
final class TicketOrderBot extends CommandListener {
    private static final String OPEN_BUTTON = "tori:ticket:open";
    private static final String ACTION_FAILED = "The action could not be completed. If this persists, contact a server administrator.";
    private static final String ORDER_PERMISSION_REQUIRED = "This action requires Manage Server, Administrator, or the configured order staff role.";
    private static final String TICKET_CHANNEL_REQUIRED = "This command must be used inside a ticket channel.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu").withLocale(Locale.US).withZone(ZoneOffset.UTC);
    private final TicketOrderStore store;
    TicketOrderBot(LanguageStore languages, TicketOrderStore store) {
        super(Set.of("ticket", "order"), languages); this.store = store;
    }
    static List<CommandData> commands() { return LocalizedCommands.apply(List.of(orderCommand(), ticketCommand())); }

    private static SlashCommandData orderCommand() {
        var c = Commands.slash("order", "Manage real customer orders and service work");
        c.addSubcommands(new SubcommandData("create", "Create an order")
            .addOptions(new OptionData(OptionType.USER,"customer","Customer",true), new OptionData(OptionType.STRING,"product","Product or service",true).setMaxLength(100),
                new OptionData(OptionType.STRING,"description","Order details",true).setMaxLength(1000),
                new OptionData(OptionType.USER,"assigned_staff","Assigned staff member",false)),
            new SubcommandData("view", "View an order").addOption(OptionType.INTEGER,"id","Order ID",true),
            new SubcommandData("queue", "View active guild queue"),
            new SubcommandData("assign", "Assign an order to staff").addOptions(new OptionData(OptionType.INTEGER,"id","Order ID",true),new OptionData(OptionType.USER,"staff","Staff member",true)),
            new SubcommandData("edit", "Edit an active order").addOptions(new OptionData(OptionType.INTEGER,"id","Order ID",true),new OptionData(OptionType.STRING,"product","New product",false).setMaxLength(100),new OptionData(OptionType.STRING,"description","New description",false).setMaxLength(1000)),
            new SubcommandData("cancel", "Cancel an order").addOption(OptionType.INTEGER,"id","Order ID",true),
            new SubcommandData("history", "Show order status history").addOption(OptionType.INTEGER,"id","Order ID",true),
            new SubcommandData("post", "Repair or recreate a missing order post").addOption(OptionType.INTEGER,"id","Order ID",true),
            new SubcommandData("config", "Configure order workflow")
                .addOptions(new OptionData(OptionType.ROLE,"staff_role","Order staff role",false),new OptionData(OptionType.CHANNEL,"channel","Order post channel",false),
                    new OptionData(OptionType.STRING,"queue_mode","Sequential or parallel processing",false).addChoice("Sequential","SEQUENTIAL").addChoice("Parallel","PARALLEL")));
        return c;
    }
    private static SlashCommandData ticketCommand() {
        var c = Commands.slash("ticket", "Create and manage private support tickets");
        c.addSubcommands(new SubcommandData("panel", "Post a ticket panel").addOption(OptionType.CHANNEL,"channel","Panel channel",true),
            new SubcommandData("config", "Configure ticket staff, category and text")
                .addOptions(new OptionData(OptionType.ROLE,"staff_role","Ticket support role",false),new OptionData(OptionType.CHANNEL,"category","Parent category",false),
                    new OptionData(OptionType.STRING,"panel_title","Ticket panel heading",false).setMaxLength(100),
                    new OptionData(OptionType.STRING,"panel_text","Ticket panel message",false).setMaxLength(1500),
                    new OptionData(OptionType.STRING,"button_text","Open-ticket button label",false).setMaxLength(80),
                    new OptionData(OptionType.STRING,"welcome_text","New-ticket message; use {ticket}, {category}, {user}",false).setMaxLength(1500),
                    new OptionData(OptionType.STRING,"claim_button","Claim button label",false).setMaxLength(80),
                    new OptionData(OptionType.STRING,"close_button","Close button label",false).setMaxLength(80),
                    new OptionData(OptionType.STRING,"delete_button","Delete button label",false).setMaxLength(80)),
            new SubcommandData("reset_text", "Restore default ticket panel and welcome text"),
            new SubcommandData("open", "Open a private ticket").addOptions(new OptionData(OptionType.STRING,"category","Ticket category",false)
                .addChoice("Support","SUPPORT").addChoice("Reports","REPORTS").addChoice("Orders","ORDERS").addChoice("General","GENERAL")),
            new SubcommandData("details", "Show this ticket and any linked order"),
            new SubcommandData("claim", "Claim this ticket"), new SubcommandData("close", "Close this ticket"),
            new SubcommandData("reopen", "Reopen this ticket"), new SubcommandData("transcript", "Export this ticket conversation")
                .addOptions(new OptionData(OptionType.INTEGER,"id","Archived ticket ID (omit for this ticket)",false).setRequiredRange(1,9_007_199_254_740_991L)),
            new SubcommandData("add", "Add a member to this ticket").addOption(OptionType.USER,"member","Member",true),
            new SubcommandData("remove", "Remove a member from this ticket").addOption(OptionType.USER,"member","Member",true),
            new SubcommandData("link_order", "Link an order to this ticket").addOption(OptionType.INTEGER,"order_id","Order ID",true));
        return c;
    }

    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!accepts(event.getName()) || event.isAcknowledged()) return;
        if (event.getGuild() == null) { event.reply("This command can only be used in a server.").setEphemeral(true).queue(); return; }
        event.deferReply(true).queue(hook -> serial(event.getGuild().getIdLong(), () -> {
            String result;
            try { result = handleCommand(event); }
            catch (IllegalArgumentException ex) { result = actionErrorMessage(ex); }
            catch (Exception ex) {
                LoggerFactory.getLogger(TicketOrderBot.class).warn("Ticket/order command failed ({})", ex.getClass().getSimpleName());
                result = actionErrorMessage(ex);
            }
            hook.editOriginal(result).setAllowedMentions(List.of()).queue(null,
                failure -> LoggerFactory.getLogger(TicketOrderBot.class).warn("Ticket/order response failed ({})", failure.getClass().getSimpleName()));
        }));
    }

    @Override public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("tori:ticket:") && !id.startsWith("tori:order:")) return;
        if (event.getGuild() == null) { event.reply("This control only works in its server.").setEphemeral(true).queue(); return; }
        event.deferReply(true).queue(hook -> serial(event.getGuild().getIdLong(), () -> {
            try {
                String response;
                if (id.equals(OPEN_BUTTON)) response = "Ticket opened: <#"+openTicket(event.getGuild(), event.getUser().getId(), "SUPPORT").getId()+">.";
                else if (id.startsWith("tori:order:")) response = updateOrderButton(event, id);
                else if (id.equals("tori:ticket:delete")) {
                    var ticket = store.ticketByChannel(event.getGuild().getId(), event.getChannel().getId());
                    if (ticket == null) response = "Ticket not found in this channel.";
                    else {
                        requireStaff(event.getMember(), store.config(ticket.guildId()).ticketStaffRoleId());
                        if (!canDeleteTicket(ticket.status())) response = "Close this ticket before deleting its channel.";
                        else {
                            hook.editOriginal("Delete the channel for ticket #"+ticket.id()+"? Its ticket record and history will be kept.")
                                .setComponents(ActionRow.of(Button.danger("tori:ticket:delete-confirm:"+ticket.id(), "Delete channel"),
                                    Button.secondary("tori:ticket:delete-cancel", "Cancel"))).queue();
                            return;
                        }
                    }
                }
                else if (id.equals("tori:ticket:delete-cancel")) response = "Ticket deletion cancelled.";
                else if (id.startsWith("tori:ticket:delete-confirm:")) response = confirmTicketDelete(event, id);
                else if (id.equals("tori:ticket:close")) response = setTicketStatus(event, "CLOSED", "OPEN");
                else if (id.equals("tori:ticket:reopen")) response = setTicketStatus(event, "OPEN", "CLOSED");
                else if (id.equals("tori:ticket:claim")) response = claimTicket(event);
                else response = "This control is no longer available.";
                hook.editOriginal(response).setAllowedMentions(List.of()).queue();
            } catch (IllegalArgumentException ex) {
                hook.editOriginal(actionErrorMessage(ex)).setAllowedMentions(List.of()).queue();
            } catch (Exception ex) {
                if (ex instanceof ClassCastException)
                    LoggerFactory.getLogger(TicketOrderBot.class).warn("Ticket/order button failed with incompatible stored data.", ex);
                else LoggerFactory.getLogger(TicketOrderBot.class).warn("Ticket/order button failed ({})", ex.getClass().getSimpleName());
                hook.editOriginal(actionErrorMessage(ex)).setAllowedMentions(List.of()).queue();
            }
        }));
    }

    private String handleCommand(SlashCommandInteractionEvent e) throws Exception {
        String guild=e.getGuild().getId(), sub=e.getSubcommandName();
        return e.getName().equals("order") ? orderCommand(e,guild,sub) : ticketCommand(e,guild,sub);
    }
    private String orderCommand(SlashCommandInteractionEvent e,String guild,String sub) throws Exception {
        var cfg=store.config(guild);
        if (!"config".equals(sub)) requireOrderStaff(e.getMember(),cfg.staffRoleId());
        switch(sub) {
            case "config" -> {
                requireAdmin(e.getMember()); boolean any=false;
                if(e.getOption("staff_role")!=null){store.configure(guild,"order_staff_role_id",e.getOption("staff_role").getAsRole().getId());any=true;}
                if(e.getOption("channel")!=null){store.configure(guild,"order_channel_id",e.getOption("channel").getAsChannel().getId());any=true;}
                if(e.getOption("queue_mode")!=null){store.configure(guild,"queue_mode",e.getOption("queue_mode").getAsString());any=true;}
                return any?"Order settings saved.":"Provide staff_role, channel, or queue_mode to change a setting.";
            }
            case "create" -> {
                var customer=e.getOption("customer").getAsUser();
                String product=e.getOption("product").getAsString(), desc=e.getOption("description").getAsString();
                String assigned=e.getOption("assigned_staff")==null?null:e.getOption("assigned_staff").getAsUser().getId();
                var order=store.createOrder(guild,customer.getId(),product,desc,assigned);
                var channel=cfg.orderChannelId()==null?null:e.getGuild().getTextChannelById(cfg.orderChannelId());
                if(channel==null)return "Order #"+order.id()+" was saved, but no valid order channel is configured. Configure one with /order config.";
                try {
                    var active=store.orders(guild,true);
                    var message=channel.sendMessageEmbeds(orderEmbed(order,position(order,active))).setComponents(orderButtons(order.id(),false)).setAllowedMentions(List.of()).complete();
                    store.postOrder(guild,order.id(),channel.getId(),message.getId());
                    return "Created order #"+order.id()+" and posted it in <#"+channel.getId()+">.";
                } catch(Exception ex) { return "Order #"+order.id()+" is saved, but its Discord post failed. It remains in the database; check channel permissions and create a replacement post after review."; }
            }
            case "queue" -> { return queueText(store.orders(guild,true),cfg.queueMode()); }
            case "view" -> { var o=store.order(guild,e.getOption("id").getAsLong()); return o==null?"Order not found on this server.":orderText(o,store.orders(guild,true)); }
            case "assign" -> { long id=e.getOption("id").getAsLong(); String staff=e.getOption("staff").getAsUser().getId(); boolean saved=store.assign(guild,id,staff,e.getUser().getId()); if(saved)refreshOrder(e.getGuild(),store.order(guild,id),store.orders(guild,true)); return saved?"Order #"+id+" assigned to <@"+staff+">.":"Active order not found."; }
            case "edit" -> {
                var old=store.order(guild,e.getOption("id").getAsLong()); if(old==null)return "Order not found on this server.";
                String product=e.getOption("product")==null?old.product():e.getOption("product").getAsString(); String desc=e.getOption("description")==null?old.description():e.getOption("description").getAsString();
                if(e.getOption("product")==null&&e.getOption("description")==null)return "Provide product or description to edit.";
                var updated=store.editOrder(guild,old.id(),product,desc); if(updated==null)return "Order is already done or cancelled.";
                refreshOrder(e.getGuild(),updated,store.orders(guild,true)); return "Order #"+old.id()+" updated.";
            }
            case "cancel" -> { long id=e.getOption("id").getAsLong(); var o=store.order(guild,id); if(o==null)return "Order not found on this server."; return transition(e,o,"CANCELLED"); }
            case "history" -> {
                long id=e.getOption("id").getAsLong(); if(store.order(guild,id)==null)return "Order not found on this server.";
                var events=store.history(guild,id); if(events.isEmpty())return "Order #"+id+" has no status changes yet.";
                var out=new StringBuilder("Status history for order #").append(id).append(':'); int start=Math.max(0,events.size()-20);
                for(int i=start;i<events.size();i++){var x=events.get(i);out.append("\n").append(x.previousStatus()).append(" → ").append(x.newStatus()).append(" by <@").append(x.actorId()).append("> at ").append(x.occurredAt());} return out.toString();
            }
            case "post" -> { long id=e.getOption("id").getAsLong(); var o=store.order(guild,id); return o==null?"Order not found on this server.":repairOrderPost(e,o); }
            default -> { return "Unknown order action."; }
        }
    }
    private String ticketCommand(SlashCommandInteractionEvent e,String guild,String sub) throws Exception {
        var cfg=store.config(guild);
        switch(sub) {
            case "config" -> {
                requireAdmin(e.getMember()); var fields = new LinkedHashMap<String, String>();
                if(e.getOption("staff_role")!=null) fields.put("ticket_staff_role_id",e.getOption("staff_role").getAsRole().getId());
                if(e.getOption("category")!=null) fields.put("ticket_category_id",e.getOption("category").getAsChannel().getId());
                if(e.getOption("panel_title")!=null) fields.put("ticket_panel_title",TicketText.validate(e.getOption("panel_title").getAsString(),"Panel title",100));
                if(e.getOption("panel_text")!=null) fields.put("ticket_panel_body",TicketText.validate(e.getOption("panel_text").getAsString(),"Panel text",1500));
                if(e.getOption("button_text")!=null) fields.put("ticket_button_label",TicketText.validate(e.getOption("button_text").getAsString(),"Open button",80));
                if(e.getOption("welcome_text")!=null) fields.put("ticket_welcome_body",TicketText.validate(e.getOption("welcome_text").getAsString(),"Welcome text",1500));
                if(e.getOption("claim_button")!=null) fields.put("ticket_claim_label",TicketText.validate(e.getOption("claim_button").getAsString(),"Claim button",80));
                if(e.getOption("close_button")!=null) fields.put("ticket_close_label",TicketText.validate(e.getOption("close_button").getAsString(),"Close button",80));
                if(e.getOption("delete_button")!=null) fields.put("ticket_delete_label",TicketText.validate(e.getOption("delete_button").getAsString(),"Delete button",80));
                if(fields.isEmpty()) {
                    var copy=TicketText.from(cfg);
                    return "Current ticket text (long messages abbreviated):\nPanel title: " + copy.panelTitle() + "\nPanel text: " + TicketText.preview(copy.panelBody())
                        + "\nOpen button: " + copy.buttonLabel() + "\nWelcome text: " + TicketText.preview(copy.welcomeBody())
                        + "\nTicket buttons: " + copy.claimLabel() + " / " + copy.closeLabel() + " / " + copy.deleteLabel();
                }
                store.configureTicket(guild,fields);
                return "Ticket settings saved. Run `/ticket panel` to publish the updated panel text; new tickets use the welcome text immediately.";
            }
            case "reset_text" -> {
                requireAdmin(e.getMember()); store.resetTicketText(guild);
                return "Ticket text restored to defaults. Run `/ticket panel` to publish a new panel.";
            }
            case "panel" -> {
                requireAdmin(e.getMember()); TextChannel channel=e.getOption("channel").getAsChannel().asTextChannel();
                var copy=TicketText.from(cfg);
                channel.sendMessage(copy.panelMessage()).setComponents(ActionRow.of(Button.primary(OPEN_BUTTON,copy.buttonLabel())))
                    .setAllowedMentions(List.of()).complete();
                store.configure(guild,"ticket_panel_channel_id",channel.getId()); return "Ticket panel posted in <#"+channel.getId()+">.";
            }
            case "open" -> { return "Ticket opened: <#"+openTicket(e.getGuild(),e.getUser().getId(),e.getOption("category")==null?"SUPPORT":e.getOption("category").getAsString()).getId()+">."; }
            case "claim" -> { return claimTicket(e); }
            case "details" -> {
                var t=currentTicket(e); String contextError=ticketContextError(t); if(contextError!=null)return contextError;
                requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),t.creatorId());
                var out=new StringBuilder("Ticket #").append(t.id()).append(" · ").append(t.category()).append(" · ").append(t.status()).append("\nCreator: <@").append(t.creatorId()).append(">");
                if(t.assignedStaffId()!=null)out.append("\nClaimed by: <@").append(t.assignedStaffId()).append(">");
                if(t.orderId()!=null){var o=store.order(guild,t.orderId());out.append("\nLinked order: #").append(t.orderId());if(o!=null)out.append(" · ").append(o.status()).append(" · ").append(o.product());}
                return out.toString();
            }
            case "close" -> { return setTicketStatus(e,"CLOSED","OPEN"); }
            case "reopen" -> { return setTicketStatus(e,"OPEN","CLOSED"); }
            case "add", "remove" -> {
                var t=currentTicket(e); String contextError=ticketContextError(t); if(contextError!=null)return contextError;
                requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),t.creatorId()); String id=e.getOption("member").getAsUser().getId();
                var channel=e.getGuild().getTextChannelById(t.channelId()); if(channel==null)return "Ticket record saved; its channel no longer exists.";
                Member member=e.getGuild().getMemberById(id); if(member==null)return "Member is not currently in this server.";
                if(id.equals(t.creatorId())&&sub.equals("remove"))return "The ticket creator cannot be removed from their own ticket.";
                if(!store.ticketMember(guild,t.id(),id,e.getUser().getId(),sub.equals("add")))return "Ticket is closed or could not be updated.";
                if(sub.equals("add"))channel.upsertPermissionOverride(member).grant(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY).complete();
                else channel.upsertPermissionOverride(member).deny(Permission.VIEW_CHANNEL).complete();
                return sub.equals("add")?"Member added to ticket.":"Member removed from ticket.";
            }
            case "link_order" -> {
                var t=currentTicket(e); String contextError=ticketContextError(t); if(contextError!=null)return contextError;
                requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),t.creatorId()); long order=e.getOption("order_id").getAsLong();
                var linkedOrder=store.order(guild,order); if(linkedOrder==null)return "Order not found on this server.";
                if(t.orderId()!=null&&t.orderId()!=order)return "Ticket already has a different linked order.";
                if(linkedOrder.ticketId()!=null&&linkedOrder.ticketId()!=t.id())return "Order already has a different linked ticket.";
                if(!store.linkTicketOrder(guild,t.id(),order))return "Could not link the ticket and order. Check their existing links and retry.";
                e.getChannel().sendMessage("Ticket #"+t.id()+" is linked to order #"+order+".").setAllowedMentions(List.of()).queue();
                return "Ticket linked to order #"+order+".";
            }
            case "transcript" -> {
                if(e.getOption("id")!=null) {
                    long id=e.getOption("id").getAsLong(); var archivedTicket=store.ticket(guild,id);
                    if(archivedTicket==null)return "Ticket not found on this server.";
                    requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),archivedTicket.creatorId());
                    String archived=store.archivedTranscript(guild,id);
                    if(archived==null)return "No archived transcript exists for ticket #"+id+".";
                    e.getHook().sendFiles(FileUpload.fromData(archived.getBytes(StandardCharsets.UTF_8),"ticket-"+id+"-transcript.txt")).setEphemeral(true).queue();
                    return "Archived transcript for ticket #"+id+" exported.";
                }
                var t=currentTicket(e); String contextError=ticketContextError(t); if(contextError!=null)return contextError;
                requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),t.creatorId()); return transcript(e,t);
            }
            default -> { return "Unknown ticket action."; }
        }
    }

    private TextChannel openTicket(net.dv8tion.jda.api.entities.Guild guild,String creator,String category) throws Exception {
        String guildId=guild.getId(); var cfg=store.config(guildId); var member=guild.getMemberById(creator); if(member==null)throw new IllegalStateException("creator absent");
        var existing=store.activeTicket(guildId,creator,category);
        if(existing!=null) { var existingChannel=guild.getTextChannelById(existing.channelId()); if(existingChannel!=null)return existingChannel; }
        long ticketId=store.reserveTicketId(guildId);
        var action=guild.createTextChannel(ticketChannelName(ticketId));
        Category parent=cfg.ticketCategoryId()==null?null:guild.getCategoryById(cfg.ticketCategoryId()); if(parent!=null)action.setParent(parent);
        TextChannel channel=action.complete();
        channel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.VIEW_CHANNEL).complete();
        channel.upsertPermissionOverride(member).grant(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY,Permission.MESSAGE_ATTACH_FILES).complete();
        channel.upsertPermissionOverride(guild.getSelfMember()).grant(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY,Permission.MESSAGE_ATTACH_FILES,Permission.MANAGE_CHANNEL).complete();
        Role staff=cfg.ticketStaffRoleId()==null?null:guild.getRoleById(cfg.ticketStaffRoleId());
        if(staff!=null)channel.upsertPermissionOverride(staff).grant(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY).complete();
        TicketOrderStore.Ticket ticket;
        try { ticket=store.createTicket(guildId,ticketId,creator,category,channel.getId()); }
        catch(Exception ex) { channel.delete().complete(); throw ex; }
        var copy=TicketText.from(cfg);
        channel.sendMessage(copy.welcomeMessage(ticket.id(),category,creator))
            .setComponents(ActionRow.of(Button.secondary("tori:ticket:claim",copy.claimLabel()),
                Button.danger("tori:ticket:close",copy.closeLabel()),Button.danger("tori:ticket:delete",copy.deleteLabel())))
            .setAllowedMentions(List.of()).complete();
        return channel;
    }
    static String ticketChannelName(long id) { return String.format(Locale.ROOT,"ticket-%03d",id); }
    static boolean canDeleteTicket(String status) { return "CLOSED".equals(status); }
    private String confirmTicketDelete(ButtonInteractionEvent event, String componentId) throws Exception {
        String[] parts = componentId.split(":");
        if (parts.length != 4) return "Invalid ticket delete confirmation.";
        long id;
        try { id = Long.parseLong(parts[3]); }
        catch (NumberFormatException ex) { return "Invalid ticket delete confirmation."; }
        var guild = event.getGuild();
        var ticket = store.ticket(guild.getId(), id);
        if (ticket == null || !ticket.channelId().equals(event.getChannel().getId())) return "Ticket not found in this channel.";
        requireStaff(event.getMember(), store.config(ticket.guildId()).ticketStaffRoleId());
        if (!canDeleteTicket(ticket.status())) return "Only closed tickets can be deleted.";
        var channel = guild.getTextChannelById(ticket.channelId());
        if (channel == null) return "Ticket channel is already missing; the ticket record and history were kept.";
        TranscriptSnapshot snapshot = buildTranscript(channel, ticket.id());
        store.archiveTranscript(ticket.guildId(),ticket.id(),ticket.channelId(),event.getUser().getId(),snapshot.content(),snapshot.messageCount());
        channel.delete().reason("Ticket #"+ticket.id()+" deleted by staff "+event.getUser().getId()).complete();
        if (!store.markTicketDeleted(ticket.guildId(), ticket.id(), event.getUser().getId()))
            return "Channel deleted and transcript archived, but the ticket status could not be updated. Its stored history remains for admin follow-up.";
        return "Ticket #"+ticket.id()+" deleted; transcript and ticket history retained. Retrieve it with `/ticket transcript id:"+ticket.id()+"`.";
    }
    private String updateOrderButton(ButtonInteractionEvent e,String component) throws Exception {
        String[] parts=component.split(":"); if(parts.length!=4)return "Invalid order control.";
        long id;
        try { id=Long.parseLong(parts[2]); }
        catch(NumberFormatException ex) { return "Invalid order control."; }
        String status=parts[3]; if(!Set.of("NOTED","PROCESSING","DONE").contains(status))return "Invalid status.";
        String guild=e.getGuild().getId(); var cfg=store.config(guild); requireOrderStaff(e.getMember(),cfg.staffRoleId());
        var order=store.order(guild,id); if(order==null)return "Order not found on this server.";
        if(!Objects.equals(order.postChannelId(),e.getChannel().getId())||!Objects.equals(order.postMessageId(),e.getMessageId()))return "This order post is no longer the active post.";
        if(order.status().equals(status))return "Order #"+id+" is already "+status+".";
        if(!OrderPolicy.validTransition(order.status(),status))return "Completed or cancelled orders cannot be reopened from the buttons.";
        var currentQueue=store.orders(guild,true);
        if(status.equals("PROCESSING")&&!OrderPolicy.mayProcess(cfg.queueMode(),currentQueue,id))return "Sequential mode allows one processing order at a time.";
        if(!store.transition(guild,id,order.status(),status,e.getUser().getId(),e.getId()))return "Order changed in another action; refresh and try again.";
        var latest=store.order(guild,id); var active=store.orders(guild,true); refreshOrder(e.getGuild(),latest,active); refreshActivePosts(e.getGuild(),active); return "Order #"+id+" updated to "+status+".";
    }
    private String transition(SlashCommandInteractionEvent e,TicketOrderStore.Order order,String status) throws Exception {
        if(!OrderPolicy.validTransition(order.status(),status))return "Completed or cancelled orders cannot be reopened.";
        if(!store.transition(order.guildId(),order.id(),order.status(),status,e.getUser().getId(),e.getId()))return "Order changed in another action; refresh and try again.";
        var latest=store.order(order.guildId(),order.id()); var active=store.orders(order.guildId(),true); refreshOrder(e.getGuild(),latest,active); refreshActivePosts(e.getGuild(),active); return "Order #"+order.id()+" cancelled.";
    }
    private void refreshOrder(net.dv8tion.jda.api.entities.Guild guild,TicketOrderStore.Order order,List<TicketOrderStore.Order> active) {
        if(order==null||order.postChannelId()==null||order.postMessageId()==null)return;
        TextChannel channel=guild.getTextChannelById(order.postChannelId()); if(channel==null)return;
        boolean terminal=!OrderPolicy.active(order.status());
        channel.editMessageEmbedsById(order.postMessageId(),orderEmbed(order,position(order,active))).setComponents(orderButtons(order.id(),terminal)).queue(null,
            failure->LoggerFactory.getLogger(TicketOrderBot.class).warn("Order post refresh failed ({})",failure.getClass().getSimpleName()));
    }
    private String repairOrderPost(SlashCommandInteractionEvent e,TicketOrderStore.Order order) throws Exception {
        TextChannel target=order.postChannelId()==null?null:e.getGuild().getTextChannelById(order.postChannelId());
        if(target!=null&&order.postMessageId()!=null) {
            try {
                target.retrieveMessageById(order.postMessageId()).complete();
                var active=store.orders(order.guildId(),true);
                target.editMessageEmbedsById(order.postMessageId(),orderEmbed(order,position(order,active)))
                    .setComponents(orderButtons(order.id(),!OrderPolicy.active(order.status()))).complete();
                return "Refreshed order #"+order.id()+" in <#"+target.getId()+">.";
            } catch(net.dv8tion.jda.api.exceptions.ErrorResponseException error) {
                if(error.getErrorCode()!=10008)return "Could not verify the existing order post; no replacement was created. Check channel access and try again.";
                // The stored message was deleted; create a replacement below.
            }
        }
        var config=store.config(order.guildId()); if(config.orderChannelId()==null)return "Configure an order channel with /order config before rebuilding the post.";
        target=e.getGuild().getTextChannelById(config.orderChannelId()); if(target==null)return "Configured order channel is missing; update it with /order config.";
        var active=store.orders(order.guildId(),true); var message=target.sendMessageEmbeds(orderEmbed(order,position(order,active)))
            .setComponents(orderButtons(order.id(),!OrderPolicy.active(order.status()))).setAllowedMentions(List.of()).complete();
        store.postOrder(order.guildId(),order.id(),target.getId(),message.getId()); return "Rebuilt the post for order #"+order.id()+" in <#"+target.getId()+">.";
    }
    private void refreshActivePosts(net.dv8tion.jda.api.entities.Guild guild,List<TicketOrderStore.Order> active) {
        for(var item:active) refreshOrder(guild,item,active);
    }
    static List<ActionRow> orderButtons(long id,boolean disabled) { return List.of(ActionRow.of(
        Button.secondary("tori:order:"+id+":NOTED","♡ noted").withDisabled(disabled),Button.primary("tori:order:"+id+":PROCESSING","💌 processing").withDisabled(disabled),Button.success("tori:order:"+id+":DONE","❕ done").withDisabled(disabled))); }
    private static net.dv8tion.jda.api.entities.MessageEmbed orderEmbed(TicketOrderStore.Order o,int position) {
        return new EmbedBuilder().setTitle("📦 ORDER #"+o.id()).addField("Customer","<@"+o.customerId()+">",true)
            .addField("Product",o.product(),true).addField("Assigned Staff",o.assignedStaffId()==null?"Unassigned":"<@"+o.assignedStaffId()+">",true)
            .addField("Status",o.status(),true).addField("Queue Position",position<1?"—":"#"+position,true)
            .addField("Created",DATE.format(o.createdAt()),true).addField("Description",o.description(),false)
            .addField("Ticket",o.ticketId()==null?"—":"#"+o.ticketId(),true).build();
    }
    private static int position(TicketOrderStore.Order target,List<TicketOrderStore.Order> active) {
        if(!OrderPolicy.active(target.status()))return 0;
        return OrderPolicy.position(target.id(),active);
    }
    private static String orderText(TicketOrderStore.Order o,List<TicketOrderStore.Order> active) {
        return "Order #"+o.id()+" · "+o.status()+" · queue "+(position(o,active)==0?"—":"#"+position(o,active))+"\nCustomer: <@"+o.customerId()+">\nProduct: "+o.product()+"\nAssigned: "+(o.assignedStaffId()==null?"unassigned":"<@"+o.assignedStaffId()+">")+"\n"+o.description()+(o.ticketId()==null?"":"\nTicket #"+o.ticketId());
    }
    private static String queueText(List<TicketOrderStore.Order> orders,String mode) {
        if(orders.isEmpty())return "ORDER QUEUE\nNo active orders.";
        var out=new StringBuilder("ORDER QUEUE · ").append(mode).append("\n"); int shown=0;
        for(int i=0;i<orders.size();i++){var o=orders.get(i);String line="#"+(i+1)+" Order "+o.id()+" — "+o.status()+" — "+o.product()+"\n";if(out.length()+line.length()>1750)break;out.append(line);shown++;}
        if(shown<orders.size())out.append("… ").append(orders.size()-shown).append(" more active orders; use `/order view` with an ID.");return out.toString();
    }
    private TicketOrderStore.Ticket currentTicket(SlashCommandInteractionEvent e) throws Exception {
        return store.ticketByChannel(e.getGuild().getId(),e.getChannel().getId());
    }
    static String ticketContextError(TicketOrderStore.Ticket ticket) {
        return ticket==null?TICKET_CHANNEL_REQUIRED:null;
    }
    private String setTicketStatus(SlashCommandInteractionEvent e,String next,String expected) throws Exception {
        var t=currentTicket(e); String contextError=ticketContextError(t); if(contextError!=null)return contextError;
        var cfg=store.config(t.guildId()); requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),t.creatorId());
        return setTicketStatus(e.getGuild(),e.getChannel().getId(),t,next,expected,e.getUser().getId());
    }
    private String setTicketStatus(ButtonInteractionEvent e,String next,String expected) throws Exception {
        var t=store.ticketByChannel(e.getGuild().getId(),e.getChannel().getId()); if(t==null)return "Ticket not found.";
        var cfg=store.config(t.guildId()); requireStaffOrOwner(e.getMember(),cfg.ticketStaffRoleId(),t.creatorId());
        return setTicketStatus(e.getGuild(),e.getChannel().getId(),t,next,expected,e.getUser().getId());
    }
    private String setTicketStatus(net.dv8tion.jda.api.entities.Guild guild,String channelId,TicketOrderStore.Ticket t,String next,String expected,String actor) throws Exception {
        if(!store.ticketStatus(t.guildId(),t.id(),expected,next,actor))return "Ticket is already in a different state.";
        var channel=guild.getTextChannelById(channelId);
        if(channel!=null) {
            if(next.equals("CLOSED")) {
                channel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.VIEW_CHANNEL).complete();
                for(String memberId:store.ticketMembers(t.guildId(),t.id())) { var ticketMember=guild.getMemberById(memberId); if(ticketMember!=null){var override=channel.getPermissionOverride(ticketMember);if(override!=null)override.delete().complete();} }
            } else {
                for(String memberId:store.ticketMembers(t.guildId(),t.id())) { var ticketMember=guild.getMemberById(memberId); if(ticketMember!=null)channel.upsertPermissionOverride(ticketMember).grant(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY).complete(); }
                var staff=ticketStaffRole(guild,t.guildId()); if(staff!=null)channel.upsertPermissionOverride(staff).grant(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY).complete();
            }
            channel.sendMessage("Ticket #"+t.id()+" "+next.toLowerCase(Locale.ROOT)+" by <@"+actor+">.").setAllowedMentions(List.of()).queue();
        }
        return "Ticket #"+t.id()+" "+next.toLowerCase(Locale.ROOT)+".";
    }
    private Role ticketStaffRole(net.dv8tion.jda.api.entities.Guild guild,String guildId) throws Exception { String role=store.config(guildId).ticketStaffRoleId(); return role==null?null:guild.getRoleById(role); }
    private String claimTicket(SlashCommandInteractionEvent e) throws Exception {
        var t=currentTicket(e); String contextError=ticketContextError(t); if(contextError!=null)return contextError;
        var cfg=store.config(t.guildId()); requireStaff(e.getMember(),cfg.ticketStaffRoleId());
        return store.claimTicket(t.guildId(),t.id(),e.getUser().getId())?"Ticket #"+t.id()+" claimed.":"Ticket is already claimed or closed.";
    }
    private String claimTicket(ButtonInteractionEvent e) throws Exception {
        var t=store.ticketByChannel(e.getGuild().getId(),e.getChannel().getId()); if(t==null)return "Ticket not found.";
        var cfg=store.config(t.guildId()); requireStaff(e.getMember(),cfg.ticketStaffRoleId());
        return store.claimTicket(t.guildId(),t.id(),e.getUser().getId())?"Ticket #"+t.id()+" claimed.":"Ticket is already claimed or closed.";
    }
    private String transcript(SlashCommandInteractionEvent e,TicketOrderStore.Ticket t) throws Exception {
        if(!(e.getChannel() instanceof TextChannel channel))return "Transcript requires a text ticket channel.";
        TranscriptSnapshot snapshot=buildTranscript(channel,t.id());
        e.getHook().sendFiles(FileUpload.fromData(snapshot.content().getBytes(StandardCharsets.UTF_8),"ticket-"+t.id()+"-transcript.txt")).setEphemeral(true).queue(); return "Transcript exported (up to the latest 100 messages).";
    }
    private record TranscriptSnapshot(String content,int messageCount) {}
    private static TranscriptSnapshot buildTranscript(TextChannel channel,long ticketId) {
        List<Message> messages=channel.getHistory().retrievePast(100).complete(); var out=new StringBuilder("Transcript for ticket #").append(ticketId).append(" (latest 100 messages)\n");
        Collections.reverse(messages);
        for(Message message:messages) {
            out.append('[').append(message.getTimeCreated()).append("] ").append(message.getAuthor().getName()).append(": ").append(message.getContentDisplay()).append('\n');
            message.getAttachments().forEach(file->out.append("  Attachment: ").append(file.getUrl()).append('\n'));
        }
        return new TranscriptSnapshot(out.toString(),messages.size());
    }
    private static void requireAdmin(Member member) { if(member==null||!member.hasPermission(Permission.MANAGE_SERVER))throw new IllegalArgumentException("Server management permission required."); }
    private static void requireOrderStaff(Member member,String roleId) {
        Set<String> roleIds=member==null?Set.of():member.getRoles().stream().map(Role::getId).collect(java.util.stream.Collectors.toSet());
        if(!OrderPolicy.mayManageOrders(member!=null&&member.hasPermission(Permission.MANAGE_SERVER),
            member!=null&&member.hasPermission(Permission.ADMINISTRATOR),roleId,roleIds))
            throw new IllegalArgumentException(ORDER_PERMISSION_REQUIRED);
    }
    static String actionErrorMessage(Exception failure) {
        if(failure instanceof IllegalArgumentException&&failure.getMessage()!=null&&!failure.getMessage().isBlank()) return failure.getMessage();
        return ACTION_FAILED;
    }
    private static void requireStaff(Member member,String roleId) { if(member==null||(!member.hasPermission(Permission.ADMINISTRATOR)&& (roleId==null||member.getRoles().stream().noneMatch(r->r.getId().equals(roleId)))))throw new IllegalArgumentException("You need the configured staff role or administrator permission."); }
    private static void requireStaffOrOwner(Member member,String roleId,String creator) { if(member==null||(!member.hasPermission(Permission.ADMINISTRATOR)&&!member.getId().equals(creator)&&(roleId==null||member.getRoles().stream().noneMatch(r->r.getId().equals(roleId)))))throw new IllegalArgumentException("You do not have access to manage this ticket."); }
}
