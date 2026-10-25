-- Every verified webhook the card processor sent us. The processor's event id is the primary key, which is also what makes
-- redelivery harmless: the second insert of the same id is a duplicate, not a second effect.
CREATE TABLE psp_webhook_event (
    event_id    VARCHAR(100) PRIMARY KEY,
    type        VARCHAR(100) NOT NULL,
    payload     TEXT         NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_psp_webhook_event_received_at ON psp_webhook_event (received_at DESC);
