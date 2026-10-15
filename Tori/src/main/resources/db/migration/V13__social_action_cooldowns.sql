-- Social interaction cooldown state. Existing relationship and account rows are untouched.
-- Idempotent interaction results continue to use economy_v2_requests.
CREATE TABLE economy_v2_social_action_cooldowns (
    actor_user_id TEXT NOT NULL CHECK (actor_user_id ~ '^[0-9]{1,32}$' AND btrim(actor_user_id, '0') <> ''),
    target_user_id TEXT NOT NULL CHECK (target_user_id ~ '^[0-9]{1,32}$' AND btrim(target_user_id, '0') <> ''),
    action_code TEXT NOT NULL CHECK (action_code IN (
        'hug', 'kiss', 'pat', 'cuddle', 'poke', 'slap', 'bite', 'tickle', 'highfive', 'boop', 'wave'
    )),
    last_action_at_ms BIGINT NOT NULL CHECK (last_action_at_ms >= 0),
    PRIMARY KEY (actor_user_id, target_user_id, action_code)
);
