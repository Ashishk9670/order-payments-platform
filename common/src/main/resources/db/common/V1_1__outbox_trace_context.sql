-- W3C trace context captured when the event was written, so the (asynchronous) relay can continue the same trace
ALTER TABLE outbox_event ADD COLUMN trace_context VARCHAR(512);
