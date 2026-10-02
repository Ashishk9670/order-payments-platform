import { test, expect, Page } from '@playwright/test';
import { uid } from '../../support/api';

async function fill(page: Page, o: { customer: string; sku?: string; qty?: string; price?: string }) {
  await page.getByTestId('customer-id').fill(o.customer);
  if (o.sku) await page.getByTestId('sku').selectOption(o.sku);
  if (o.qty) await page.getByTestId('quantity').fill(o.qty);
  if (o.price) await page.getByTestId('unit-price').fill(o.price);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
});

test('page loads with the order form', async ({ page }) => {
  await expect(page).toHaveTitle('Order Platform');
  await expect(page.getByRole('heading', { name: 'Place an order' })).toBeVisible();
  await expect(page.getByTestId('place-order')).toBeEnabled();
});

test('placing a valid order ends CONFIRMED and appears in the GraphQL-backed history', async ({ page }) => {
  const customer = uid('ui');
  await fill(page, { customer, sku: 'SKU-1001', qty: '2', price: '10.00' });

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });
  await expect(page.getByTestId('order-total')).toHaveText('Total: 20.00');
  const id = (await page.getByTestId('order-id').textContent())!;

  await page.getByTestId('refresh-history').click();
  const row = page.getByTestId('history-row');
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(id.slice(0, 8));
  await expect(row).toContainText('2× SKU-1001');
  await expect(row).toContainText('CONFIRMED');
});

test('payment above the card limit ends CANCELLED with a payment reason', async ({ page }) => {
  await fill(page, { customer: uid('ui') });
  await page.getByTestId('preset-decline').click();
  await expect(page.getByTestId('unit-price')).toHaveValue('20000.00');

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CANCELLED', { timeout: 40_000 });
  await expect(page.getByTestId('order-reason')).toContainText('Payment');
});

test('unknown product ends CANCELLED with an inventory reason', async ({ page }) => {
  await fill(page, { customer: uid('ui'), sku: 'SKU-UNKNOWN', qty: '1', price: '5' });

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CANCELLED', { timeout: 40_000 });
  await expect(page.getByTestId('order-reason')).toContainText('Inventory');
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

test('history is empty for a customer with no orders', async ({ page }) => {
  await page.getByTestId('customer-id').fill(uid('nobody'));
  await page.getByTestId('refresh-history').click();
  await expect(page.getByTestId('history-empty')).toBeVisible();
  await expect(page.getByTestId('history-row')).toHaveCount(0);
});

test('double-click on Place order creates a single order (button disabled while in flight)', async ({ page }) => {
  const customer = uid('ui');
  await fill(page, { customer, sku: 'SKU-1001', qty: '1', price: '3' });

  await page.getByTestId('place-order').dblclick();
  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });

  await page.getByTestId('refresh-history').click();
  await expect(page.getByTestId('history-row')).toHaveCount(1);
});
