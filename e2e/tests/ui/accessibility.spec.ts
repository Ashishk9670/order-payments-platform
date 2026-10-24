import AxeBuilder from '@axe-core/playwright';
import { devices, expect, test } from '@playwright/test';
import { uid } from '../../support/api';
import { catalogLoaded } from '../../support/ui';

test.describe('accessibility (WCAG 2.1 A/AA)', () => {
  test('the storefront has no automatically detectable violations', async ({ page }) => {
    await page.goto('/');
    await catalogLoaded(page);

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

    expect(results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`)).toEqual([]);
  });

  test('still clean after an order result is shown (status badge, reason, cancel button)', async ({ page }) => {
    await page.goto('/');
    await catalogLoaded(page);
    await page.getByTestId('customer-id').fill(uid('a11y'));
    await page.getByTestId('preset-decline').click();
    await page.getByTestId('place-order').click();
    await expect(page.getByTestId('order-status')).toHaveText('CANCELLED', { timeout: 40_000 });

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

    expect(results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`)).toEqual([]);
  });

  test('the dark theme also meets contrast requirements', async ({ browser }) => {
    const context = await browser.newContext({ colorScheme: 'dark' });
    const page = await context.newPage();
    await page.goto('/');
    await catalogLoaded(page);

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

    expect(results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`)).toEqual([]);
    await context.close();
  });

  test('every form control has an accessible name', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('textbox', { name: 'Customer ID' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Product' })).toBeVisible();
    await expect(page.getByRole('spinbutton', { name: 'Quantity' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Place order' })).toBeVisible();
  });

  test('the whole purchase flow works with the keyboard only', async ({ page }) => {
    await page.goto('/');
    await catalogLoaded(page);
    await page.getByTestId('customer-id').fill(uid('kbd'));

    await page.getByTestId('customer-id').press('Tab');        // -> product
    await page.keyboard.press('ArrowDown');                    // pick another product with the keyboard
    await page.keyboard.press('Tab');                          // -> quantity
    await page.keyboard.press('Tab');                          // -> Place order
    await expect(page.getByTestId('place-order')).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page.getByTestId('order-status')).toHaveText(/CONFIRMED|CANCELLED/, { timeout: 40_000 });
  });

  test('errors are announced to assistive technology (role=alert)', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('quantity').fill('0');
    await page.getByTestId('place-order').click();

    await expect(page.getByRole('alert')).toHaveText('Quantity must be at least 1');
  });

  test('the order result is a live region so status changes are announced', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#result-card')).toHaveAttribute('aria-live', 'polite');
  });
});

// defaultBrowserType cannot be set per describe-group (it would force a new worker); everything else can
const { defaultBrowserType: _ignored, ...pixel5 } = devices['Pixel 5'];

test.describe('responsive layout', () => {
  test.use(pixel5);   // 393x727 touch device, Chromium

  test('no horizontal scrolling and the form is usable on a phone', async ({ page }) => {
    await page.goto('/');
    await catalogLoaded(page);

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await expect(page.getByTestId('place-order')).toBeInViewport({ ratio: 0.5 });
  });

  test('an order can be placed on a phone-sized screen', async ({ page }) => {
    await page.goto('/');
    await catalogLoaded(page);
    await page.getByTestId('customer-id').fill(uid('mobile'));
    await page.getByTestId('sku').selectOption('SKU-1002');

    await page.getByTestId('place-order').tap();

    await expect(page.getByTestId('order-status')).toHaveText('CONFIRMED', { timeout: 40_000 });
    await expect(page.getByTestId('order-total')).toBeInViewport();
  });

  test('the history table does not break the layout on a narrow screen', async ({ page }) => {
    await page.goto('/');
    const box = await page.locator('table').boundingBox();
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 1);
  });
});
