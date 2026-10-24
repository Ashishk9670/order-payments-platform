import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';
import { uid } from '../../support/api';
import { catalogLoaded } from '../../support/ui';

test('the storefront works end-to-end through the gateway (REST + GraphQL + SSE)', async ({ page }) => {
  await page.goto(`${URLS.gateway}/`);
  await catalogLoaded(page);
  await page.getByTestId('customer-id').fill(uid('gw'));
  await page.getByTestId('sku').selectOption('SKU-1002');

  await page.getByTestId('place-order').click();

  await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });
  await expect(page.getByTestId('live-indicator')).not.toHaveText('(polling)');
  await page.getByTestId('refresh-history').click();
  await expect(page.getByTestId('history-row')).toHaveCount(1);
});
