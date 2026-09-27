# Tickets and real order queue

Tickets and real customer orders use Main Tori's PostgreSQL database and existing JDA listeners. They have no integration with economy, wallet, virtual inventory, or shop code.

## Server setup

1. Back up the PostgreSQL database and deploy the new Main Tori build. Flyway applies additive schema migrations at startup.
2. Authorize the bot with `applications.commands` and the guild permissions below.
3. Configure a support role with `/ticket config staff_role:@Support` and optionally a parent category with `/ticket config category:#Tickets`.
4. Configure order staff and a text post channel with `/order config staff_role:@OrderStaff channel:#orders`.
5. Optionally set `/order config queue_mode:Sequential` (default) or `Parallel`.
6. Post a panel with `/ticket panel channel:#support`.

Server managers can customize ticket copy with `/ticket config panel_title:"..." panel_text:"..." button_text:"..." welcome_text:"..." claim_button:"..." close_button:"..." delete_button:"..."`. All text options are optional. `/ticket config` without options shows the effective text; `/ticket reset_text` restores defaults. In the welcome text `{ticket}`, `{category}`, and `{user}` expand to the ticket number, category, and creator. Text is stored per guild in PostgreSQL. Existing tickets and panels keep their posted messages; run `/ticket panel` again to post an updated panel. Custom text never enables Discord mention notifications.

Slash command registration is part of normal startup. The separate `registerCommands` Gradle task uses Discord's API and is not needed when restarting normally.

## Commands and controls

- `/ticket open [category]` supports Support, Reports, Orders, and General. The panel button opens a Support ticket. A user already holding an open ticket in the same category is sent back to it.
- New ticket channels use a persistent per-guild ticket number (`ticket-001`, `ticket-002`, …). Existing ticket channels keep their names. A failed channel creation may leave a gap.
- `/ticket claim`, `/ticket close`, `/ticket reopen`, `/ticket transcript`, `/ticket add`, `/ticket remove`, and `/ticket link_order` act on the ticket channel where invoked. The initial ticket post also has Claim, Close, and Delete buttons.
- Delete is staff/admin only, requires a closed ticket, and asks for confirmation. Before deleting the Discord channel, Tori stores the latest 100 messages (including attachment URLs) in `guild_ticket_transcripts`. The ticket row and event history remain with status `DELETED`; no linked order is deleted. Retrieve the archived file with `/ticket transcript id:<ticket ID>` (staff or ticket creator only).
- `/order create customer product description [assigned_staff] [payment_method] [fastpass]` saves an order and posts it in the configured order channel. `payment_method` is an optional free-form label such as PayPal, Cash App, or Robux. It records the method only: Tori does not collect money, verify payment, or store an amount. `fastpass:true` moves the order ahead of waiting standard orders; it never interrupts an order already marked `PROCESSING` and adds no fee. A ticket is optional. Associate an existing order later with `/ticket link_order order_id:<ID>` inside a ticket.
- `/order view`, `/order queue`, `/order assign`, `/order edit`, `/order cancel`, `/order history`, and `/order config` manage or inspect orders. Queue positions put currently-processing work first, then fastpass orders, then regular waiting orders, with creation time as the tie-breaker. In sequential mode, staff cannot start a regular order while a fastpass is waiting.
- Each order post has exactly three visible buttons: `♡ noted`, `💌 processing`, and `❕ done`. A status action edits that same Discord message. Queue positions are recalculated from active records and active posts are refreshed after status changes.
- `CANCELLED` is set by `/order cancel`; completed and cancelled orders are terminal. Ticket close/reopen never changes order status.

Order and ticket IDs are monotonically allocated per server and record type. Lookups include the invoking guild ID. Staff permissions are checked on every command and button action; permission failures are private. Server administrators are included in staff checks.

## Discord permissions

The bot needs **View Channels**, **Send Messages**, **Read Message History**, **Manage Channels**, and **Manage Roles** (to create and maintain channel permission overwrites). Transcript export also needs **Attach Files** in the ticket channel and permission to send the ephemeral file response. Staff roles need view/send/history in ticket and order channels. Order channels should be restricted to the intended staff by server configuration; customer mentions do not send notification pings.

New ticket channels explicitly deny `@everyone`, then grant access to the creator, configured support role, and bot. Administrators retain Discord's normal administrator bypass. Closing removes explicit member access; reopening restores ticket members and the current support role.

## PostgreSQL persistence

The schema is maintained by Flyway in `src/main/resources/db/migration`. It includes guild-scoped config and ID counters, orders, status and assignment event history, tickets, members, ticket events, and transcripts. Order payment-method labels and fastpass flags are optional metadata added by an additive migration; previous orders default to no method selected and no fastpass. Discord snowflakes are stored as text to preserve their full unsigned decimal representation; internal ticket/order IDs use PostgreSQL `BIGINT`. Foreign keys enforce guild-scoped links. Database transactions protect coupled writes such as order status plus history, ticket/order linking, and counter allocation. The database interaction key makes repeated component interactions idempotent.

Order status history is append-only. The queue is calculated from active rows. Queue operations are serialized per guild within one bot process; do not run multiple bot replicas for the same guild without a distributed coordination design. Discord message edits are external side effects and cannot be part of the SQL transaction; if a Discord edit fails, the database remains authoritative and the post may need repair.

Back up PostgreSQL before deployment or maintenance. See [the Mongo-to-PostgreSQL migration guide](../migration/mongodb/README.md) for the one-time source copy. Tests do not connect to production databases or Discord.

## Verification

Run `./gradlew test` (Windows: `./gradlew.bat test`). Automated tests cover command definitions/localization, legal order status changes, sequential versus parallel processing, queue renumbering, ID precision, and ticket text/number behavior. A real Discord test server is still required to verify channel overwrites, component routing, transcript upload, and message edits.
