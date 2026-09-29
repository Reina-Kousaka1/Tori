-- TEST DATABASE ONLY. Apply V1-V4 and draft V5 first. Never put this in automatic Flyway.
-- Run with psql -v ON_ERROR_STOP=1 -X -f backfill_v1_to_v2.sql.
-- This is a snapshot, not a writer cutover. Stop all legacy writers before any future production use.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE economy_accounts, economy_inventory, economy_tool_wear, economy_equipment IN SHARE MODE;

CREATE TEMP TABLE backfill_wallet_snapshot ON COMMIT DROP AS
SELECT user_id, balance, last_daily_at, last_beg_at, last_work_at,
       last_fish_at, last_mine_at, last_chop_at
FROM economy_accounts;
CREATE TEMP TABLE backfill_inventory_snapshot ON COMMIT DROP AS
SELECT user_id, item_id, quantity FROM economy_inventory;
CREATE TEMP TABLE backfill_wear_snapshot ON COMMIT DROP AS
SELECT user_id, item_id, used FROM economy_tool_wear;
CREATE TEMP TABLE backfill_equipment_snapshot ON COMMIT DROP AS
SELECT user_id, slot, item_id FROM economy_equipment;

INSERT INTO economy_v2_ledger_entries
    (user_id, leg, delta, balance_after, reason_code, occurred_at)
SELECT user_id, 'opening', balance, balance, 'OPENING_BALANCE', now()
FROM backfill_wallet_snapshot
ON CONFLICT DO NOTHING;

INSERT INTO economy_v2_inventory_events
    (user_id, item_id, leg, delta, quantity_after, reason_code, occurred_at)
SELECT user_id, item_id, 'opening', quantity, quantity, 'OPENING_INVENTORY', now()
FROM backfill_inventory_snapshot
ON CONFLICT DO NOTHING;

INSERT INTO economy_v2_activity_state (user_id, activity, last_at_ms)
SELECT user_id, activity, last_at_ms
FROM backfill_wallet_snapshot
CROSS JOIN LATERAL (VALUES
    ('daily', last_daily_at), ('beg', last_beg_at), ('work', last_work_at),
    ('fish', last_fish_at), ('mine', last_mine_at), ('chop', last_chop_at)
) AS cooldown(activity, last_at_ms)
ON CONFLICT DO NOTHING;

INSERT INTO economy_v2_tool_wear (user_id, item_id, used)
SELECT user_id, item_id, used FROM backfill_wear_snapshot
ON CONFLICT DO NOTHING;
INSERT INTO economy_v2_equipment (user_id, slot, item_id)
SELECT user_id, slot, item_id FROM backfill_equipment_snapshot
ON CONFLICT DO NOTHING;

-- Exact row/value comparisons catch an incompatible partial backfill, duplicate state,
-- missed Snowflake, stale quantity, or source writer racing with the snapshot.
DO $$
BEGIN
    IF (SELECT count(*) FROM economy_accounts) <> (SELECT count(*) FROM backfill_wallet_snapshot)
       OR (SELECT coalesce(sum(balance), 0) FROM economy_accounts) <>
          (SELECT coalesce(sum(balance), 0) FROM backfill_wallet_snapshot)
       OR EXISTS (
          SELECT user_id, balance, last_daily_at, last_beg_at, last_work_at,
                 last_fish_at, last_mine_at, last_chop_at FROM economy_accounts
          EXCEPT SELECT * FROM backfill_wallet_snapshot
       ) THEN RAISE EXCEPTION 'Legacy wallet or cooldown changed during backfill'; END IF;
    IF (SELECT count(*) FROM economy_inventory) <> (SELECT count(*) FROM backfill_inventory_snapshot)
       OR (SELECT coalesce(sum(quantity), 0) FROM economy_inventory) <>
          (SELECT coalesce(sum(quantity), 0) FROM backfill_inventory_snapshot)
       OR EXISTS (
          SELECT user_id, item_id, quantity FROM economy_inventory
          EXCEPT SELECT * FROM backfill_inventory_snapshot
       ) THEN RAISE EXCEPTION 'Legacy inventory changed during backfill'; END IF;
    IF (SELECT count(*) FROM economy_v2_ledger_entries WHERE reason_code='OPENING_BALANCE') <>
       (SELECT count(*) FROM backfill_wallet_snapshot)
       OR (SELECT coalesce(sum(balance_after), 0) FROM economy_v2_ledger_entries WHERE reason_code='OPENING_BALANCE') <>
          (SELECT coalesce(sum(balance), 0) FROM backfill_wallet_snapshot)
       OR EXISTS (
           SELECT user_id, balance FROM backfill_wallet_snapshot
           EXCEPT SELECT user_id, balance_after FROM economy_v2_ledger_entries WHERE reason_code='OPENING_BALANCE'
       ) THEN RAISE EXCEPTION 'V2 opening wallet mismatch'; END IF;
    IF (SELECT count(*) FROM economy_v2_inventory_events WHERE reason_code='OPENING_INVENTORY') <>
       (SELECT count(*) FROM backfill_inventory_snapshot)
       OR (SELECT coalesce(sum(quantity_after), 0) FROM economy_v2_inventory_events WHERE reason_code='OPENING_INVENTORY') <>
          (SELECT coalesce(sum(quantity), 0) FROM backfill_inventory_snapshot)
       OR EXISTS (
           SELECT user_id, item_id, quantity FROM backfill_inventory_snapshot
           EXCEPT SELECT user_id, item_id, quantity_after FROM economy_v2_inventory_events WHERE reason_code='OPENING_INVENTORY'
       ) THEN RAISE EXCEPTION 'V2 opening inventory mismatch'; END IF;
    IF (SELECT count(*) FROM economy_v2_activity_state) <> (SELECT count(*) * 6 FROM backfill_wallet_snapshot)
       OR EXISTS (
           SELECT user_id, activity, last_at_ms FROM backfill_wallet_snapshot
           CROSS JOIN LATERAL (VALUES
               ('daily', last_daily_at), ('beg', last_beg_at), ('work', last_work_at),
               ('fish', last_fish_at), ('mine', last_mine_at), ('chop', last_chop_at)
           ) AS cooldown(activity, last_at_ms)
           EXCEPT SELECT user_id, activity, last_at_ms FROM economy_v2_activity_state
       ) THEN RAISE EXCEPTION 'V2 cooldown mismatch'; END IF;
    IF (SELECT count(*) FROM economy_v2_tool_wear) <> (SELECT count(*) FROM backfill_wear_snapshot)
       OR EXISTS (SELECT * FROM backfill_wear_snapshot EXCEPT SELECT * FROM economy_v2_tool_wear)
       OR (SELECT count(*) FROM economy_v2_equipment) <> (SELECT count(*) FROM backfill_equipment_snapshot)
       OR EXISTS (SELECT * FROM backfill_equipment_snapshot EXCEPT SELECT * FROM economy_v2_equipment)
       THEN RAISE EXCEPTION 'V2 equipment or tool wear mismatch'; END IF;
END $$;

SELECT (SELECT count(*) FROM backfill_wallet_snapshot) AS wallet_count,
       (SELECT coalesce(sum(balance), 0) FROM backfill_wallet_snapshot) AS balance_sum,
       (SELECT count(*) FROM backfill_inventory_snapshot) AS inventory_count,
       (SELECT coalesce(sum(quantity), 0) FROM backfill_inventory_snapshot) AS item_quantity_sum,
       (SELECT count(*) FROM backfill_wear_snapshot) AS wear_count,
       (SELECT count(*) FROM backfill_equipment_snapshot) AS equipment_count;
COMMIT;
