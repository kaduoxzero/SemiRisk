import { test, expect } from '@playwright/test';

test('GIS globe renders non-empty WebGL canvas', async ({ page }) => {
  const csrfResp = await page.request.get('http://localhost:8080/api/auth/csrf');
  const csrf = (await csrfResp.json()).data.token;
  const loginResp = await page.request.post('http://localhost:8080/api/auth/login', {
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
    data: { username: 'kaduoxli', password: '123qwe123', captchaToken: 'vue-slider-ok' }
  });
  const login = await loginResp.json();
  const session = { ...login.data.user, token: login.data.token, expiresAt: login.data.expiresAt };

  await page.goto('http://localhost:8080/dashboard', { waitUntil: 'networkidle' });
  await page.evaluate(value => localStorage.setItem('semiriskUser', JSON.stringify(value)), session);
  await page.goto('http://localhost:8080/gis', { waitUntil: 'networkidle' });
  await page.waitForSelector('.globe-canvas canvas', { timeout: 20000 });
  await page.waitForTimeout(1600);

  const metrics = await page.locator('.globe-canvas canvas').evaluate(canvas => {
    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
    if (!gl) return { hasContext: false, nonBlack: 0, max: 0 };
    const width = canvas.width;
    const height = canvas.height;
    const sampleW = Math.min(80, width);
    const sampleH = Math.min(80, height);
    const x = Math.max(0, Math.floor(width / 2 - sampleW / 2));
    const y = Math.max(0, Math.floor(height / 2 - sampleH / 2));
    const pixels = new Uint8Array(sampleW * sampleH * 4);
    gl.readPixels(x, y, sampleW, sampleH, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
    let nonBlack = 0;
    let max = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      const r = pixels[i];
      const g = pixels[i + 1];
      const b = pixels[i + 2];
      if (r + g + b > 24) nonBlack += 1;
      max = Math.max(max, r, g, b);
    }
    return { hasContext: true, width, height, sampleW, sampleH, nonBlack, max };
  });

  await page.screenshot({ path: '/tmp/semirisk-gis-desktop.png', fullPage: false });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('http://localhost:8080/gis', { waitUntil: 'networkidle' });
  await page.waitForSelector('.globe-canvas canvas', { timeout: 20000 });
  await page.waitForTimeout(1000);
  await page.screenshot({ path: '/tmp/semirisk-gis-mobile.png', fullPage: false });

  console.log(JSON.stringify(metrics));
  expect(metrics.hasContext).toBe(true);
  expect(metrics.nonBlack).toBeGreaterThan(100);
  expect(metrics.max).toBeGreaterThan(40);
});
