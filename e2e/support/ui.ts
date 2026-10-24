import { expect, Page } from '@playwright/test';

/**
 * The product dropdown is filled by JavaScript after /api/v1/products answers, and starts with zero options, so a check such as
 * "option count is not 1" passes immediately. Wait for a real seeded product instead, otherwise a slow load resets the
 * selection after the test has already interacted with the form.
 */
export async function catalogLoaded(page: Page) {
  await expect(page.locator('[data-testid=sku] option[value="SKU-1001"]')).toBeAttached();
  await expect(page.locator('[data-testid=sku] option[value="SKU-LUXURY"]')).toBeAttached();
}
