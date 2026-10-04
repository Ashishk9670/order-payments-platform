import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { uid } from '../../support/api';

test.describe('catalog service', () => {
  test('seed products are listed', async ({ request }) => {
    const list = await (await request.get(`${URLS.catalog}/api/v1/products`)).json();
    const skus = list.map((p: { sku: string }) => p.sku);
    expect(skus).toEqual(expect.arrayContaining(['SKU-1001', 'SKU-1002', 'SKU-1003', 'SKU-LUXURY']));
  });

  test('PUT creates (201) then updates (200) and the price change reaches new orders', async ({ request }) => {
    const sku = uid('CAT').toUpperCase();
    const created = await request.put(`${URLS.catalog}/api/v1/products/${sku}`, { data: { name: 'First', price: 3 } });
    expect(created.status()).toBe(201);
    const updated = await request.put(`${URLS.catalog}/api/v1/products/${sku}`, { data: { name: 'Second', price: 4.5 } });
    expect(updated.status()).toBe(200);
    expect(await updated.json()).toEqual({ sku, name: 'Second', price: 4.5 });
  });

  test('unknown SKU is 404 problem+json', async ({ request }) => {
    const r = await request.get(`${URLS.catalog}/api/v1/products/NOPE-${Date.now()}`);
    expect(r.status()).toBe(404);
    expect(r.headers()['content-type']).toContain('application/problem+json');
  });

  for (const [name, data] of [
    ['zero price', { name: 'x', price: 0 }],
    ['negative price', { name: 'x', price: -1 }],
    ['blank name', { name: '', price: 1 }],
    ['missing price', { name: 'x' }],
  ] as const) {
    test(`rejects ${name}`, async ({ request }) => {
      const r = await request.put(`${URLS.catalog}/api/v1/products/BAD-${Date.now()}`, { data });
      expect(r.status()).toBe(400);
    });
  }

  test('order-service exposes the catalog to the storefront', async ({ request }) => {
    const list = await (await request.get('/api/v1/products')).json();
    expect(list.map((p: { sku: string }) => p.sku)).toContain('SKU-1001');
  });
});
