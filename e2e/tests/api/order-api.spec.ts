import { test, expect } from '@playwright/test';
import { newSku, placeOrder, uid, waitForTerminal } from '../../support/api';
import { URLS } from '../../playwright.config';

test.describe('REST contract', () => {
  test('validation errors return 400 problem+json', async ({ request }) => {
    const r = await request.post('/api/v1/orders', { data: { customerId: '', items: [] } });
    expect(r.status()).toBe(400);
    expect(r.headers()['content-type']).toContain('application/problem+json');
  });

  for (const [name, item] of [
    ['zero quantity', { sku: 'S', quantity: 0, unitPrice: 1 }],
    ['negative price', { sku: 'S', quantity: 1, unitPrice: -5 }],
    ['blank sku', { sku: '', quantity: 1, unitPrice: 1 }],
  ] as const) {
    test(`rejects ${name}`, async ({ request }) => {
      const r = await request.post('/api/v1/orders', { data: { customerId: 'c', items: [item] } });
      expect(r.status()).toBe(400);
    });
  }

  test('unknown order id returns 404 problem+json', async ({ request }) => {
    const r = await request.get('/api/v1/orders/00000000-0000-0000-0000-000000000000');
    expect(r.status()).toBe(404);
    expect(r.headers()['content-type']).toContain('application/problem+json');
  });

  test('malformed order id returns 400', async ({ request }) => {
    expect((await request.get('/api/v1/orders/not-a-uuid')).status()).toBe(400);
  });

  test('Idempotency-Key: a retry returns the original order and creates no duplicate', async ({ request }) => {
    const sku = await newSku(request, 10);
    const customer = uid('cust');
    const key = uid('key');

    const first = await placeOrder(request, sku, 1, 5, customer, { 'Idempotency-Key': key });
    const second = await placeOrder(request, sku, 1, 5, customer, { 'Idempotency-Key': key });

    expect(first.status()).toBe(202);
    expect(second.status()).toBe(200);
    expect((await second.json()).id).toBe((await first.json()).id);
    const list = await (await request.get(`/api/v1/orders?customerId=${customer}`)).json();
    expect(list).toHaveLength(1);
    await waitForTerminal(request, (await first.json()).id);
  });

  test('list endpoint is paginated and newest first', async ({ request }) => {
    const sku = await newSku(request, 10);
    const customer = uid('cust');
    for (let i = 0; i < 3; i++) await placeOrder(request, sku, 1, 1, customer);

    const page = await (await request.get(`/api/v1/orders?customerId=${customer}&size=2`)).json();
    const all = await (await request.get(`/api/v1/orders?customerId=${customer}`)).json();
    expect(page).toHaveLength(2);
    expect(all).toHaveLength(3);
    expect(page[0].id).toBe(all[0].id);
  });
});

test.describe('GraphQL', () => {
  const gql = (request: any, query: string, variables?: object) =>
    request.post('/graphql', { data: { query, variables } });

  test('ordersByCustomer returns orders with their items', async ({ request }) => {
    const sku = await newSku(request, 10);
    const customer = uid('cust');
    const { id } = await (await placeOrder(request, sku, 2, 7, customer)).json();
    await waitForTerminal(request, id);

    const r = await gql(request, `query($c:String!){ ordersByCustomer(customerId:$c){ id status totalAmount items{ sku quantity unitPrice } } }`,
      { c: customer });
    const body = await r.json();

    expect(body.errors).toBeUndefined();
    expect(body.data.ordersByCustomer).toEqual([
      { id, status: 'CONFIRMED', totalAmount: 14, items: [{ sku, quantity: 2, unitPrice: 7 }] },
    ]);
  });

  test('order(id) returns null for an unknown id', async ({ request }) => {
    const r = await gql(request, `{ order(id:"00000000-0000-0000-0000-000000000000"){ id } }`);
    expect((await r.json()).data.order).toBeNull();
  });

  test('schema violations are reported in errors[]', async ({ request }) => {
    const r = await gql(request, `{ order(id:"x"){ doesNotExist } }`);
    expect((await r.json()).errors?.length).toBeGreaterThan(0);
  });
});

test.describe('inventory & payment REST', () => {
  test('restock creates and then increments a SKU', async ({ request }) => {
    const sku = await newSku(request, 4);
    const r = await request.post(`${URLS.inventory}/api/v1/inventory/${sku}/restock`, { data: { quantity: 6 } });
    expect(await r.json()).toMatchObject({ sku, available: 10, reserved: 0 });
  });

  test('restock rejects non-positive quantity', async ({ request }) => {
    const r = await request.post(`${URLS.inventory}/api/v1/inventory/X/restock`, { data: { quantity: 0 } });
    expect(r.status()).toBe(400);
  });
});
