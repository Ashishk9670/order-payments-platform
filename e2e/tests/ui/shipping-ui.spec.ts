import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { confirmedOrder, newSku, uid } from '../../support/api';
import { catalogLoaded } from '../../support/ui';

/** The customer's history shows where the parcel is. */
test('order history shows shipping progress', async ({ page, request }) => {
  const sku = await newSku(request, 5);
  const customer = uid('shipui');
  const { id } = await confirmedOrder(request, sku, 1, customer);
  await expect.poll(async () => (await request.get(`${URLS.shipping}/api/v1/shipments/order/${id}`)).status(), { timeout: 30_000 }).toBe(200);

  await page.goto('/');
  await catalogLoaded(page);
  await page.getByTestId('customer-id').fill(customer);
  await page.getByTestId('refresh-history').click();
  await expect(page.getByTestId('history-shipping')).toHaveText('-');   // confirmed, not shipped yet

  await request.post(`${URLS.shipping}/api/v1/shipments/order/${id}/ship`, { data: { carrier: 'DHL', trackingNumber: 'TRACK-UI-1' } });
  await expect(async () => {
    await page.getByTestId('refresh-history').click();
    await expect(page.getByTestId('history-shipping')).toHaveText('Shipped (DHL TRACK-UI-1)', { timeout: 1_500 });
  }).toPass({ timeout: 30_000 });

  await request.post(`${URLS.shipping}/api/v1/shipments/order/${id}/deliver`);
  await expect(async () => {
    await page.getByTestId('refresh-history').click();
    await expect(page.getByTestId('history-shipping')).toHaveText('Delivered', { timeout: 1_500 });
  }).toPass({ timeout: 30_000 });
});
