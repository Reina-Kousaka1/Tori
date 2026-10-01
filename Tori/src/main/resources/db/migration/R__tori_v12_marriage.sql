-- Repeatable additive V12 relationship schema. No existing account, wallet or profile data changes.
CREATE TABLE IF NOT EXISTS economy_v2_marriages (
  relationship_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  proposer_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
  recipient_id TEXT NOT NULL REFERENCES economy_accounts(user_id) ON DELETE RESTRICT,
  status TEXT NOT NULL CHECK (status IN ('PENDING','MARRIED','DIVORCED','CANCELLED')),
  proposed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  responded_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  CHECK (proposer_id <> recipient_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS economy_v2_marriages_active_pair_idx
  ON economy_v2_marriages ((LEAST(proposer_id,recipient_id)),(GREATEST(proposer_id,recipient_id)))
  WHERE status IN ('PENDING','MARRIED');
CREATE INDEX IF NOT EXISTS economy_v2_marriages_proposer_idx
  ON economy_v2_marriages (proposer_id,relationship_id DESC);
CREATE INDEX IF NOT EXISTS economy_v2_marriages_recipient_idx
  ON economy_v2_marriages (recipient_id,relationship_id DESC);
