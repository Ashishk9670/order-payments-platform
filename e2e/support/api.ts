import { APIRequestContext, expect } from '@playwright/test';
import { URLS } from '../playwright.config';

export type Order = {
  id: string; customerId: string;
  status: 'PENDING' | 'INVENTORY_RESERVED' | 'CONFIRMED' | 'REFUND_PENDING' | 'CANCELLED';
  totalAmount: number; cancelReason: string | null; items: { sku: string; quantity: number; unitPrice: number }[];
};

export const uid = (p: string) => `${p}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/**
 * Creates a brand-new product (priced in the catalog) with the given stock, so every test owns its SKU and can run in
 * parallel. Prices are server-side only: tests choose them here, never in the order request.
 */
export async function newSku(request: APIRequestContext, stock: number, price = 10): Promise<string> {
  const sku = uid('E2E').toUpperCase();
  const p = await request.put(`${URLS.catalog}/api/v1/products/${sku}`, { data: { name: `Test ${sku}`, price } });
  expect(p.status()).toBe(201);
  const r = await request.post(`${URLS.inventory}/api/v1/inventory/${sku}/restock`, { data: { quantity: stock } });
  expect(r.ok()).toBeTruthy();
  return sku;
}

export async function stock(request: APIRequestContext, sku: string) {
  const r = await request.get(`${URLS.inventory}/api/v1/inventory/${sku}`);
  expect(r.ok()).toBeTruthy();
  return (await r.json()) as { sku: string; available: number; reserved: number };
}

export async function placeOrder(request: APIRequestContext, sku: string, quantity: number,
                                 customerId = uid('cust'), headers: Record<string, string> = {}) {
  return request.post('/api/v1/orders', { headers, data: { customerId, items: [{ sku, quantity }] } });
}

export async function getOrder(request: APIRequestContext, id: string): Promise<Order> {
  const r = await request.get(`/api/v1/orders/${id}`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

export async function waitForStatus(request: APIRequestContext, id: string, pattern: RegExp): Promise<Order> {
  let last!: Order;
  await expect.poll(async () => (last = await getOrder(request, id)).status, { timeout: 40_000, intervals: [500, 1000] })
    .toMatch(pattern);
  return last;
}

/** The saga is asynchronous: wait for a terminal state instead of sleeping. */
export const waitForTerminal = (request: APIRequestContext, id: string) =>
  waitForStatus(request, id, /^(CONFIRMED|CANCELLED)$/);

/** Places an order and waits until it is CONFIRMED. */
export async function confirmedOrder(request: APIRequestContext, sku: string, quantity: number, customer = uid('cust')) {
  const { id } = await (await placeOrder(request, sku, quantity, customer)).json();
  const done = await waitForTerminal(request, id);
  expect(done.status).toBe('CONFIRMED');
  return done;
}

/** A token for one customer (non-admin), to test isolation between customers. */
export async function tokenFor(request: APIRequestContext, customerId: string, admin = false): Promise<string> {
  const r = await request.post(`${URLS.order}/dev/token`, { data: { customerId, admin }, headers: { Authorization: '' } });
  expect(r.ok()).toBeTruthy();
  return (await r.json()).token;
}

export const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });
