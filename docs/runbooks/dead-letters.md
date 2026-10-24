# Messages on a dead-letter topic

**What it means.** A consumer gave up on a record (retries exhausted, or an unreadable payload) and parked it on `<topic>.DLT`.

**Customer impact.** The order that record belongs to is stuck at that saga step until the record is fixed and replayed.

## Triage
1. List parked topics and sample payloads (admin token required): `GET /api/v1/admin/dlt` on the notification service.
2. The record headers (`kafka_dlt-exception-message`, `kafka_dlt-original-topic`) say why it failed.
3. Malformed payload (HTTP 400-type error): a producer bug; fix the producer, the bad message cannot be replayed as is.
4. Transient cause (database was down): the record itself is fine.

## Fix
* After fixing the cause, replay: `POST /api/v1/admin/dlt/<topic>.DLT/replay?max=100`. Consumers are idempotent, so replaying twice is safe.
* Records that can never succeed should be discarded with `DELETE /api/v1/admin/dlt/<topic>.DLT?before=<offset>` after recording them in the incident notes.

## Escalate when
Many different records are parked at once.
