-- Compare the single JSON line from this query on source and restored DB.
-- No credentials or row-level personal data are returned.
SELECT jsonb_build_object(
  'schema', (SELECT md5(string_agg(table_name || ':' || column_name || ':' || data_type || ':' || is_nullable,
                                  '|' ORDER BY table_name, ordinal_position))
             FROM information_schema.columns WHERE table_schema='public'),
  'flyway', (SELECT md5(coalesce(string_agg(installed_rank || ':' || version || ':' || script || ':' ||
                                      coalesce(checksum::text, '') || ':' || success, '|'
                                      ORDER BY installed_rank), '')) FROM flyway_schema_history),
  'wallets', (SELECT count(*) FROM economy_accounts),
  'balances', (SELECT coalesce(sum(balance), 0) FROM economy_accounts),
  'daily_cooldowns', (SELECT count(*) FILTER (WHERE last_daily_at > 0) FROM economy_accounts),
  'inventory_rows', (SELECT count(*) FROM economy_inventory),
  'item_quantities', (SELECT coalesce(sum(quantity), 0) FROM economy_inventory),
  'tool_wear', (SELECT count(*) FROM economy_tool_wear),
  'equipment', (SELECT count(*) FROM economy_equipment),
  'market_products', (SELECT count(*) FROM economy_market_products),
  'market_transactions', (SELECT count(*) FROM economy_market_transactions),
  'tickets', (SELECT count(*) FROM guild_tickets),
  'ticket_events', (SELECT count(*) FROM guild_ticket_events),
  'orders', (SELECT count(*) FROM guild_orders),
  'order_events', (SELECT count(*) FROM guild_order_status_events),
  'moderation_cases', (SELECT count(*) FROM moderation_cases),
  'bot_events', (SELECT count(*) FROM bot_events),
  'mongo_import_archive', (SELECT count(*) FROM mongo_import_archive)
)::text;
