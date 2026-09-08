ALTER TABLE "transaction" ADD COLUMN modified_at TIMESTAMP NOT NULL DEFAULT '1970-01-01 00:00:00';
ALTER TABLE "transaction" ADD COLUMN modified_by TEXT NOT NULL DEFAULT '';

-- backfill from the existing event log: the last event for each transaction is its last modification
UPDATE "transaction"
SET modified_at = COALESCE(
    (SELECT e.created FROM event e
     WHERE e.object_uuid = "transaction".uuid AND e.object_kind = 'TRANSACTION'
     ORDER BY e.created DESC, e.id DESC
     LIMIT 1),
    date
),
    modified_by = COALESCE(
    (SELECT e.creator FROM event e
     WHERE e.object_uuid = "transaction".uuid AND e.object_kind = 'TRANSACTION'
     ORDER BY e.created DESC, e.id DESC
     LIMIT 1),
    ''
);
