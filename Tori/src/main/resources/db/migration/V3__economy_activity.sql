-- Additive cooldown fields for existing quick-credit activities.
ALTER TABLE economy_accounts
    ADD COLUMN last_beg_at BIGINT NOT NULL DEFAULT 0 CHECK (last_beg_at >= 0),
    ADD COLUMN last_work_at BIGINT NOT NULL DEFAULT 0 CHECK (last_work_at >= 0);
