import { APIRequestContext, expect } from '@playwright/test';
import { URLS } from '../playwright.config';

export type Order = {
  id: string; customerId: string; status: 'PENDING' | 'INVENTORY_RESERVED' | 'CONFIRMED' | 'CANCELLED';
  totalAmount: number; cancelReason: string | null; items: { sku: string; quantity: number; unitPrice: number }[];
};

export const uid = (p: string) => `${p}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

/** Creates a brand-new SKU with the given stock so each test owns its inventory and can run in parallel. */
export async function newSku(request: APIRequestContext, stock: number): Promise<string> {
  const sku = uid('E2E').toUpperCase();
  const r = await request.post(`${URLS.inventory}/api/v1/inventory/${sku}/restock`, { data: { quantity: stock } });
  expect(r.ok()).toBeTruthy();
  return sku;
}

export async function stock(request: APIRequestContext, sku: string) {
  const r = await request.get(`${URLS.inventory}/api/v1/inventory/${sku}`);
  expect(r.ok()).toBeTruthy();
  return (await r.json()) as { sku: string; available: number; reserved: number };
}

export async function placeOrder(request: APIRequestContext, sku: string, quantity: number, unitPrice = 10,
                                 customerId = uid('cust'), headers: Record<string, string> = {}) {
  return request.post('/api/v1/orders', {
    headers, data: { customerId, items: [{ sku, quantity, unitPrice }] },
  });
}

export async function getOrder(request: APIRequestContext, id: string): Promise<Order> {
  const r = await request.get(`/api/v1/orders/${id}`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

/** The saga is asynchronous: wait for a terminal state instead of sleeping. */
export async function waitForTerminal(request: APIRequestContext, id: string): Promise<Order> {
  let last!: Order;
  await expect.poll(async () => (last = await getOrder(request, id)).status, { timeout: 40_000, intervals: [500, 1000] })
    .toMatch(/CONFIRMED|CANCELLED/);
  return last;
}
