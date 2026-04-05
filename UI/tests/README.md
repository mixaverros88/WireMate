# WireMate UI Tests

End-to-end tests for the WireMate UI using Playwright.

## Setup

The tests require the dev server to be running on localhost:5173.

```bash
# Install Playwright (first time only)
npm install -D @playwright/test
npx playwright install chromium

# Run the dev server in one terminal
npm run dev

# In another terminal, run the tests
npx playwright test --headed
```

## Running Tests

**All tests in headed mode (visible browser):**
```bash
npx playwright test --headed
```

**Single test file in headed mode:**
```bash
npx playwright test tests/create-mock.spec.ts --headed
```

**Run with debugging:**
```bash
npx playwright test --headed --debug
```

**View test report:**
```bash
npx playwright show-report
```

## Test Files

- `create-mock.spec.ts` - Tests for the Create Mock form:
  - Fill and submit the form with realistic data
  - Validate required field handling
  - Test URL auto-formatting (adds leading slash)

## Notes

- All tests are configured to run single-worker with chromium browser for better observability
- Tests include strategic pauses (waitForTimeout) so you can watch the form being filled
- Form submission calls the real backend at `/api` — no mocking
- Success is verified via success toast or redirect to project detail page
