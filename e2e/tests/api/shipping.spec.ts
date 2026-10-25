import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { confirmedOrder, getOrder, newSku, placeOrder, tokenFor, bearer, uid, waitForStatus, waitForTerminal } from '../../support/api';

/** Shipping: confirmed orders queue up for the warehouse; shipping stops cancellation; cancelling first stops the parcel. */
const shipment = async (request: any, orderId: string) =>
  request.get(`${URLS.shipping}/api/v1/shipments/order/${orderId}`);

const pendingShipment = async (request: any, orderId: string) => {
  await expect.poll(async () => (await shipment(request, orderId)).status(), { timeout: 30_000, intervals: [500, 1000] }).toBe(200);
  return (await shipment(request, orderId)).json();
};

const ship = (request: any, orderId: string, carrier = 'DHL', trackingNumber = 'TRACK-' + uid('t')) =>
  request.post(`${URLS.shipping}/api/v1/shipments/order/${orderId}/ship`, { data: { carrier, trackingNumber } });

test.describe('shipping', () => {
  test('a confirmed order is shipped, tracked on the order, can no longer be cancelled, then delivered', async ({ request }) => {
    const sku = await newSku(request, 5);
    const { id } = await confirmedOrder(request, sku, 1);

    expect((await pendingShipment(request, id)).status).toBe('PENDING');
    expect((await getOrder(request, id)).fulfillment ?? null).toBeNull();   // nothing yet

    const shipped = await ship(request, id, 'DHL', 'TRACK-E2E-1');
    expect(shipped.status()).toBe(200);
    expect((await shipped.json()).status).toBe('SHIPPED');

    await expect.poll(async () => (await getOrder(request, id)).fulfillment, { timeout: 30_000, intervals: [500, 1000] }).toBe('SHIPPED');
    const order = await getOrder(request, id);
    expect(order).toMatchObject({ status: 'CONFIRMED', carrier: 'DHL', trackingNumber: 'TRACK-E2E-1' });

    const cancel = await request.post(`/api/v1/orders/${id}/cancel`, { data: {} });   // too late: it left the warehouse
    expect(cancel.status()).toBe(409);
    expect(JSON.stringify(await cancel.json())).toContain('shipped');
    expect((await getOrder(request, id)).status).toBe('CONFIRMED');

    const delivered = await request.post(`${URLS.shipping}/api/v1/shipments/order/${id}/deliver`);
    expect(delivered.status()).toBe(200);
    await expect.poll(async () => (await getOrder(request, id)).fulfillment, { timeout: 30_000, intervals: [500, 1000] }).toBe('DELIVERED');
  });

  test('shipping twice, or delivering an unshipped parcel, is refused', async ({ request }) => {
    const sku = await newSku(request, 5);
    const { id } = await confirmedOrder(request, sku, 1);
    await pendingShipment(request, id);

    expect((await request.post(`${URLS.shipping}/api/v1/shipments/order/${id}/deliver`)).status()).toBe(409);
    expect((await ship(request, id)).status()).toBe(200);
    expect((await ship(request, id, 'UPS', 'OTHER')).status()).toBe(409);
    expect((await shipment(request, id).then((r: any) => r.json())).carrier).toBe('DHL');   // the first answer stands
  });

  test('cancelling before dispatch refunds the order and the parcel is never sent', async ({ request }) => {
    const sku = await newSku(request, 5);
    const { id } = await confirmedOrder(request, sku, 1);
    await pendingShipment(request, id);

    expect((await request.post(`/api/v1/orders/${id}/cancel`, { data: {} })).status()).toBe(202);
    expect((await waitForTerminal(request, id)).status).toBe('CANCELLED');

    await expect.poll(async () => (await (await shipment(request, id)).json()).status, { timeout: 30_000, intervals: [500, 1000] }).toBe('CANCELLED');
    expect((await ship(request, id)).status()).toBe(409);   // the warehouse cannot send a cancelled order
  });

  test('an order that fails never reaches the warehouse', async ({ request }) => {
    const sku = await newSku(request, 1);   // not enough stock: the order is cancelled, never confirmed
    const placed = await (await placeOrder(request, sku, 5)).json();
    await waitForStatus(request, placed.id, /^CANCELLED$/);

    expect((await shipment(request, placed.id)).status()).toBe(404);
  });

  test('the warehouse queue lists only pending parcels, oldest first', async ({ request }) => {
    const sku = await newSku(request, 5);
    const first = await confirmedOrder(request, sku, 1);
    await pendingShipment(request, first.id);
    const second = await confirmedOrder(request, sku, 1);
    await pendingShipment(request, second.id);
    await ship(request, second.id);   // leaves the queue

    // other runs leave pending parcels behind, so assert the property of the list rather than the position of our rows
    const queue = await (await request.get(`${URLS.shipping}/api/v1/shipments?status=PENDING&limit=200`)).json();
    expect(queue.length).toBeGreaterThan(0);
    expect(queue.every((s: any) => s.status === 'PENDING')).toBe(true);
    expect(queue.map((s: any) => s.orderId)).not.toContain(second.id);
    const times = queue.map((s: any) => Date.parse(s.createdAt));
    expect(times).toEqual([...times].sort((a: number, b: number) => a - b));   // oldest first
    expect((await (await shipment(request, second.id)).json()).status).toBe('SHIPPED');
  });

  test('the shipping API is for operators only', async ({ request, playwright }) => {
    const anon = await playwright.request.newContext({ extraHTTPHeaders: {} });
    expect((await anon.get(`${URLS.shipping}/api/v1/shipments`)).status()).toBe(401);
    await anon.dispose();

    const customer = await playwright.request.newContext({ extraHTTPHeaders: bearer(await tokenFor(request, uid('cust'))) });
    expect((await customer.get(`${URLS.shipping}/api/v1/shipments`)).status()).toBe(403);
    expect((await customer.post(`${URLS.shipping}/api/v1/shipments/order/${uid('x')}/deliver`)).status()).toBe(403);
    await customer.dispose();
  });

  test('shipping details are validated', async ({ request }) => {
    const r = await request.post(`${URLS.shipping}/api/v1/shipments/order/00000000-0000-0000-0000-000000000000/ship`, { data: { carrier: '' } });
    expect(r.status()).toBe(400);
  });
});
