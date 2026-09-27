-- Add optional order metadata only; existing orders remain method-unspecified and standard priority.
ALTER TABLE guild_orders
    ADD COLUMN payment_method TEXT NOT NULL DEFAULT '' CHECK (char_length(payment_method) <= 60),
    ADD COLUMN fastpass BOOLEAN NOT NULL DEFAULT FALSE;
