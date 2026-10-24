import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { bearer, tokenFor } from '../../support/api';
import { DEV_SECRET, signJwt } from '../../support/jwt';

/**
 * Inventory, payment, catalog and the admin areas are INTERNAL APIs. Even if a port is reachable they demand a token with
 * the right role: operators (ADMIN) and other platform services (SERVICE, read-only) only.
 */
const now = () => Math.floor(Date.now() / 1000);
const serviceToken = () => signJwt({ sub: 'order-service', roles: ['SERVICE'], iat: now(), exp: now() + 300 }, DEV_SECRET);
const ghost = '00000000-0000-0000-0000-000000000000';

type Case = { name: string; method: 'get' | 'post' | 'put'; url: string; data?: object; serviceMay: boolean };
const cases: Case[] = [
  { name: 'inventory read', method: 'get', url: `${URLS.inventory}/api/v1/inventory/SKU-1001`, serviceMay: true },
  { name: 'inventory restock', method: 'post', url: `${URLS.inventory}/api/v1/inventory/SEC-PROBE/restock`, data: { quantity: 1 }, serviceMay: false },
  { name: 'payment read', method: 'get', url: `${URLS.payment}/api/v1/payments/order/${ghost}`, serviceMay: true },
  { name: 'catalog list', method: 'get', url: `${URLS.catalog}/api/v1/products`, serviceMay: true },
  { name: 'catalog price change', method: 'put', url: `${URLS.catalog}/api/v1/products/SEC-PROBE`, data: { name: 'x', price: 1 }, serviceMay: false },
];

test.describe('internal APIs demand a token with the right role', () => {
  for (const c of cases) {
    test(`${c.name}: no token is 401 problem+json`, async ({ playwright }) => {
      const anon = await playwright.request.newContext({ extraHTTPHeaders: {} });
      const r = await anon[c.method](c.url, c.data ? { data: c.data } : {});
      expect(r.status()).toBe(401);
      expect(r.headers()['content-type']).toContain('application/problem+json');
      await anon.dispose();
    });

    test(`${c.name}: a customer token is 403`, async ({ playwright, request }) => {
      const customer = await playwright.request.newContext({ extraHTTPHeaders: bearer(await tokenFor(request, 'some-customer')) });
      expect((await customer[c.method](c.url, c.data ? { data: c.data } : {})).status()).toBe(403);
      await customer.dispose();
    });

    test(`${c.name}: a service token ${c.serviceMay ? 'is allowed' : 'is 403 (read-only)'}`, async ({ playwright }) => {
      const svc = await playwright.request.newContext({ extraHTTPHeaders: bearer(serviceToken()) });
      const r = await svc[c.method](c.url, c.data ? { data: c.data } : {});
      if (c.serviceMay) expect([200, 404]).toContain(r.status());
      else expect(r.status()).toBe(403);
      await svc.dispose();
    });

    test(`${c.name}: an admin token is allowed`, async ({ request }) => {
      const r = await request[c.method](c.url, c.data ? { data: c.data } : {});   // project default header = admin
      expect([200, 201, 404]).toContain(r.status());
    });
  }

  test('a token signed with the wrong key is rejected by every internal service', async ({ playwright }) => {
    const forged = signJwt({ sub: 'x', roles: ['ADMIN'], iat: now(), exp: now() + 300 }, 'f'.repeat(40));
    const bad = await playwright.request.newContext({ extraHTTPHeaders: bearer(forged) });
    for (const url of [`${URLS.inventory}/api/v1/inventory/SKU-1001`, `${URLS.payment}/api/v1/payments/order/${ghost}`, `${URLS.catalog}/api/v1/products`]) {
      expect((await bad.get(url)).status(), url).toBe(401);
    }
    await bad.dispose();
  });

  test('unknown paths are denied, not just unlisted', async ({ playwright, request }) => {
    const anon = await playwright.request.newContext({ extraHTTPHeaders: {} });
    expect((await anon.get(`${URLS.inventory}/api/v1/anything-else`)).status()).toBe(401);
    expect((await request.get(`${URLS.inventory}/api/v1/anything-else`)).status()).toBe(403);   // even for an admin
    await anon.dispose();
  });

  test('health and metrics stay open for the platform', async ({ playwright }) => {
    const anon = await playwright.request.newContext({ extraHTTPHeaders: {} });
    for (const base of [URLS.inventory, URLS.payment, URLS.catalog, URLS.notification]) {
      expect((await anon.get(`${base}/actuator/health`)).status(), base).toBe(200);
    }
    expect((await anon.get(`${URLS.inventory}/actuator/prometheus`)).status()).toBe(200);
    await anon.dispose();
  });

  test('the order service admin area is admin-only', async ({ playwright, request }) => {
    const customer = await playwright.request.newContext({ baseURL: URLS.order, extraHTTPHeaders: bearer(await tokenFor(request, 'alice')) });
    expect((await customer.get('/api/v1/admin/stuck-orders')).status()).toBe(403);
    await customer.dispose();
  });
});
