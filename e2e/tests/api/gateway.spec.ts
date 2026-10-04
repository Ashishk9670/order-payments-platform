import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { bearer, newSku, tokenFor, uid } from '../../support/api';

/** The public entry point: routing, internal APIs hidden, request ids, per-caller rate limiting. */
test.describe('api gateway', () => {
  const gw = (playwright: any, headers: Record<string, string> = {}) =>
    playwright.request.newContext({ baseURL: URLS.gateway, extraHTTPHeaders: headers });

  test('reports UP', async ({ playwright }) => {
    const c = await gw(playwright);
    expect((await (await c.get('/actuator/health')).json()).status).toBe('UP');
    await c.dispose();
  });

  test('serves the storefront and routes the order API, GraphQL and catalog read-through', async ({ playwright, request }) => {
    const token = await tokenFor(request, uid('cust'));
    const c = await gw(playwright, bearer(token));
    expect(await (await c.get('/')).text()).toContain('Order Platform');
    expect((await c.get('/api/v1/products')).status()).toBe(200);
    const g = await c.post('/graphql', { data: { query: '{ order(id:"00000000-0000-0000-0000-000000000000"){ id } }' } });
    expect((await g.json()).data.order).toBeNull();
    await c.dispose();
  });

  test('internal services are not reachable through the gateway', async ({ playwright }) => {
    const c = await gw(playwright);
    for (const path of ['/api/v1/inventory/SKU-1001', '/api/v1/payments/order/x', '/actuator/env']) {
      expect((await c.get(path)).status(), path).toBe(404);
    }
    expect((await c.put('/api/v1/catalog/HACK', { data: { name: 'x', price: 1 } })).status()).toBe(404);
    await c.dispose();
  });

  test('token validation still happens (unauthenticated calls are rejected end-to-end)', async ({ playwright }) => {
    const c = await gw(playwright);
    const r = await c.get('/api/v1/orders/00000000-0000-0000-0000-000000000000');
    expect(r.status()).toBe(401);
    expect(r.headers()['content-type']).toContain('application/problem+json');
    await c.dispose();
  });

  test('every response carries an X-Request-Id; a sane one from the client is preserved', async ({ playwright }) => {
    const c = await gw(playwright);
    const generated = (await c.get('/')).headers()['x-request-id'];
    expect(generated).toMatch(/^[0-9a-f-]{36}$/);
    const echoed = (await c.get('/', { headers: { 'X-Request-Id': 'e2e-trace-12345678' } })).headers()['x-request-id'];
    expect(echoed).toBe('e2e-trace-12345678');
    await c.dispose();
  });

  test('full order flow works through the gateway, including the SSE status stream', async ({ playwright, request }) => {
    const sku = await newSku(request, 5);
    const customer = uid('cust');
    const token = await tokenFor(request, customer);
    const c = await gw(playwright, bearer(token));

    const placed = await c.post('/api/v1/orders', { data: { customerId: customer, items: [{ sku, quantity: 1 }] } });
    expect(placed.status()).toBe(202);
    const { id } = await placed.json();

    const res = await fetch(`${URLS.gateway}/graphql`, {
      method: 'POST', signal: AbortSignal.timeout(30_000),
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...bearer(token) },
      body: JSON.stringify({ query: `subscription { orderStatusChanged(id:"${id}"){ status } }` }),
    });
    expect(res.status).toBe(200);
    const body = await res.text();   // completes when the order reaches a terminal state
    expect(body).toContain('"status":"CONFIRMED"');
    await c.dispose();
  });

  test('rate limiting is per caller: a flooding client is throttled while others are unaffected', async ({ playwright, request }) => {
    const flooder = await gw(playwright, { Authorization: `Bearer flood-${uid('x')}` });   // key = hash of this header
    const results = await Promise.all(Array.from({ length: 400 }, () => flooder.get('/api/v1/products')));
    const codes = results.map((r) => r.status());

    expect(codes).toContain(429);
    expect(codes.some((c) => c !== 429)).toBe(true);
    const limited = results.find((r) => r.status() === 429)!;
    expect(limited.headers()['x-ratelimit-remaining']).toBe('0');

    const polite = await gw(playwright, bearer(await tokenFor(request, uid('polite'))));
    expect((await polite.get('/api/v1/products')).status()).toBe(200);
    await Promise.all([flooder.dispose(), polite.dispose()]);
  });
});
