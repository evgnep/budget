CREATE TABLE currency
(
    uuid               TEXT PRIMARY KEY,
    name               TEXT    NOT NULL,
    digits_after_point INTEGER NOT NULL,
    official_code      TEXT    NOT NULL,
    hidden             INTEGER NOT NULL
);

CREATE TABLE account
(
    uuid          TEXT PRIMARY KEY,
    name          TEXT    NOT NULL,
    description   TEXT    NOT NULL,
    currency_uuid TEXT    NOT NULL REFERENCES currency (uuid),
    kind          TEXT    NOT NULL,
    tags          TEXT    NOT NULL,
    order_no      INTEGER NOT NULL,
    hidden        INTEGER NOT NULL
);

CREATE TABLE account_rest
(
    uuid TEXT PRIMARY KEY REFERENCES account (uuid),
    name TEXT    NOT NULL,
    rest INTEGER NOT NULL
);

CREATE TABLE "transaction"
(
    uuid        TEXT PRIMARY KEY,
    date        TIMESTAMP NOT NULL,
    description TEXT      NOT NULL,
    flag        INTEGER   NOT NULL,
    deleted     INTEGER   NOT NULL
);

CREATE TABLE transaction_item
(
    id                  INTEGER PRIMARY KEY,
    transaction_uuid    TEXT REFERENCES "transaction" (uuid) ON DELETE CASCADE,
    transaction_date    TIMESTAMP NOT NULL, -- copy of transaction.date
    transaction_deleted INTEGER   NOT NULL, -- copy of transaction.deleted
    account_uuid        TEXT REFERENCES account (uuid),
    no                  INTEGER   NOT NULL,
    money               INTEGER   NOT NULL,
    description         TEXT      NOT NULL,
    flag                INTEGER   NOT NULL
);

CREATE TABLE event
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    source      TEXT      NOT NULL,
    no          INTEGER, -- absent for current place events while transaction is not commited
    created     TIMESTAMP NOT NULL,
    creator     TEXT      NOT NULL,
    object_uuid TEXT      NOT NULL,
    object_kind TEXT      NOT NULL,
    serialized  TEXT      NOT NULL
);

CREATE UNIQUE INDEX event_coords ON event (source, no);

CREATE INDEX event_object ON event (object_uuid, object_kind);

