import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { bearer, confirmedOrder, newSku, placeOrder, tokenFor, uid, waitForStatus } from '../../support/api';
import { DEV_SECRET, signJwt, unsigned } from '../../support/jwt';

const now = () => Math.floor(Date.now() / 1000);

/** A client with NO default credentials (the project-wide admin token is not applied). */
const anonymous = (playwright: any) => playwright.request.newContext({ baseURL: URLS.order });

test.describe('authentication', () => {
  test('requests without a token are 401 problem+json', async ({ playwright }) => {
    const anon = await anonymous(playwright);
    for (const r of [
      await anon.get('/api/v1/orders/00000000-0000-0000-0000-000000000000'),
      await anon.get('/api/v1/orders?customerId=x'),
      await anon.post('/api/v1/orders', { data: { customerId: 'x', items: [{ sku: 'S', quantity: 1 }] } }),
      await anon.post('/graphql', { data: { query: '{ order(id:"x"){ id } }' } }),
      await anon.get('/api/v1/products'),
    ]) {
      expect(r.status()).toBe(401);
      expect(r.headers()['content-type']).toContain('application/problem+json');
    }
    await anon.dispose();
  });

  test('health, UI and GraphiQL stay public', async ({ playwright }) => {
    const anon = await anonymous(playwright);
    expect((await anon.get('/actuator/health')).status()).toBe(200);
    expect((await anon.get('/')).status()).toBe(200);
    expect((await anon.get('/graphiql')).status()).toBe(200);
    await anon.dispose();
  });

  const claims = () => ({ sub: 'attacker', iat: now(), exp: now() + 600, roles: ['ADMIN'] });
  for (const [name, token] of [
    ['garbage', () => 'not.a.jwt'],
    ['signed with the wrong secret', () => signJwt(claims(), 'x'.repeat(40))],
    ['alg=none (unsigned)', () => unsigned(claims())],
    ['expired', () => signJwt({ ...claims(), iat: now() - 7200, exp: now() - 3600 }, DEV_SECRET)],
    ['tampered payload (privilege escalation)', () => {
      const good = signJwt({ sub: 'alice', iat: now(), exp: now() + 600, roles: ['CUSTOMER'] }, DEV_SECRET).split('.');
      const forged = Buffer.from(JSON.stringify({ sub: 'alice', iat: now(), exp: now() + 600, roles: ['ADMIN'] })).toString('base64url');
      return `${good[0]}.${forged}.${good[2]}`;
    }],
  ] as const) {
    test(`rejects a ${name} token`, async ({ playwright }) => {
      const anon = await playwright.request.newContext({ baseURL: URLS.order, extraHTTPHeaders: bearer(token()) });
      const r = await anon.get('/api/v1/products');
      expect(r.status()).toBe(401);
      await anon.dispose();
    });
  }

  test('a valid token is accepted', async ({ playwright, request }) => {
    const token = await tokenFor(request, uid('cust'));
    const c = await playwright.request.newContext({ baseURL: URLS.order, extraHTTPHeaders: bearer(token) });
    expect((await c.get('/api/v1/products')).status()).toBe(200);
    await c.dispose();
  });
});

test.describe('authorization: customers are isolated from each other', () => {
  async function twoCustomers(playwright: any, request: any) {
    const alice = uid('alice'), bob = uid('bob');
    const as = async (who: string, admin = false) =>
      playwright.request.newContext({ baseURL: URLS.order, extraHTTPHeaders: bearer(await tokenFor(request, who, admin)) });
    return { alice, bob, a: await as(alice), b: await as(bob), admin: await as(uid('ops'), true) };
  }

  test('a customer sees only their own orders (others look nonexistent)', async ({ playwright, request }) => {
    const sku = await newSku(request, 10);
    const { alice, bob, a, b, admin } = await twoCustomers(playwright, request);
    const { id } = await (await placeOrder(a, sku, 1, alice)).json();

    expect((await a.get(`/api/v1/orders/${id}`)).status()).toBe(200);
    expect((await b.get(`/api/v1/orders/${id}`)).status()).toBe(404);          // not 403: ids cannot be probed
    expect((await admin.get(`/api/v1/orders/${id}`)).status()).toBe(200);
    expect((await b.get(`/api/v1/orders?customerId=${alice}`)).status()).toBe(403);
    expect((await a.get(`/api/v1/orders?customerId=${alice}`)).status()).toBe(200);
    expect((await admin.get(`/api/v1/orders?customerId=${alice}`)).status()).toBe(200);
    expect(await (await b.get(`/api/v1/orders?customerId=${bob}`)).json()).toHaveLength(0);
    await Promise.all([a.dispose(), b.dispose(), admin.dispose()]);
  });

  test('a customer cannot place an order on someone else’s behalf', async ({ playwright, request }) => {
    const sku = await newSku(request, 10);
    const { alice, b } = await twoCustomers(playwright, request);

    const r = await placeOrder(b, sku, 1, alice);

    expect(r.status()).toBe(403);
    expect(r.headers()['content-type']).toContain('application/problem+json');
    await b.dispose();
  });

  test('a customer cannot cancel someone else’s order', async ({ playwright, request }) => {
    const sku = await newSku(request, 10);
    const { alice, a, b } = await twoCustomers(playwright, request);
    const { id } = await (await placeOrder(a, sku, 1, alice)).json();
    await waitForStatus(a, id, /^CONFIRMED$/);

    expect((await b.post(`/api/v1/orders/${id}/cancel`)).status()).toBe(404);
    expect((await a.get(`/api/v1/orders/${id}`)).status()).toBe(200);
    expect((await (await a.get(`/api/v1/orders/${id}`)).json()).status).toBe('CONFIRMED');
    expect((await a.post(`/api/v1/orders/${id}/cancel`)).status()).toBe(202);
    await Promise.all([a.dispose(), b.dispose()]);
  });

  test('Idempotency-Key is scoped per customer: the same key from two customers makes two orders', async ({ playwright, request }) => {
    const sku = await newSku(request, 10);
    const { alice, bob, a, b } = await twoCustomers(playwright, request);
    const key = uid('shared');

    const ra = await placeOrder(a, sku, 1, alice, { 'Idempotency-Key': key });
    const rb = await placeOrder(b, sku, 1, bob, { 'Idempotency-Key': key });

    expect(ra.status()).toBe(202);
    expect(rb.status()).toBe(202);
    expect((await ra.json()).id).not.toBe((await rb.json()).id);
    await Promise.all([a.dispose(), b.dispose()]);
  });

  test('GraphQL enforces the same isolation', async ({ playwright, request }) => {
    const sku = await newSku(request, 10);
    const { alice, a, b, admin } = await twoCustomers(playwright, request);
    const { id } = await (await placeOrder(a, sku, 1, alice)).json();
    const gql = (c: any, query: string) => c.post('/graphql', { data: { query } }).then((r: any) => r.json());

    expect((await gql(a, `{ order(id:"${id}"){ id } }`)).data.order.id).toBe(id);
    expect((await gql(b, `{ order(id:"${id}"){ id } }`)).data.order).toBeNull();
    expect((await gql(admin, `{ order(id:"${id}"){ id } }`)).data.order.id).toBe(id);

    const list = await gql(b, `{ ordersByCustomer(customerId:"${alice}"){ id } }`);
    expect(list.errors[0].extensions.classification).toBe('FORBIDDEN');

    const place = await gql(b, `mutation { placeOrder(input:{customerId:"${alice}", items:[{sku:"${sku}", quantity:1}]}){ id } }`);
    expect(place.errors[0].extensions.classification).toBe('FORBIDDEN');

    const cancel = await gql(b, `mutation { cancelOrder(id:"${id}"){ id } }`);
    expect(cancel.errors[0].extensions.classification).toBe('NOT_FOUND');
    await Promise.all([a.dispose(), b.dispose(), admin.dispose()]);
  });

  test('another customer cannot subscribe to my order’s status stream', async ({ playwright, request, baseURL }) => {
    const sku = await newSku(request, 10);
    const { alice, a } = await twoCustomers(playwright, request);
    const { id } = await (await placeOrder(a, sku, 1, alice)).json();
    const mallory = await tokenFor(request, uid('mallory'));

    const res = await fetch(`${baseURL}/graphql`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...bearer(mallory) },
      body: JSON.stringify({ query: `subscription { orderStatusChanged(id:"${id}"){ status } }` }),
      signal: AbortSignal.timeout(15_000),
    });
    const text = await res.text();   // an error terminates the stream, so this returns
    expect(text).not.toMatch(/"status":"(PENDING|INVENTORY_RESERVED|CONFIRMED)"/);
    expect(text).toMatch(/NOT_FOUND|errors/);
    await a.dispose();
  });

  test('a confirmed order is still cancellable by its owner end-to-end (positive control)', async ({ request }) => {
    const sku = await newSku(request, 5);
    const order = await confirmedOrder(request, sku, 1);
    expect((await request.post(`/api/v1/orders/${order.id}/cancel`)).status()).toBe(202);
  });
});
