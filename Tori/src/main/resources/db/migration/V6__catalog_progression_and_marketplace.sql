-- Additive Elixir domain structures. Existing wallet, inventory, market, orders and tickets remain authoritative.
-- Applying this Flyway migration does not enable any Elixir write route.
ALTER TABLE economy_v2_catalog_items
    ADD COLUMN subcategory TEXT NOT NULL DEFAULT 'general',
    ADD COLUMN tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN rarity TEXT NOT NULL DEFAULT 'common',
    ADD COLUMN currency TEXT NOT NULL DEFAULT 'credits',
    ADD COLUMN stackable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN max_stack BIGINT,
    ADD COLUMN consumable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN tradeable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN equip_slots TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN conflict_slots TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN level_requirement INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN season TEXT,
    ADD COLUMN rotation_weight INTEGER NOT NULL DEFAULT 100,
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::JSONB;
ALTER TABLE economy_v2_catalog_items
    ADD CONSTRAINT catalog_max_stack_positive CHECK (max_stack IS NULL OR max_stack > 0),
    ADD CONSTRAINT catalog_level_positive CHECK (level_requirement > 0),
    ADD CONSTRAINT catalog_rotation_weight_positive CHECK (rotation_weight > 0),
    ADD CONSTRAINT catalog_rarity_valid CHECK (rarity IN ('common','uncommon','rare','special')),
    ADD CONSTRAINT catalog_currency_valid CHECK (currency = 'credits');

CREATE TABLE economy_v2_shop_rotations (
    period_key BIGINT PRIMARY KEY,
    theme TEXT NOT NULL,
    season TEXT NOT NULL,
    seed BIGINT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
CREATE TABLE economy_v2_shop_rotation_items (
    period_key BIGINT NOT NULL REFERENCES economy_v2_shop_rotations(period_key) ON DELETE RESTRICT,
    item_id TEXT NOT NULL REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT,
    position INTEGER NOT NULL CHECK (position >= 0),
    unit_price BIGINT NOT NULL CHECK (unit_price > 0),
    stock_limit BIGINT CHECK (stock_limit IS NULL OR stock_limit >= 0),
    sold BIGINT NOT NULL DEFAULT 0 CHECK (sold >= 0),
    PRIMARY KEY (period_key, item_id), UNIQUE (period_key, position),
    CHECK (stock_limit IS NULL OR sold <= stock_limit)
);
CREATE INDEX economy_v2_shop_rotation_items_position_idx ON economy_v2_shop_rotation_items(period_key, position);

-- New fashion slots only; legacy rod/pickaxe/axe/wrench remain in economy_equipment.
-- All ownership quantities continue to live in economy_inventory.
CREATE TABLE economy_v2_loadout (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    slot TEXT NOT NULL CHECK (slot IN ('top','bottom','dress','outerwear','shoes','bag','accessory','jewelry','hair_accessory')),
    item_id TEXT NOT NULL REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, slot),
    UNIQUE (user_id, item_id)
);

CREATE TABLE economy_v2_xp_thresholds (
    level INTEGER PRIMARY KEY CHECK (level >= 1),
    required_xp BIGINT NOT NULL UNIQUE CHECK (required_xp >= 0)
);
CREATE TABLE economy_v2_careers (
    career_code TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO economy_v2_careers(career_code,display_name) VALUES
    ('ballet','Ballet'),('volleyball','Volleyball'),('cheer','Cheerleading')
ON CONFLICT DO NOTHING;
CREATE TABLE economy_v2_xp_sources (
    source_code TEXT PRIMARY KEY,
    reward_xp BIGINT NOT NULL CHECK (reward_xp > 0),
    cooldown_ms BIGINT NOT NULL DEFAULT 0 CHECK (cooldown_ms >= 0),
    career_code TEXT REFERENCES economy_v2_careers(career_code) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE economy_v2_account_progress (
    user_id TEXT PRIMARY KEY REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    xp BIGINT NOT NULL DEFAULT 0 CHECK (xp >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE economy_v2_career_progress (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    career_code TEXT NOT NULL REFERENCES economy_v2_careers(career_code) ON DELETE RESTRICT,
    xp BIGINT NOT NULL DEFAULT 0 CHECK (xp >= 0),
    PRIMARY KEY (user_id, career_code)
);
CREATE TABLE economy_v2_xp_events (
    request_key TEXT PRIMARY KEY REFERENCES economy_v2_requests(idempotency_key) ON DELETE RESTRICT,
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    source_code TEXT NOT NULL REFERENCES economy_v2_xp_sources(source_code) ON DELETE RESTRICT,
    xp_delta BIGINT NOT NULL CHECK (xp_delta > 0),
    xp_after BIGINT NOT NULL CHECK (xp_after >= xp_delta),
    career_code TEXT REFERENCES economy_v2_careers(career_code) ON DELETE RESTRICT,
    career_xp_after BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX economy_v2_xp_events_cooldown_idx ON economy_v2_xp_events(user_id, source_code, occurred_at DESC);

-- Listings escrow quantity by removing it from economy_inventory when listed.
CREATE TABLE economy_v2_marketplace_listings (
    listing_id TEXT PRIMARY KEY,
    seller_user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    buyer_user_id TEXT REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    item_id TEXT NOT NULL REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    ask_price BIGINT NOT NULL CHECK (ask_price > 0),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','SOLD','CANCELLED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    CHECK ((status = 'ACTIVE' AND buyer_user_id IS NULL AND closed_at IS NULL) OR status <> 'ACTIVE')
);
CREATE INDEX economy_v2_marketplace_active_idx ON economy_v2_marketplace_listings(status, expires_at);
