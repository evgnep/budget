CREATE TABLE simple_object
(
    uuid    TEXT PRIMARY KEY,
    kind    TEXT    NOT NULL,
    hidden  INTEGER NOT NULL,
    content TEXT    NOT NULL
);
