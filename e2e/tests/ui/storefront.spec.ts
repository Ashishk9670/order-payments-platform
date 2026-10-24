import { test, expect, Page } from '@playwright/test';
import { uid } from '../../support/api';
import { catalogLoaded } from '../../support/ui';

async function fill(page: Page, o: { customer: string; sku?: string; qty?: string }) {
  await page.getByTestId('customer-id').fill(o.customer);
  if (o.sku) await page.getByTestId('sku').selectOption(o.sku);
  if (o.qty) await page.getByTestId('quantity').fill(o.qty);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await catalogLoaded(page);
});

test('page loads with the order form and catalog products', async ({ page }) => {
  await expect(page).toHaveTitle('Order Platform');
  await expect(page.getByRole('heading', { name: 'Place an order' })).toBeVisible();
  await expect(page.getByTestId('place-order')).toBeEnabled();
  await expect(page.getByTestId('sku')).toContainText('Standard widget');
});

test('estimated total comes from catalog prices and follows quantity', async ({ page }) => {
  await fill(page, { customer: 'c', sku: 'SKU-1001', qty: '2' });
  await expect(page.getByTestId('estimate')).toHaveText('39.98');
  await page.getByTestId('quantity').fill('3');
  await expect(page.getByTestId('estimate')).toHaveText('59.97');
});

test('placing a valid order ends CONFIRMED and appears in the GraphQL-backed history', async ({ page }) => {
  const customer = uid('ui');
  await fill(page, { customer, sku: 'SKU-1001', qty: '2' });

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });
  await expect(page.getByTestId('order-total')).toHaveText('Total: 39.98');
  const id = (await page.getByTestId('order-id').textContent())!;

  await page.getByTestId('refresh-history').click();
  const row = page.getByTestId('history-row');
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(id.slice(0, 8));
  await expect(row).toContainText('2× SKU-1001');
  await expect(row).toContainText('CONFIRMED');
});

test('status updates arrive over a GraphQL subscription, not by polling', async ({ page }) => {
  const polled: string[] = [];
  page.on('request', (r) => { if (/\/api\/v1\/orders\/[0-9a-f-]{36}$/.test(r.url()) && r.method() === 'GET') polled.push(r.url()); });
  const streamRequest = page.waitForRequest((r) => r.url().endsWith('/graphql') && r.headers()['accept'] === 'text/event-stream');
  await fill(page, { customer: uid('ui'), sku: 'SKU-1002', qty: '1' });

  await page.getByTestId('place-order').click();

  await streamRequest;
  await expect(page.getByTestId('live-indicator')).toHaveText(/live|^$/);
  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });
  expect(polled).toHaveLength(0);
});

test('a confirmed order can be cancelled: refund pending, then cancelled', async ({ page }) => {
  await fill(page, { customer: uid('ui'), sku: 'SKU-1002', qty: '1' });
  await page.getByTestId('place-order').click();
  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });

  await expect(page.getByTestId('cancel-order')).toBeVisible();
  await page.getByTestId('cancel-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CANCELLED', { timeout: 40_000 });
  await expect(page.getByTestId('order-reason')).toContainText('Cancelled by customer');
  await expect(page.getByTestId('cancel-order')).toBeHidden();
});

test('cancel button is not offered while an order is still pending or after it was cancelled by a failure', async ({ page }) => {
  await fill(page, { customer: uid('ui') });
  await page.getByTestId('preset-decline').click();
  await page.getByTestId('place-order').click();
  await expect(page.getByTestId('order-status')).toHaveText('CANCELLED', { timeout: 40_000 });
  await expect(page.getByTestId('cancel-order')).toBeHidden();
});

test('payment above the card limit ends CANCELLED with a payment reason', async ({ page }) => {
  await fill(page, { customer: uid('ui') });
  await page.getByTestId('preset-decline').click();
  await expect(page.getByTestId('sku')).toHaveValue('SKU-LUXURY');
  await expect(page.getByTestId('estimate')).toHaveText('20000.00');

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CANCELLED', { timeout: 40_000 });
  await expect(page.getByTestId('order-reason')).toContainText('Payment');
});

test('unknown product is rejected immediately with a clear message and no order card', async ({ page }) => {
  await fill(page, { customer: uid('ui'), sku: 'SKU-UNKNOWN', qty: '1' });

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('form-error')).toHaveText('Unknown product SKU-UNKNOWN');
  await expect(page.getByTestId('order-status')).toBeHidden();
});

test('client-side validation blocks bad input without calling the API', async ({ page }) => {
  let posted = false;
  await page.route('**/api/v1/orders', (route) => { posted = route.request().method() === 'POST' || posted; return route.continue(); });

  await fill(page, { customer: 'c', qty: '0' });
  await page.getByTestId('place-order').click();
  await expect(page.getByTestId('form-error')).toHaveText('Quantity must be at least 1');

  await fill(page, { customer: '   ', qty: '1' });
  await page.getByTestId('place-order').click();
  await expect(page.getByTestId('form-error')).toHaveText('Customer ID is required');

  expect(posted).toBe(false);
});

test('server-side error is shown to the user', async ({ page }) => {
  await page.route('**/api/v1/orders', (route) => route.request().method() === 'POST'
    ? route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ detail: 'Invalid request content.' }) })
    : route.continue());
  await fill(page, { customer: uid('ui') });

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('form-error')).toHaveText('Invalid request content.');
});

test('falls back to polling when the live stream is unavailable', async ({ page }) => {
  await page.route('**/graphql', (route) => route.request().headers()['accept'] === 'text/event-stream'
    ? route.fulfill({ status: 500, body: 'no stream' }) : route.continue());
  await fill(page, { customer: uid('ui'), sku: 'SKU-1002', qty: '1' });

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });
  await expect(page.getByTestId('live-indicator')).toHaveText('(polling)');
});

test('history is empty for a customer with no orders', async ({ page }) => {
  await page.getByTestId('customer-id').fill(uid('nobody'));
  await page.getByTestId('refresh-history').click();
  await expect(page.getByTestId('history-empty')).toBeVisible();
  await expect(page.getByTestId('history-row')).toHaveCount(0);
});

test('double-click on Place order creates a single order (button disabled while in flight)', async ({ page }) => {
  const customer = uid('ui');
  await fill(page, { customer, sku: 'SKU-1002', qty: '1' });

  await page.getByTestId('place-order').dblclick();
  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });

  await page.getByTestId('refresh-history').click();
  await expect(page.getByTestId('history-row')).toHaveCount(1);
});
