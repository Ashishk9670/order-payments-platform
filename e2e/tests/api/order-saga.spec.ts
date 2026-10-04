import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { newSku, stock, placeOrder, waitForTerminal } from '../../support/api';

test.describe('order saga across order, catalog, inventory, payment services', () => {
  test('happy path: priced from catalog, confirmed, stock reserved, payment authorized', async ({ request }) => {
    const sku = await newSku(request, 10, 12.5);

    const placed = await placeOrder(request, sku, 3);
    expect(placed.status()).toBe(202);
    const order = await placed.json();
    expect(order.status).toBe('PENDING');
    expect(order.totalAmount).toBeCloseTo(37.5);
    expect(order.items[0].unitPrice).toBeCloseTo(12.5);
    expect(placed.headers()['location']).toBe(`/api/v1/orders/${order.id}`);

    const done = await waitForTerminal(request, order.id);
    expect(done.status).toBe('CONFIRMED');

    expect(await stock(request, sku)).toMatchObject({ available: 7, reserved: 3 });
    const payment = await (await request.get(`${URLS.payment}/api/v1/payments/order/${order.id}`)).json();
    expect(payment).toMatchObject({ status: 'AUTHORIZED', orderId: order.id });
    expect(payment.amount).toBeCloseTo(37.5);
  });

  test('insufficient stock cancels the order and leaves stock untouched', async ({ request }) => {
    const sku = await newSku(request, 2);

    const { id } = await (await placeOrder(request, sku, 5)).json();
    const done = await waitForTerminal(request, id);

    expect(done.status).toBe('CANCELLED');
    expect(done.cancelReason).toContain('Inventory');
    expect(await stock(request, sku)).toMatchObject({ available: 2, reserved: 0 });
    expect((await request.get(`${URLS.payment}/api/v1/payments/order/${id}`)).status()).toBe(404); // never charged
  });

  test('declined payment cancels the order and compensates by releasing reserved stock', async ({ request }) => {
    const sku = await newSku(request, 10, 20_000); // above the simulated 10 000 card limit

    const { id } = await (await placeOrder(request, sku, 2)).json();
    const done = await waitForTerminal(request, id);

    expect(done.status).toBe('CANCELLED');
    expect(done.cancelReason).toContain('Payment');
    // compensation is its own async hop (PaymentFailed -> inventory), so poll for it
    await expect.poll(async () => stock(request, sku), { timeout: 30_000 }).toMatchObject({ available: 10, reserved: 0 });
    const payment = await (await request.get(`${URLS.payment}/api/v1/payments/order/${id}`)).json();
    expect(payment.status).toBe('FAILED');
  });

  test('multi-line order is all-or-nothing', async ({ request }) => {
    const plenty = await newSku(request, 10);
    const scarce = await newSku(request, 1);

    const r = await request.post('/api/v1/orders', {
      data: { customerId: 'c', items: [{ sku: plenty, quantity: 4 }, { sku: scarce, quantity: 9 }] },
    });
    const done = await waitForTerminal(request, (await r.json()).id);

    expect(done.status).toBe('CANCELLED');
    expect(await stock(request, plenty)).toMatchObject({ available: 10, reserved: 0 });
  });

  test('never oversells under concurrent orders', async ({ request }) => {
    const sku = await newSku(request, 5);

    const placed = await Promise.all(Array.from({ length: 12 }, () => placeOrder(request, sku, 1)));
    const ids = await Promise.all(placed.map(async (p) => (await p.json()).id as string));
    const results = await Promise.all(ids.map((id) => waitForTerminal(request, id)));

    expect(results.filter((o) => o.status === 'CONFIRMED')).toHaveLength(5);
    expect(results.filter((o) => o.status === 'CANCELLED')).toHaveLength(7);
    expect(await stock(request, sku)).toMatchObject({ available: 0, reserved: 5 });
  });
});
