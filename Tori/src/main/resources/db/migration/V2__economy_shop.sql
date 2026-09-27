-- Additive schema only. Existing bot, ticket, order, and Mongo archive tables are untouched.
CREATE TABLE economy_accounts (
    user_id TEXT PRIMARY KEY CHECK (user_id ~ '^[0-9]{1,32}$'),
    balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),
    last_daily_at BIGINT NOT NULL DEFAULT 0 CHECK (last_daily_at >= 0),
    last_fish_at BIGINT NOT NULL DEFAULT 0 CHECK (last_fish_at >= 0),
    last_mine_at BIGINT NOT NULL DEFAULT 0 CHECK (last_mine_at >= 0),
    last_chop_at BIGINT NOT NULL DEFAULT 0 CHECK (last_chop_at >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    schema_version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE economy_inventory (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    item_id TEXT NOT NULL CHECK (item_id ~ '^[a-z0-9_-]{1,64}$'),
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    schema_version INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, item_id)
);

CREATE TABLE economy_tool_wear (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    item_id TEXT NOT NULL CHECK (item_id ~ '^[a-z0-9_-]{1,64}$'),
    used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
    schema_version INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, item_id)
);

CREATE TABLE economy_equipment (
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    slot TEXT NOT NULL CHECK (slot IN ('rod', 'pickaxe', 'axe', 'wrench')),
    item_id TEXT NOT NULL CHECK (item_id ~ '^[a-z0-9_-]{1,64}$'),
    schema_version INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, slot)
);

CREATE INDEX economy_accounts_balance_idx ON economy_accounts(balance DESC, user_id);

CREATE TABLE economy_market_products (
    product_id TEXT PRIMARY KEY CHECK (product_id ~ '^[a-z0-9_-]{1,64}$'),
    name TEXT NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 1000),
    category TEXT NOT NULL CHECK (category ~ '^[a-z0-9_-]{1,64}$'),
    current_price BIGINT NOT NULL CHECK (current_price BETWEEN 1 AND 1000000000),
    base_price BIGINT NOT NULL CHECK (base_price BETWEEN 1 AND 1000000000),
    minimum_price BIGINT NOT NULL CHECK (minimum_price BETWEEN 1 AND current_price),
    maximum_price BIGINT NOT NULL CHECK (maximum_price BETWEEN current_price AND 1000000000),
    volatility DOUBLE PRECISION NOT NULL CHECK (volatility BETWEEN 0 AND 0.25),
    stock BIGINT NOT NULL DEFAULT -1 CHECK (stock >= -1),
    available BOOLEAN NOT NULL DEFAULT TRUE,
    rarity TEXT NOT NULL DEFAULT 'common',
    tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_price_at TIMESTAMPTZ NOT NULL,
    CHECK (minimum_price <= base_price AND base_price <= maximum_price)
);
CREATE INDEX economy_market_category_idx ON economy_market_products(category, product_id);
CREATE INDEX economy_market_due_idx ON economy_market_products(next_price_at) WHERE available;

CREATE TABLE economy_market_price_history (
    history_id BIGSERIAL PRIMARY KEY,
    product_id TEXT NOT NULL REFERENCES economy_market_products(product_id) ON DELETE RESTRICT,
    price BIGINT NOT NULL CHECK (price >= 1),
    changed_at TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('MARKET_MOVEMENT', 'ADMIN_CHANGE'))
);
CREATE INDEX economy_market_price_history_idx ON economy_market_price_history(product_id, history_id DESC);

CREATE TABLE economy_market_sales (
    sale_id TEXT PRIMARY KEY,
    product_id TEXT REFERENCES economy_market_products(product_id) ON DELETE RESTRICT,
    category TEXT,
    discount_percent INTEGER NOT NULL CHECK (discount_percent BETWEEN 5 AND 30),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED')),
    active_slot TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (ends_at > starts_at),
    CHECK ((product_id IS NOT NULL) <> (category IS NOT NULL)),
    CHECK ((status = 'ACTIVE' AND active_slot = 'global') OR (status = 'EXPIRED' AND active_slot IS NULL))
);
CREATE UNIQUE INDEX economy_market_one_active_sale_idx ON economy_market_sales(active_slot) WHERE active_slot IS NOT NULL;
CREATE INDEX economy_market_sales_active_idx ON economy_market_sales(status, starts_at, ends_at);

CREATE TABLE economy_market_quotes (
    guild_id TEXT NOT NULL CHECK (guild_id ~ '^[0-9]{1,32}$'),
    user_id TEXT NOT NULL CHECK (user_id ~ '^[0-9]{1,32}$'),
    product_id TEXT NOT NULL REFERENCES economy_market_products(product_id) ON DELETE RESTRICT,
    quoted_price BIGINT NOT NULL CHECK (quoted_price >= 1),
    quoted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (guild_id, user_id, product_id),
    CHECK (expires_at > quoted_at)
);
CREATE INDEX economy_market_quotes_expiry_idx ON economy_market_quotes(expires_at);

CREATE TABLE economy_market_transactions (
    transaction_id TEXT PRIMARY KEY,
    interaction_id TEXT NOT NULL UNIQUE,
    guild_id TEXT NOT NULL CHECK (guild_id ~ '^[0-9]{1,32}$'),
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    product_id TEXT NOT NULL REFERENCES economy_market_products(product_id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    unit_price BIGINT NOT NULL CHECK (unit_price >= 1),
    amount BIGINT NOT NULL CHECK (amount < 0),
    balance_before BIGINT NOT NULL CHECK (balance_before >= 0),
    balance_after BIGINT NOT NULL CHECK (balance_after >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    sale_id TEXT REFERENCES economy_market_sales(sale_id) ON DELETE RESTRICT
);
CREATE INDEX economy_market_transactions_guild_time_idx ON economy_market_transactions(guild_id, occurred_at DESC);

CREATE TABLE economy_market_inventory (
    acquisition_id BIGSERIAL PRIMARY KEY,
    guild_id TEXT NOT NULL CHECK (guild_id ~ '^[0-9]{1,32}$'),
    user_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
    product_id TEXT NOT NULL REFERENCES economy_market_products(product_id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    acquired_at TIMESTAMPTZ NOT NULL,
    transaction_id TEXT NOT NULL UNIQUE REFERENCES economy_market_transactions(transaction_id) ON DELETE RESTRICT
);
CREATE INDEX economy_market_inventory_owner_idx ON economy_market_inventory(guild_id, user_id, product_id);
