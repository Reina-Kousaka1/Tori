-- Consumables are distinct from owned inventory and their active effects.
-- No effect or balance modifier is activated by this migration.
CREATE TABLE economy_v2_consumable_effects (
    item_id TEXT PRIMARY KEY REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT,
    effect_code TEXT NOT NULL CHECK (effect_code ~ '^[a-z_]{1,48}$'),
    duration_ms BIGINT NOT NULL CHECK (duration_ms BETWEEN 1000 AND 604800000),
    active BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE economy_v2_active_effects (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    effect_code TEXT NOT NULL CHECK (effect_code ~ '^[a-z_]{1,48}$'),
    source_item_id TEXT NOT NULL REFERENCES economy_v2_catalog_items(item_id) ON DELETE RESTRICT,
    activated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    request_key TEXT NOT NULL REFERENCES economy_v2_requests(idempotency_key) ON DELETE RESTRICT,
    PRIMARY KEY (user_id,effect_code),
    CHECK (expires_at > activated_at)
);
CREATE INDEX economy_v2_active_effects_expiry_idx ON economy_v2_active_effects(expires_at);
CREATE TABLE economy_v2_activity_effect_rules (
    effect_code TEXT NOT NULL CHECK (effect_code ~ '^[a-z_]{1,48}$'),
    activity TEXT NOT NULL CHECK (activity IN ('fish','mine','chop')),
    credit_bonus_percent INTEGER NOT NULL CHECK (credit_bonus_percent BETWEEN 1 AND 100),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (effect_code,activity)
);
