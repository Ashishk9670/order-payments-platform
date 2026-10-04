import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { confirmedOrder, newSku, placeOrder, stock, uid, waitForStatus } from '../../support/api';

test.describe('cancellation / refund saga', () => {
  test('cancelling a confirmed order refunds the payment, restores stock and ends CANCELLED', async ({ request }) => {
    const sku = await newSku(request, 10, 8);
    const order = await confirmedOrder(request, sku, 4);
    expect(await stock(request, sku)).toMatchObject({ available: 6, reserved: 4 });

    const cancel = await request.post(`/api/v1/orders/${order.id}/cancel`, { data: { reason: 'changed my mind' } });
    expect(cancel.status()).toBe(202);
    expect((await cancel.json()).status).toBe('REFUND_PENDING');

    const done = await waitForStatus(request, order.id, /^CANCELLED$/);
    expect(done.cancelReason).toBe('Cancelled by customer: changed my mind');
    await expect.poll(() => stock(request, sku), { timeout: 30_000 }).toMatchObject({ available: 10, reserved: 0 });
    const payment = await (await request.get(`${URLS.payment}/api/v1/payments/order/${order.id}`)).json();
    expect(payment.status).toBe('REFUNDED');
  });

  test('cancel without a body is accepted', async ({ request }) => {
    const sku = await newSku(request, 5);
    const order = await confirmedOrder(request, sku, 1);
    expect((await request.post(`/api/v1/orders/${order.id}/cancel`)).status()).toBe(202);
    const done = await waitForStatus(request, order.id, /^CANCELLED$/);
    expect(done.cancelReason).toBe('Cancelled by customer');
  });

  test('a cancelled order cannot be cancelled again', async ({ request }) => {
    const sku = await newSku(request, 5);
    const order = await confirmedOrder(request, sku, 1);
    await request.post(`/api/v1/orders/${order.id}/cancel`);
    await waitForStatus(request, order.id, /^CANCELLED$/);

    const again = await request.post(`/api/v1/orders/${order.id}/cancel`);
    expect(again.status()).toBe(409);
    expect((await again.json()).detail).toContain('CANCELLED');
  });

  test('a pending or already-cancelled-by-failure order cannot be cancelled', async ({ request }) => {
    const sku = await newSku(request, 1);
    const { id } = await (await placeOrder(request, sku, 5)).json();   // will fail on stock
    await waitForStatus(request, id, /^CANCELLED$/);
    expect((await request.post(`/api/v1/orders/${id}/cancel`)).status()).toBe(409);
  });

  test('cancelling an unknown order is 404', async ({ request }) => {
    const r = await request.post('/api/v1/orders/00000000-0000-0000-0000-000000000000/cancel');
    expect(r.status()).toBe(404);
  });

  test('concurrent cancels refund exactly once', async ({ request }) => {
    const sku = await newSku(request, 5);
    const order = await confirmedOrder(request, sku, 2);

    const results = await Promise.all(Array.from({ length: 5 }, () => request.post(`/api/v1/orders/${order.id}/cancel`)));

    expect(results.filter((r) => r.status() === 202).length).toBeGreaterThanOrEqual(1);
    expect(results.every((r) => [202, 409].includes(r.status()))).toBe(true);
    await waitForStatus(request, order.id, /^CANCELLED$/);
    await expect.poll(() => stock(request, sku), { timeout: 30_000 }).toMatchObject({ available: 5, reserved: 0 });
    const payment = await (await request.get(`${URLS.payment}/api/v1/payments/order/${order.id}`)).json();
    expect(payment.status).toBe('REFUNDED');
  });

  test('cancel is idempotent per order id under double-submit from different customers is still isolated', async ({ request }) => {
    const sku = await newSku(request, 10);
    const a = await confirmedOrder(request, sku, 1, uid('a'));
    const b = await confirmedOrder(request, sku, 1, uid('b'));
    await request.post(`/api/v1/orders/${a.id}/cancel`);
    await waitForStatus(request, a.id, /^CANCELLED$/);
    expect((await (await request.get(`/api/v1/orders/${b.id}`)).json()).status).toBe('CONFIRMED');
  });
});
