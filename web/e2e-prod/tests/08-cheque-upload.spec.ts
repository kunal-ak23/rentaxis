/**
 * 08 — Cheque image upload + OCR extraction.
 *
 * PROPERTY_MANAGER uploads a cheque image to /v1/cheques/extract. The
 * endpoint stores the image (returns blob path / URL metadata) and runs
 * OCR to extract cheque fields (cheque number, bank, amount, date).
 *
 * We use a tiny 1×1 PNG fixture — enough to verify the upload contract,
 * storage layer, and that the response shape is correct. Real OCR on a
 * 1×1 image will produce empty / warning-laden results; we accept that.
 * The smoke value is in proving the endpoint accepts the file and emits
 * a well-formed response, not in validating OCR accuracy.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');
const FIXTURE = path.join(__dirname, '..', 'fixtures', 'test-cheque.png');

test('cheque upload — accepts image, returns extraction response', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.pmEmail, '01-provision must have created a PROPERTY_MANAGER').toBeTruthy();
  expect(fs.existsSync(FIXTURE), 'test-cheque.png fixture must exist').toBeTruthy();

  // Cheque upload is allowed for any role except RENTER on the controller's
  // @PreAuthorize; PM is the realistic operator in the cheque-collection flow.
  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  const imageBytes = fs.readFileSync(FIXTURE);

  const res = await pctx.request.post('/api/proxy/v1/cheques/extract', {
    multipart: {
      file: {
        name: 'test-cheque.png',
        mimeType: 'image/png',
        buffer: imageBytes,
      },
    },
    failOnStatusCode: false,
  });

  // Two acceptable success paths:
  //   - 200 with a well-formed { image, extracted, warnings } body (happy)
  //   - 400/422 with an error message (if OCR rejects a 1×1 image outright
  //     — that's a product decision we shouldn't fail on, since we're not
  //     testing OCR quality)
  // Anything 5xx means the contract broke.
  expect(res.status(), `upload returned 5xx: ${res.status()}`).toBeLessThan(500);

  if (res.ok()) {
    const body = await res.json();
    expect(body, 'response should have image metadata').toHaveProperty('image');
    expect(body, 'response should have extracted fields (may be empty)').toHaveProperty('extracted');
    expect(Array.isArray(body.warnings), 'warnings should be an array').toBeTruthy();
  } else {
    // 4xx with a useful error message is also valid behavior for a 1×1 image.
    const body = await res.text();
    expect(body.length, 'error response should carry a message').toBeGreaterThan(0);
  }

  await pctx.request.dispose();
});
