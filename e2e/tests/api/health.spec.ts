import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';

for (const [name, url] of Object.entries(URLS)) {
  test(`${name}-service reports UP`, async ({ request }) => {
    const r = await request.get(`${url}/actuator/health`);
    expect(r.status()).toBe(200);
    expect((await r.json()).status).toBe('UP');
  });
}
