-- Reservations that are never paid must not hold stock forever: record when each one was made so a job can expire the stale ones.
ALTER TABLE reservation ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_reservation_expiry ON reservation (created_at) WHERE status = 'RESERVED';
