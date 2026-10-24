# Messages on a dead-letter topic

**What it means.** A consumer gave up on a record (retries exhausted, or an unreadable payload) and parked it on `<topic>.DLT`.

**Customer impact.** The order that record belongs to is stuck at that saga step until the record is fixed and replayed.

## Triage
1. List parked topics and how many records wait (admin token required): `GET /api/v1/admin/dlt` on the notification service.
2. Read them: `GET /api/v1/admin/dlt/<topic>.DLT/records?limit=20` shows each record's key, payload and why it failed (`error`, from the `kafka_dlt-exception-message` header) and where it came from (`sourceTopic`).
3. Malformed payload (HTTP 400-type error): a producer bug; fix the producer, the bad message cannot be replayed as is.
4. Transient cause (database was down): the record itself is fine.

## Fix
* After fixing the cause, replay: `POST /api/v1/admin/dlt/<topic>.DLT/replay?max=100`. Each record goes back to the topic it came from, in order, and progress is remembered, so a record is not replayed twice by accident (consumers are idempotent anyway). If one cannot be sent the replay stops there and keeps the rest pending.
* Records that can never succeed (for example a malformed payload from a producer bug) are dropped from the pending list with `POST /api/v1/admin/dlt/<topic>.DLT/acknowledge`. The records stay in the topic as a log; copy them into the incident notes first (`GET .../records?limit=200`).
* Only the platform's own `<topic>.DLT` topics are accepted, and a record whose source header does not name a platform topic is skipped, never published anywhere.

## Escalate when
Many different records are parked at once.
