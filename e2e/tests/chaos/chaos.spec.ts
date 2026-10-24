import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { newSku, placeOrder, stock, uid, waitForStatus, waitForTerminal } from '../../support/api';
import { restart, sleep, whilePaused, whileStopped } from '../../support/chaos';

/**
 * What happens when a dependency disappears in the middle of the flow? The platform promises: nothing accepted is lost, nothing
 * is charged or reserved twice, nothing is oversold, and it heals by itself. Run serially (one failure at a time).
 */
test.describe.configure({ mode: 'serial' });

const payment = async (request: any, orderId: string) =>
  (await request.get(`${URLS.payment}/api/v1/payments/order/${orderId}`)).json();

test('Kafka hangs while orders are placed: they are accepted, wait in the outbox, and complete after recovery', async ({ request }) => {
  const sku = await newSku(request, 10);
  const ids = await whilePaused('kafka', async () => {
    const placed = await Promise.all([1, 2, 3].map(() => placeOrder(request, sku, 1)));
    expect(placed.map((p) => p.status())).toEqual([202, 202, 202]);   // the API does not depend on Kafka being up
    const ids = await Promise.all(placed.map(async (p) => (await p.json()).id as string));
    await sleep(4_000);
    for (const id of ids) {
      expect((await (await request.get(`/api/v1/orders/${id}`)).json()).status).toBe('PENDING');   // nothing can progress yet
    }
    return ids;
  });

  for (const id of ids) {
    expect((await waitForTerminal(request, id)).status).toBe('CONFIRMED');
    expect((await payment(request, id)).status).toBe('AUTHORIZED');   // one payment per order, never two
  }
  expect(await stock(request, sku)).toMatchObject({ available: 7, reserved: 3 });   // reserved exactly once each
});

test('the payment service is down mid-saga: orders wait at INVENTORY_RESERVED and finish when it returns', async ({ request }) => {
  const sku = await newSku(request, 10);
  const ids = await whileStopped('payment-service', async () => {
    const ids = await Promise.all([1, 2, 3, 4].map(async () => (await (await placeOrder(request, sku, 1)).json()).id as string));
    for (const id of ids) {
      await waitForStatus(request, id, /^INVENTORY_RESERVED$/);   // stock is held, payment cannot answer
    }
    return ids;
  });

  for (const id of ids) {
    expect((await waitForTerminal(request, id)).status).toBe('CONFIRMED');
    expect((await payment(request, id)).status).toBe('AUTHORIZED');
  }
  expect(await stock(request, sku)).toMatchObject({ available: 6, reserved: 4 });
});

test('inventory restarts while orders race for scarce stock: never oversold, nothing lost', async ({ request }) => {
  const initial = 5;
  const sku = await newSku(request, initial);

  const placing = Promise.all(Array.from({ length: 14 }, () => placeOrder(request, sku, 1)));
  await sleep(600);
  const restarting = restart('inventory-service');   // mid-flight: consumers rebalance, in-flight records are redelivered
  const ids = await Promise.all((await placing).map(async (p) => (await p.json()).id as string));
  await restarting;

  const results = await Promise.all(ids.map((id) => waitForTerminal(request, id)));
  const confirmed = results.filter((o) => o.status === 'CONFIRMED').length;
  const cancelled = results.filter((o) => o.status === 'CANCELLED').length;

  expect(confirmed + cancelled).toBe(14);               // every accepted order reached a final state
  expect(confirmed).toBeLessThanOrEqual(initial);       // never more sales than stock
  const level = await stock(request, sku);
  expect(level.available + level.reserved).toBe(initial);   // not a unit created or destroyed by the restart
  expect(level.reserved).toBe(confirmed);                   // exactly the confirmed orders hold stock
});

test('Redis hangs: orders and reads keep working, and only idempotent requests are refused honestly', async ({ request }) => {
  const sku = await newSku(request, 10);
  const key = uid('idem');
  const customer = uid('c');   // a retry must repeat the same request, or the key is (rightly) a conflict

  await whilePaused('redis', async () => {
    // no Idempotency-Key: nothing needs Redis (cache, rate limit and the stock lock all fail soft)
    const placed = await placeOrder(request, sku, 1);
    expect(placed.status()).toBe(202);
    const { id } = await placed.json();
    expect((await request.get(`/api/v1/orders/${id}`)).status()).toBe(200);

    // with a key we cannot promise "at most once" without Redis, so say so instead of risking a duplicate
    const keyed = await placeOrder(request, sku, 1, customer, { 'Idempotency-Key': key });
    expect(keyed.status()).toBe(503);
    expect(keyed.headers()['content-type']).toContain('application/problem+json');

    expect((await waitForTerminal(request, id)).status).toBe('CONFIRMED');   // the saga itself does not need Redis
  });

  // healed: the same key works again. The claim sent while Redis hung may land once it wakes, leaving an in-flight marker that
  // answers 409 ("still being processed") until its 30 s TTL passes; that is the safe answer, never a duplicate order.
  await expect.poll(async () => (await placeOrder(request, sku, 1, customer, { 'Idempotency-Key': key })).status(),
    { timeout: 60_000, intervals: [2_000] }).toBe(202);
});

test('Postgres hangs: no order is accepted that cannot be stored, and the platform recovers by itself', async ({ request }) => {
  const sku = await newSku(request, 10);
  const before = await (await placeOrder(request, sku, 1)).json();
  await waitForStatus(request, before.id, /^CONFIRMED$/);

  const started = Date.now();
  const during = await whilePaused('postgres', async () => placeOrder(request, sku, 1));
  expect(during.status()).toBeGreaterThanOrEqual(500);   // refused, never a "202" for an order that was not stored
  expect(Date.now() - started).toBeLessThan(30_000);     // and it fails fast instead of hanging for a minute

  // recovery needs no restart
  await expect.poll(async () => (await placeOrder(request, sku, 1)).status(), { timeout: 90_000, intervals: [3_000] }).toBe(202);
  expect((await (await request.get(`/api/v1/orders/${before.id}`)).json()).status).toBe('CONFIRMED');   // earlier work intact
});
