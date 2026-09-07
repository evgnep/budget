ALTER TABLE account ADD COLUMN credit_limit INTEGER NOT NULL DEFAULT 0; -- minor units, 0 means "no credit limit"
