import { test, expect, APIRequestContext } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { confirmedOrder, newSku, placeOrder, stock, uid, waitForStatus, waitForTerminal } from '../../support/api';

/**
 * The HTTP payment adapter against a fake card processor (WireMock), inside the real stack.
 * Start with:  PAYMENT_GATEWAY=http docker compose --profile apps --profile psp up -d      then  E2E_PSP=1 npx playwright test --project=psp
 * Processor behaviour is driven by the amount: >= 20000 declined, 13.13 -> HTTP 402, 66.66 -> HTTP 503, otherwise approved.
 * These tests share one circuit breaker, so they run serially (workers: 1) and the outage test leaves it recovered.
 */
test.describe.configure({ mode: 'serial' });

type Call = { path: string; key: string | undefined; orderId: string; amount: number };

async function processorCalls(request: APIRequestContext, orderId: string): Promise<Call[]> {
  const r = await request.post(`${URLS.psp}/__admin/requests/find`, {
    data: { method: 'POST', urlPathPattern: '/v1/(charges|refunds)' }, headers: { Authorization: '' },
  });
  const { requests } = await r.json();
  return requests
    .map((q: any) => ({ path: new URL(q.url, 'http://x').pathname, key: q.headers['Idempotency-Key'], body: JSON.parse(q.body) }))
    .filter((q: any) => q.body.orderId === orderId)
    .map((q: any) => ({ path: q.path, key: q.key, orderId: q.body.orderId, amount: q.body.amount }));
}

test('approved charge: order confirmed, processor called once with an idempotency key', async ({ request }) => {
  const sku = await newSku(request, 5, 25);
  const order = await confirmedOrder(request, sku, 1);

  const calls = await processorCalls(request, order.id);
  expect(calls).toHaveLength(1);
  expect(calls[0]).toMatchObject({ path: '/v1/charges', key: `charge-${order.id}`, amount: 25 });
});

test('a decline from the issuer cancels the order, with the processor reason, and releases the stock', async ({ request }) => {
  const sku = await newSku(request, 5, 20_000);
  const { id } = await (await placeOrder(request, sku, 1)).json();

  const done = await waitForTerminal(request, id);

  expect(done.status).toBe('CANCELLED');
  expect(done.cancelReason).toContain('Card declined by issuer');
  await expect.poll(() => stock(request, sku), { timeout: 30_000 }).toMatchObject({ available: 5, reserved: 0 });
});

test('a 4xx from the processor is a final answer: declined, and NOT retried', async ({ request }) => {
  const sku = await newSku(request, 5, 13.13);
  const { id } = await (await placeOrder(request, sku, 1)).json();

  const done = await waitForTerminal(request, id);

  expect(done.status).toBe('CANCELLED');
  expect(done.cancelReason).toContain('Rejected by processor (402)');
  expect(await processorCalls(request, id)).toHaveLength(1);
});

test('refund goes to the processor with its own idempotency key', async ({ request }) => {
  const sku = await newSku(request, 5, 30);
  const order = await confirmedOrder(request, sku, 1);

  expect((await request.post(`/api/v1/orders/${order.id}/cancel`)).status()).toBe(202);
  await waitForStatus(request, order.id, /^CANCELLED$/);

  const calls = await processorCalls(request, order.id);
  expect(calls.map((c) => c.path).sort()).toEqual(['/v1/charges', '/v1/refunds']);
  expect(calls.find((c) => c.path === '/v1/refunds')!.key).toBe(`refund-${order.id}`);
});

test('processor outage: retried with the SAME key, then declined; the circuit opens, then self-heals', async ({ request }) => {
  const outageSku = await newSku(request, 20, 66.66);

  // first order: 3 attempts (retry), all with the same idempotency key, then a clean decline
  const first = await (await placeOrder(request, outageSku, 1)).json();
  const done = await waitForTerminal(request, first.id);
  expect(done.status).toBe('CANCELLED');
  expect(done.cancelReason).toContain('Payment provider unavailable');
  const attempts = await processorCalls(request, first.id);
  expect(attempts).toHaveLength(3);
  expect(new Set(attempts.map((a) => a.key))).toEqual(new Set([`charge-${first.id}`]));

  // a second outage order pushes the failure count past the breaker threshold: the circuit opens
  const second = await (await placeOrder(request, outageSku, 1)).json();
  await waitForTerminal(request, second.id);

  // now even a perfectly normal order is declined WITHOUT touching the processor (fail fast)
  const healthySku = await newSku(request, 20, 40);
  const blocked = await (await placeOrder(request, healthySku, 1)).json();
  const blockedDone = await waitForTerminal(request, blocked.id);
  expect(blockedDone.status).toBe('CANCELLED');
  expect(blockedDone.cancelReason).toContain('Payment provider unavailable');
  expect(await processorCalls(request, blocked.id)).toHaveLength(0);

  // after the breaker's wait time it probes the processor again and recovers by itself
  await expect.poll(async () => {
    const o = await (await placeOrder(request, healthySku, 1)).json();
    return (await waitForTerminal(request, o.id)).status;
  }, { timeout: 55_000, intervals: [4_000] }).toBe('CONFIRMED');
});
