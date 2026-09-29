-- DRAFT ONLY: intentionally outside src/main/resources/db/migration.
-- Apply only to an isolated test DB until production backup/restore and cutover gates pass.
-- Existing V1-V4 tables and data are not altered by this migration.
CREATE TABLE economy_v2_requests (
    idempotency_key TEXT PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    operation TEXT NOT NULL,
    actor_user_id TEXT NOT NULL CHECK (actor_user_id ~ '^[0-9]{1,32}$'),
    guild_id TEXT NOT NULL CHECK (guild_id ~ '^[0-9]{1,32}$'),
    result_json JSONB NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE economy_v2_ledger_entries (
    entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    request_key TEXT REFERENCES economy_v2_requests(idempotency_key) ON DELETE RESTRICT,
    leg TEXT NOT NULL,
    delta BIGINT NOT NULL,
    balance_after BIGINT NOT NULL CHECK (balance_after >= 0),
    reason_code TEXT NOT NULL CHECK (reason_code ~ '^[A-Z_]{1,48}$'),
    guild_id TEXT CHECK (guild_id IS NULL OR guild_id ~ '^[0-9]{1,32}$'),
    counterparty_user_id TEXT CHECK (counterparty_user_id IS NULL OR counterparty_user_id ~ '^[0-9]{1,32}$'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_key, leg)
);
CREATE INDEX economy_v2_ledger_user_time_idx
    ON economy_v2_ledger_entries(user_id, occurred_at DESC, entry_id DESC);
CREATE UNIQUE INDEX economy_v2_ledger_opening_idx
    ON economy_v2_ledger_entries(user_id) WHERE reason_code = 'OPENING_BALANCE';

CREATE TABLE economy_v2_catalog_items (
    item_id TEXT PRIMARY KEY CHECK (item_id ~ '^[a-z0-9_-]{1,64}$'),
    name TEXT NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 1000),
    category TEXT NOT NULL CHECK (category ~ '^[a-z0-9_-]{1,64}$'),
    buy_price BIGINT CHECK (buy_price IS NULL OR buy_price BETWEEN 1 AND 1000000000),
    sell_price BIGINT CHECK (sell_price IS NULL OR sell_price BETWEEN 1 AND 1000000000),
    tool_slot TEXT CHECK (tool_slot IS NULL OR tool_slot IN ('rod', 'pickaxe', 'axe', 'wrench')),
    durability INTEGER CHECK (durability IS NULL OR durability > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    catalog_version BIGINT NOT NULL DEFAULT 1 CHECK (catalog_version > 0)
);
CREATE INDEX economy_v2_catalog_category_idx ON economy_v2_catalog_items(category, item_id) WHERE active;

CREATE TABLE economy_v2_inventory_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    item_id TEXT NOT NULL,
    request_key TEXT REFERENCES economy_v2_requests(idempotency_key) ON DELETE RESTRICT,
    leg TEXT NOT NULL,
    delta BIGINT NOT NULL,
    quantity_after BIGINT NOT NULL CHECK (quantity_after >= 0),
    reason_code TEXT NOT NULL CHECK (reason_code ~ '^[A-Z_]{1,48}$'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_key, leg)
);
CREATE INDEX economy_v2_inventory_user_item_idx ON economy_v2_inventory_events(user_id, item_id, event_id DESC);
CREATE UNIQUE INDEX economy_v2_inventory_opening_idx
    ON economy_v2_inventory_events(user_id, item_id) WHERE reason_code = 'OPENING_INVENTORY';

CREATE TABLE economy_v2_tool_wear (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    item_id TEXT NOT NULL CHECK (item_id ~ '^[a-z0-9_-]{1,64}$'),
    used INTEGER NOT NULL CHECK (used >= 0),
    PRIMARY KEY (user_id, item_id)
);
CREATE TABLE economy_v2_equipment (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    slot TEXT NOT NULL CHECK (slot IN ('rod', 'pickaxe', 'axe', 'wrench')),
    item_id TEXT NOT NULL CHECK (item_id ~ '^[a-z0-9_-]{1,64}$'),
    PRIMARY KEY (user_id, slot)
);

CREATE TABLE economy_v2_activity_state (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    activity TEXT NOT NULL CHECK (activity ~ '^[a-z_]{1,48}$'),
    last_at_ms BIGINT NOT NULL DEFAULT 0 CHECK (last_at_ms >= 0),
    PRIMARY KEY (user_id, activity)
);
CREATE TABLE economy_v2_activity_events (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    request_key TEXT NOT NULL UNIQUE REFERENCES economy_v2_requests(idempotency_key) ON DELETE RESTRICT,
    activity TEXT NOT NULL,
    reward_credits BIGINT NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
