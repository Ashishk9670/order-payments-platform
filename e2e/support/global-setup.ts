import { request } from '@playwright/test';
import { URLS } from '../playwright.config';

/** The UI offers the seeded SKUs; top them up so repeated local runs never run out of stock. */
export default async function globalSetup() {
  const ctx = await request.newContext();
  for (const sku of ['SKU-1001', 'SKU-1002']) {
    const r = await ctx.post(`${URLS.inventory}/api/v1/inventory/${sku}/restock`, { data: { quantity: 1000 } });
    if (!r.ok()) throw new Error(`Cannot restock ${sku}: ${r.status()} (is the stack up?)`);
  }
  await ctx.dispose();
}
