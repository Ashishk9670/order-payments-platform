import { test, expect } from '@playwright/test';
import { newSku, placeOrder, uid, waitForTerminal } from '../../support/api';
import { catalogLoaded } from '../../support/ui';

/** The history table shows 5 orders a page; Previous/Next walk the pages and the status filter narrows them. */
test('order history is paged and filterable', async ({ page, request }) => {
  const sku = await newSku(request, 50);
  const customer = uid('pager');
  let last = '';
  for (let i = 0; i < 7; i++) {
    last = (await (await placeOrder(request, sku, 1, customer)).json()).id;
  }
  await waitForTerminal(request, last);

  await page.goto('/');
  await catalogLoaded(page);
  await page.getByTestId('customer-id').fill(customer);
  await page.getByTestId('refresh-history').click();

  const rows = page.getByTestId('history-row');
  await expect(rows).toHaveCount(5);
  await expect(page.getByTestId('history-page')).toHaveText('Page 1');
  await expect(page.getByTestId('history-prev')).toBeDisabled();
  await expect(page.getByTestId('history-next')).toBeEnabled();

  await page.getByTestId('history-next').click();
  await expect(rows).toHaveCount(2);                       // 7 orders: 5 + 2
  await expect(page.getByTestId('history-page')).toHaveText('Page 2');
  await expect(page.getByTestId('history-next')).toBeDisabled();
  await expect(page.getByTestId('history-prev')).toBeEnabled();

  await page.getByTestId('history-prev').click();
  await expect(rows).toHaveCount(5);
  await expect(page.getByTestId('history-page')).toHaveText('Page 1');

  await page.getByTestId('history-next').click();          // back on page 2, then filter: the page resets
  await page.getByTestId('history-filter').selectOption('CANCELLED');
  await expect(page.getByTestId('history-empty')).toBeVisible();
  await expect(page.getByTestId('history-page')).toHaveText('Page 1');
  await page.getByTestId('history-filter').selectOption('');
  await expect(rows).toHaveCount(5);
});
