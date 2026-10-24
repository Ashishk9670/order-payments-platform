import { test, expect } from '@playwright/test';
import { URLS } from '../../playwright.config';

// Only the application services expose /actuator/health. URLS also holds helper endpoints (the mock card processor, Keycloak)
// that exist only in opt-in modes, so they must not be iterated here.
const APP_SERVICES = ['gateway', 'order', 'inventory', 'payment', 'notification', 'catalog'] as const;

for (const name of APP_SERVICES) {
  test(`${name}-service reports UP`, async ({ request }) => {
    const r = await request.get(`${URLS[name]}/actuator/health`);
    expect(r.status()).toBe(200);
    expect((await r.json()).status).toBe('UP');
  });
}
