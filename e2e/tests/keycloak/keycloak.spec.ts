import { test, expect, APIRequestContext } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { DEV_SECRET, signJwt } from '../../support/jwt';

/**
 * The production authentication path: tokens issued by a real identity provider (Keycloak), validated by signature against
 * its published keys, roles taken from the realm, customer id taken from preferred_username. The dev token endpoint is off.
 * Realm users (infra/keycloak/realm-orders.json): alice, bob (customers), ops (customer + admin).
 */
const PASSWORDS: Record<string, string> = { alice: 'alice-dev-password', bob: 'bob-dev-password', ops: 'ops-dev-password' };

async function login(request: APIRequestContext, user: string): Promise<string> {
  const r = await request.post(`${URLS.keycloak}/realms/orders/protocol/openid-connect/token`, {
    form: { client_id: 'storefront', grant_type: 'password', username: user, password: PASSWORDS[user] },
    headers: { Authorization: '' },
  });
  expect(r.ok(), `login ${user}: ${r.status()}`).toBeTruthy();
  return (await r.json()).access_token;
}

const as = (token: string) => ({ Authorization: `Bearer ${token}` });
const order = (customerId: string) => ({ customerId, items: [{ sku: 'SKU-1002', quantity: 1 }] });

test('the dev token endpoint is disabled in this mode', async ({ request }) => {
  const r = await request.post(`${URLS.order}/dev/token`, { data: { customerId: 'alice', admin: true } });
  expect([401, 404]).toContain(r.status());
});

test('a Keycloak token for alice places and reads her order; the customer id comes from preferred_username', async ({ request }) => {
  const alice = await login(request, 'alice');

  const placed = await request.post(`${URLS.order}/api/v1/orders`, { headers: as(alice), data: order('alice') });
  expect(placed.status()).toBe(202);
  const { id } = await placed.json();

  const got = await request.get(`${URLS.order}/api/v1/orders/${id}`, { headers: as(alice) });
  expect(got.status()).toBe(200);
  expect((await got.json()).customerId).toBe('alice');
});

test('bob cannot see, list or cancel alice’s orders; the ops admin can read them', async ({ request }) => {
  const [alice, bob, ops] = await Promise.all([login(request, 'alice'), login(request, 'bob'), login(request, 'ops')]);
  const { id } = await (await request.post(`${URLS.order}/api/v1/orders`, { headers: as(alice), data: order('alice') })).json();

  expect((await request.get(`${URLS.order}/api/v1/orders/${id}`, { headers: as(bob) })).status()).toBe(404);
  expect((await request.get(`${URLS.order}/api/v1/orders?customerId=alice`, { headers: as(bob) })).status()).toBe(403);
  expect((await request.post(`${URLS.order}/api/v1/orders/${id}/cancel`, { headers: as(bob) })).status()).toBe(404);
  expect((await request.post(`${URLS.order}/api/v1/orders`, { headers: as(bob), data: order('alice') })).status()).toBe(403);

  // the realm role "admin" is mapped to ROLE_ADMIN through realm_access.roles
  expect((await request.get(`${URLS.order}/api/v1/orders/${id}`, { headers: as(ops) })).status()).toBe(200);
  expect((await request.get(`${URLS.order}/api/v1/orders?customerId=alice`, { headers: as(ops) })).status()).toBe(200);
});

test('tokens that were not issued by Keycloak are rejected, even if well-formed and "admin"', async ({ request }) => {
  const claims = { sub: 'x', preferred_username: 'alice', realm_access: { roles: ['admin'] }, exp: Math.floor(Date.now() / 1000) + 600 };
  for (const bad of [signJwt(claims, DEV_SECRET), signJwt(claims, 'z'.repeat(40)), 'not.a.token']) {
    const r = await request.get(`${URLS.order}/api/v1/orders?customerId=alice`, { headers: as(bad) });
    expect(r.status()).toBe(401);
  }
  expect((await request.get(`${URLS.order}/api/v1/orders?customerId=alice`)).status()).toBe(401);
});

test('a Keycloak token works through the gateway, including the GraphQL endpoint', async ({ request }) => {
  const alice = await login(request, 'alice');
  const g = await request.post(`${URLS.gateway}/graphql`, {
    headers: as(alice), data: { query: '{ ordersByCustomer(customerId:"alice"){ id status } }' },
  });
  expect(g.status()).toBe(200);
  expect((await g.json()).errors).toBeUndefined();

  const forbidden = await request.post(`${URLS.gateway}/graphql`, {
    headers: as(alice), data: { query: '{ ordersByCustomer(customerId:"bob"){ id } }' },
  });
  expect((await forbidden.json()).errors[0].extensions.classification).toBe('FORBIDDEN');
});

test('internal APIs accept a Keycloak admin, refuse a Keycloak customer, and the order service reaches the catalog as a service account', async ({ request }) => {
  const [alice, ops] = await Promise.all([login(request, 'alice'), login(request, 'ops')]);
  const inventory = `${URLS.inventory}/api/v1/inventory/SKU-1002`;
  const catalog = `${URLS.catalog}/api/v1/products`;

  expect((await request.get(inventory)).status()).toBe(401);                       // no token
  expect((await request.get(inventory, { headers: as(alice) })).status()).toBe(403);  // customer
  expect((await request.get(inventory, { headers: as(ops) })).status()).toBe(200);    // operator (realm role "admin")
  expect((await request.get(catalog, { headers: as(alice) })).status()).toBe(403);
  expect((await request.get(catalog, { headers: as(ops) })).status()).toBe(200);

  // order placement needs prices: the order service fetched them from the catalog with a client-credentials token
  const placed = await request.post(`${URLS.order}/api/v1/orders`, { headers: as(alice), data: order('alice') });
  expect(placed.status()).toBe(202);
  expect((await placed.json()).totalAmount).toBeGreaterThan(0);
});
