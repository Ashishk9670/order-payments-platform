# Payment webhooks are failing signature verification

**What it means.** `POST /webhooks/psp` is answering 401 for many requests (`psp_webhooks_rejected_total`). The signature is
the only authentication, so a rejected request is either a configuration problem or someone probing the endpoint.

**Customer impact.** None directly; events from the processor (disputes, delayed settlements) are not being recorded, and the
processor will retry them and eventually give up.

## Triage
1. Did the shared secret change? Compare `PSP_WEBHOOK_SECRET` in this environment with the one in the processor's dashboard.
2. Is the clock right? Timestamps more than 5 minutes off are rejected as replays; check NTP on the node and the processor's retries.
3. Are the rejections from the processor's addresses? If not, it is probing: nothing to fix, the endpoint stores nothing without a valid signature.

## Fix
* Align the secret on both sides (rotate it if it may have leaked) and let the processor redeliver; delivery of the same event twice is harmless.
* To switch the endpoint off completely, unset `PSP_WEBHOOK_SECRET`: it then answers 404.

## Escalate when
The secret is correct, clocks are right, and requests from the processor still fail: the body may be modified by a proxy in front of the
service (the signature covers the exact raw bytes).
