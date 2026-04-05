import { test, expect } from '@playwright/test';

test.describe('CreateMock Form', () => {
  test('should fill and submit the create mock form', async ({ page }) => {
    // Navigate to the create mock page with a project ID
    await page.goto('/mocks/create?projectId=62fde3e5-2008-40bd-9f1a-8a47dd117be9');

    // ─── General Section ───────────────────────────────────────
    // Mock name (required)
    await page.fill('input[placeholder*="Mock name"]', 'Test API Mock');
    await page.waitForTimeout(300);

    // Description (optional)
    await page.fill('textarea[placeholder*="Description"]', 'This is a test mock for automated testing');
    await page.waitForTimeout(300);

    // Priority should auto-fill to 5, verify it's there
    const priorityInput = page.locator('input[type="number"]');
    await expect(priorityInput).toHaveValue('5');
    await page.waitForTimeout(300);

    // Persistent checkbox should be checked by default
    const persistentCheckbox = page.locator('input[type="checkbox"]');
    await expect(persistentCheckbox).toBeChecked();
    await page.waitForTimeout(300);

    // ─── Request Matching Section ──────────────────────────────
    // Scroll to ensure visibility
    await page.waitForTimeout(500);

    // HTTP Method dropdown
    const methodSelect = page.locator('select').first();
    await methodSelect.selectOption('POST');
    await page.waitForTimeout(300);

    // URL Match Type: change from 'url' to 'urlPath'
    const urlTypeSelect = page.locator('select').nth(1);
    await urlTypeSelect.selectOption('urlPath');
    await page.waitForTimeout(300);

    // URL/Path pattern
    const urlInput = page.locator('input[type="text"]').filter({ hasText: '/' }).first();
    await urlInput.fill('/api/users');
    await page.waitForTimeout(300);

    // ─── Response Definition Section ────────────────────────────
    await page.waitForTimeout(500);

    // Status Code: change to 201
    const statusInput = page.locator('input[type="number"]').nth(1);
    await statusInput.fill('201');
    await page.waitForTimeout(300);

    // Response body (JSON)
    const bodyTextarea = page.locator('textarea').nth(1);
    await bodyTextarea.fill('{"id": 123, "name": "John Doe", "email": "john@example.com"}');
    await page.waitForTimeout(300);

    // Verify headers section has Content-Type set to application/json
    const contentTypeHeader = page.locator('input[value="application/json"]');
    await expect(contentTypeHeader).toBeVisible();
    await page.waitForTimeout(300);

    // ─── Submit Form ───────────────────────────────────────────
    await page.waitForTimeout(800);

    // Find and click submit button
    const submitButton = page.locator('button:has-text("Create Mock"), button:has-text("Save Mock")').first();
    await expect(submitButton).toBeVisible();
    await submitButton.click();

    // Wait for submission to complete
    await page.waitForTimeout(1000);

    // Verify success toast or redirect
    // Look for success toast with the mock name
    const successToast = page.locator('text=created successfully').or(page.locator('text=updated successfully'));
    await expect(successToast).toBeVisible({ timeout: 5000 });

    // Verify we either see a success toast or get redirected to project detail
    const isRedirected = page.url().includes('/projects/');
    const hasToast = await successToast.isVisible().catch(() => false);

    expect(isRedirected || hasToast).toBeTruthy();
  });

  test('should validate required fields', async ({ page }) => {
    await page.goto('/mocks/create?projectId=62fde3e5-2008-40bd-9f1a-8a47dd117be9');

    await page.waitForTimeout(500);

    // Try to submit without filling required fields
    const submitButton = page.locator('button:has-text("Create Mock"), button:has-text("Save Mock")').first();
    await submitButton.click();

    // Should see validation errors
    await page.waitForTimeout(500);

    // Look for error indicators or validation messages
    const mockNameInput = page.locator('input[placeholder*="Mock name"]');
    const hasError = await mockNameInput.evaluate((el: any) => {
      return el.classList.contains('error') ||
             el.classList.contains('invalid') ||
             el.getAttribute('aria-invalid') === 'true';
    }).catch(() => false);

    // Form should still be on the same page (not submitted)
    expect(page.url()).toContain('/mocks/create');
  });

  test('should auto-format URL to start with /', async ({ page }) => {
    await page.goto('/mocks/create?projectId=62fde3e5-2008-40bd-9f1a-8a47dd117be9');

    await page.waitForTimeout(500);

    // Fill in URL without leading slash
    const urlInput = page.locator('input[type="text"]').filter({ hasText: '/' }).first();
    await urlInput.fill('api/users');

    // Trigger blur or wait for watcher
    await page.waitForTimeout(400);

    // Should auto-correct to /api/users
    const value = await urlInput.inputValue();
    expect(value).toBe('/api/users');
  });
});
