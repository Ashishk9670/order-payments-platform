import { test, expect } from '@playwright/test';
import { newSku, placeOrder, tokenFor, bearer, uid, waitForTerminal } from '../../support/api';
import { URLS } from '../../playwright.config';

/** Listing a customer's orders: newest first, paged, filterable by status, with the total in X-Total-Count. */
test.describe('order listing: paging, filtering, total count', () => {
  test('pages are newest-first, disjoint, and X-Total-Count is the whole set', async ({ request, playwright }) => {
    const sku = await newSku(request, 50);
    const customer = uid('lister');
    const ids: string[] = [];
    for (let i = 0; i < 5; i++) {
      ids.push((await (await placeOrder(request, sku, 1, customer)).json()).id);   // sequential: a defined creation order
    }
    const mine = await playwright.request.newContext({
      baseURL: URLS.order, extraHTTPHeaders: bearer(await tokenFor(request, customer)),
    });

    const p0 = await mine.get(`/api/v1/orders?customerId=${customer}&page=0&size=2`);
    const p1 = await mine.get(`/api/v1/orders?customerId=${customer}&page=1&size=2`);
    const p2 = await mine.get(`/api/v1/orders?customerId=${customer}&page=2&size=2`);
    expect(p0.headers()['x-total-count']).toBe('5');
    const [a, b, c] = [await p0.json(), await p1.json(), await p2.json()];
    expect([a.length, b.length, c.length]).toEqual([2, 2, 1]);
    expect([...a, ...b, ...c].map((o: any) => o.id)).toEqual([...ids].reverse());   // newest first, nothing repeated or skipped
    await mine.dispose();
  });

  test('status filter narrows both the page and the total; an unknown status is a 400', async ({ request, playwright }) => {
    const sku = await newSku(request, 10);
    const customer = uid('filter');
    const ok = (await (await placeOrder(request, sku, 1, customer)).json()).id;
    const rejected = (await (await placeOrder(request, 'SKU-UNKNOWN-' + uid('x'), 1, customer)).json().catch(() => ({}))).id;
    await waitForTerminal(request, ok);
    const mine = await playwright.request.newContext({
      baseURL: URLS.order, extraHTTPHeaders: bearer(await tokenFor(request, customer)),
    });

    const confirmed = await mine.get(`/api/v1/orders?customerId=${customer}&status=CONFIRMED`);
    expect(confirmed.headers()['x-total-count']).toBe('1');
    expect((await confirmed.json()).map((o: any) => o.id)).toEqual([ok]);

    const cancelled = await mine.get(`/api/v1/orders?customerId=${customer}&status=CANCELLED`);
    expect(cancelled.headers()['x-total-count']).toBe('0');
    expect(await cancelled.json()).toEqual([]);

    expect((await mine.get(`/api/v1/orders?customerId=${customer}&status=NOPE`)).status()).toBe(400);
    void rejected;
    await mine.dispose();
  });

  test('GraphQL supports the same status filter and paging', async ({ request, playwright }) => {
    const sku = await newSku(request, 10);
    const customer = uid('gql');
    const id = (await (await placeOrder(request, sku, 1, customer)).json()).id;
    await waitForTerminal(request, id);
    const gql = await playwright.request.newContext({
      baseURL: URLS.order, extraHTTPHeaders: bearer(await tokenFor(request, customer)),
    });
    const query = (status: string | null, page = 0) => gql.post('/graphql', {
      data: { query: `query($c:String!,$s:OrderStatus,$p:Int){ ordersByCustomer(customerId:$c,status:$s,page:$p,size:5){ id status } }`,
              variables: { c: customer, s: status, p: page } },
    });

    expect((await (await query('CONFIRMED')).json()).data.ordersByCustomer.map((o: any) => o.id)).toEqual([id]);
    expect((await (await query('CANCELLED')).json()).data.ordersByCustomer).toEqual([]);
    expect((await (await query(null, 1)).json()).data.ordersByCustomer).toEqual([]);   // past the end is empty, not an error
    await gql.dispose();
  });
});
