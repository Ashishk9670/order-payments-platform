import { request } from '@playwright/test';
import { URLS } from '../playwright.config';

/** The UI offers the seeded SKUs; top them up so repeated local runs never run out of stock. */
export default async function globalSetup() {
  // Keycloak mode has no dev token endpoint (that is the point of the mode); its suite logs in as real realm users
  if (process.env.E2E_KEYCLOAK) return;
  const ctx = await request.newContext();
  const t = await ctx.post(`${URLS.order}/dev/token`, { data: { customerId: 'e2e-admin', admin: true } });
  if (!t.ok()) throw new Error(`Cannot mint an e2e admin token: ${t.status()} (is DEV_TOKEN_ENABLED=true?)`);
  process.env.E2E_ADMIN_TOKEN = (await t.json()).token;   // inherited by the worker processes
  for (const sku of ['SKU-1001', 'SKU-1002', 'SKU-LUXURY']) {
    const r = await ctx.post(`${URLS.inventory}/api/v1/inventory/${sku}/restock`, { data: { quantity: 1000 } });
    if (!r.ok()) throw new Error(`Cannot restock ${sku}: ${r.status()} (is the stack up?)`);
  }
  await ctx.dispose();
}
