ALTER TABLE account ADD COLUMN budget TEXT NOT NULL DEFAULT '{}';

ALTER TABLE transaction_item ADD COLUMN reserved_until TEXT; -- ISO date, null when not reserved
