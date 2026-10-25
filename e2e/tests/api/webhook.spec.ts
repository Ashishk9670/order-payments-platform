import { test, expect } from '@playwright/test';
import { createHmac } from 'node:crypto';
import { URLS } from '../../playwright.config';
import { uid } from '../../support/api';

// The processor's webhook secret as configured for the compose stack (dev only; see docker-compose.yml)
const SECRET = process.env.PSP_WEBHOOK_SECRET ?? 'dev-only-webhook-secret';

const signed = (body: string, t = Math.floor(Date.now() / 1000), secret = SECRET) =>
  `t=${t},v1=${createHmac('sha256', secret).update(`${t}.${body}`).digest('hex')}`;

const url = `${URLS.payment}/webhooks/psp`;
const send = (request: any, body: string, signature?: string) =>
  request.post(url, {
    data: body,
    headers: { 'Content-Type': 'application/json', Authorization: '', ...(signature ? { 'PSP-Signature': signature } : {}) },
  });

test.describe('payment webhooks (HMAC-signed, replay-protected, deduplicated)', () => {
  test('a correctly signed event is stored once; redelivery is acknowledged as a duplicate', async ({ request }) => {
    const id = uid('evt');
    const body = JSON.stringify({ id, type: 'charge.dispute.created', amount: 1200 });

    const first = await send(request, body, signed(body));
    expect(first.status()).toBe(200);
    expect((await first.json()).status).toBe('received');

    const again = await send(request, body, signed(body));
    expect(again.status()).toBe(200);
    expect((await again.json()).status).toBe('duplicate');

    const listed = await (await request.get(`${URLS.payment}/api/v1/payments/webhook-events?limit=100`)).json();
    expect(listed.filter((e: any) => e.id === id)).toHaveLength(1);   // stored exactly once
  });

  test('concurrent deliveries of the same event produce one row', async ({ request }) => {
    const id = uid('evt');
    const body = JSON.stringify({ id, type: 'charge.refunded' });
    const results = await Promise.all(Array.from({ length: 8 }, () => send(request, body, signed(body))));
    const statuses = await Promise.all(results.map(async (r) => (await r.json()).status));

    expect(results.every((r) => r.status() === 200)).toBe(true);
    expect(statuses.filter((s) => s === 'received')).toHaveLength(1);
    const listed = await (await request.get(`${URLS.payment}/api/v1/payments/webhook-events?limit=100`)).json();
    expect(listed.filter((e: any) => e.id === id)).toHaveLength(1);
  });

  test('forged, unsigned, tampered and replayed requests are rejected and leave no trace', async ({ request }) => {
    const id = uid('evt');
    const body = JSON.stringify({ id, type: 'charge.dispute.created' });
    const now = Math.floor(Date.now() / 1000);

    expect((await send(request, body)).status()).toBe(401);                                      // unsigned
    expect((await send(request, body, signed(body, now, 'wrong-secret'))).status()).toBe(401);   // forged
    expect((await send(request, body.replace(id, 'other'), signed(body))).status()).toBe(401);   // tampered after signing
    expect((await send(request, body, signed(body, now - 10 * 60))).status()).toBe(401);         // stale: replay of an old capture

    const listed = await (await request.get(`${URLS.payment}/api/v1/payments/webhook-events?limit=100`)).json();
    expect(listed.some((e: any) => e.id === id)).toBe(false);
  });

  test('a signed body that is not an event is a 400', async ({ request }) => {
    const body = JSON.stringify({ amount: 1 });
    expect((await send(request, body, signed(body))).status()).toBe(400);
  });

  test('the stored events are not readable without an admin token', async ({ playwright }) => {
    const anon = await playwright.request.newContext({ extraHTTPHeaders: {} });
    expect((await anon.get(`${URLS.payment}/api/v1/payments/webhook-events`)).status()).toBe(401);
    await anon.dispose();
  });
});
