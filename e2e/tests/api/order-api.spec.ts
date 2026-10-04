import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { confirmedOrder, newSku, placeOrder, uid, waitForStatus, waitForTerminal } from '../../support/api';

test.describe('REST contract', () => {
  test('validation errors return 400 problem+json', async ({ request }) => {
    const r = await request.post('/api/v1/orders', { data: { customerId: '', items: [] } });
    expect(r.status()).toBe(400);
    expect(r.headers()['content-type']).toContain('application/problem+json');
  });

  for (const [name, item] of [
    ['zero quantity', { sku: 'S', quantity: 0 }],
    ['excessive quantity', { sku: 'S', quantity: 5000 }],
    ['blank sku', { sku: '', quantity: 1 }],
  ] as const) {
    test(`rejects ${name}`, async ({ request }) => {
      const r = await request.post('/api/v1/orders', { data: { customerId: 'c', items: [item] } });
      expect(r.status()).toBe(400);
    });
  }

  test('unknown product is rejected synchronously with 422 and no order is created', async ({ request }) => {
    const customer = uid('cust');
    const r = await placeOrder(request, `NO-SUCH-${Date.now()}`, 1, customer);
    expect(r.status()).toBe(422);
    expect((await r.json()).detail).toContain('Unknown product');
    expect(await (await request.get(`/api/v1/orders?customerId=${customer}`)).json()).toHaveLength(0);
  });

  test('the server prices the order: a client-supplied price is ignored', async ({ request }) => {
    const sku = await newSku(request, 5, 40);
    const r = await request.post('/api/v1/orders', {
      data: { customerId: uid('cust'), items: [{ sku, quantity: 2, unitPrice: 0.01 }] },
    });
    expect(r.status()).toBe(202);
    const order = await r.json();
    expect(order.totalAmount).toBeCloseTo(80);
    expect(order.items[0].unitPrice).toBeCloseTo(40);
  });

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

    const first = await placeOrder(request, sku, 1, customer, { 'Idempotency-Key': key });
    const second = await placeOrder(request, sku, 1, customer, { 'Idempotency-Key': key });

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
    for (let i = 0; i < 3; i++) await placeOrder(request, sku, 1, customer);

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
    const sku = await newSku(request, 10, 7);
    const customer = uid('cust');
    const { id } = await (await placeOrder(request, sku, 2, customer)).json();
    await waitForTerminal(request, id);

    const body = await (await gql(request,
      `query($c:String!){ ordersByCustomer(customerId:$c){ id status totalAmount items{ sku quantity unitPrice } } }`, { c: customer })).json();

    expect(body.errors).toBeUndefined();
    expect(body.data.ordersByCustomer).toEqual([
      { id, status: 'CONFIRMED', totalAmount: 14, items: [{ sku, quantity: 2, unitPrice: 7 }] },
    ]);
  });

  test('placeOrder mutation prices from the catalog and the saga completes', async ({ request }) => {
    const sku = await newSku(request, 10, 6);
    const body = await (await gql(request,
      `mutation($i:PlaceOrderInput!){ placeOrder(input:$i){ id status totalAmount } }`,
      { i: { customerId: uid('cust'), items: [{ sku, quantity: 3 }] } })).json();

    expect(body.errors).toBeUndefined();
    expect(body.data.placeOrder).toMatchObject({ status: 'PENDING', totalAmount: 18 });
    expect((await waitForTerminal(request, body.data.placeOrder.id)).status).toBe('CONFIRMED');
  });

  test('placeOrder with an unknown product is a typed error', async ({ request }) => {
    const body = await (await gql(request,
      `mutation($i:PlaceOrderInput!){ placeOrder(input:$i){ id } }`,
      { i: { customerId: 'c', items: [{ sku: 'NOPE-GQL', quantity: 1 }] } })).json();
    expect(body.errors[0].message).toContain('Unknown product');
    expect(body.errors[0].extensions.classification).toBe('BAD_REQUEST');
  });

  test('placeOrder input is validated like REST', async ({ request }) => {
    const body = await (await gql(request,
      `mutation($i:PlaceOrderInput!){ placeOrder(input:$i){ id } }`,
      { i: { customerId: '', items: [{ sku: 'X', quantity: 0 }] } })).json();
    expect(body.errors?.length).toBeGreaterThan(0);
  });

  test('cancelOrder mutation drives the refund saga', async ({ request }) => {
    const sku = await newSku(request, 5);
    const order = await confirmedOrder(request, sku, 1);
    const body = await (await gql(request, `mutation($id:ID!){ cancelOrder(id:$id, reason:"gql"){ id status } }`, { id: order.id })).json();
    expect(body.errors).toBeUndefined();
    expect(['REFUND_PENDING', 'CANCELLED']).toContain(body.data.cancelOrder.status);
    expect((await waitForStatus(request, order.id, /^CANCELLED$/)).cancelReason).toContain('gql');
  });

  test('cancelOrder on a non-confirmed order is a typed error', async ({ request }) => {
    const sku = await newSku(request, 1);
    const { id } = await (await placeOrder(request, sku, 9)).json();
    await waitForStatus(request, id, /^CANCELLED$/);
    const body = await (await gql(request, `mutation($id:ID!){ cancelOrder(id:$id){ id } }`, { id })).json();
    expect(body.errors[0].extensions.classification).toBe('BAD_REQUEST');
  });

  test('orderStatusChanged subscription streams status changes over SSE until a terminal state', async ({ request, baseURL }) => {
    const sku = await newSku(request, 5);
    const { id } = await (await placeOrder(request, sku, 1)).json();

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 30_000);
    const res = await fetch(`${baseURL}/graphql`, {
      method: 'POST', signal: ctrl.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ query: `subscription($id:ID!){ orderStatusChanged(id:$id){ status } }`, variables: { id } }),
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('text/event-stream');

    const statuses: string[] = [];
    const reader = res.body!.getReader();
    const dec = new TextDecoder();
    let buf = '';
    outer: for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i: number;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i); buf = buf.slice(i + 2);
        const data = block.split('\n').find((l) => l.startsWith('data:'));
        if (/^event: *next/m.test(block) && data) statuses.push(JSON.parse(data.slice(5)).data.orderStatusChanged.status);
        if (/^event: *complete/m.test(block)) break outer;
      }
    }
    clearTimeout(timer);

    expect(statuses.length).toBeGreaterThanOrEqual(2);
    expect(statuses[0]).toMatch(/PENDING|INVENTORY_RESERVED/);
    expect(statuses[statuses.length - 1]).toBe('CONFIRMED');
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

test.describe('inventory REST', () => {
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
