/**
 * 13g — Payment gateway setup, secret masking, renter visibility, and deactivation.
 * Uses recognizable fake credentials and never calls the external provider.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin configures a test gateway without exposing stored secrets', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

  expect(await api.getActivePaymentGatewayConfig(taCtx)).toBeNull();

  const gateways = await api.getAvailablePaymentGateways(taCtx);
  expect(gateways.length).toBeGreaterThan(0);
  const gateway = gateways.find((item) => item.code === 'RAZORPAY') ?? gateways[0];
  expect(gateway.isActive).toBe(true);

  const fakeKey = `rzp_test_TESTE2E${ctx.runSuffix}`.replace(/[^A-Za-z0-9_]/g, '');
  const configured = await api.savePaymentGatewayConfig(taCtx, {
    gatewayId: gateway.id,
    apiKey: fakeKey,
    apiSecret: `TEST-E2E-secret-${ctx.runSuffix}`,
    webhookSecret: `TEST-E2E-webhook-${ctx.runSuffix}`,
    isActive: true,
    isTestMode: true,
  });
  expect(configured.gatewayId).toBe(gateway.id);
  expect(configured.apiKey).toBeNull();
  expect(configured.apiSecret).toBeNull();
  expect(configured.webhookSecret).toBeNull();
  expect(configured.apiKeyMasked).toBe(`${fakeKey.slice(0, 8)}****`);
  expect(configured.hasWebhookSecret).toBe(true);
  expect(configured.isTestMode).toBe(true);

  const adminRead = await api.getActivePaymentGatewayConfig(taCtx);
  expect(adminRead).toMatchObject({
    id: configured.id,
    apiKey: null,
    apiSecret: null,
    webhookSecret: null,
    apiKeyMasked: configured.apiKeyMasked,
  });

  const renterRead = await api.getActivePaymentGatewayConfig(renterCtx);
  expect(renterRead).toMatchObject({
    id: configured.id,
    apiKey: null,
    apiSecret: null,
    webhookSecret: null,
    apiKeyMasked: configured.apiKeyMasked,
  });

  // A masked key is a supported round-trip value: the service retains the
  // encrypted credentials while applying non-secret changes.
  const deactivated = await api.savePaymentGatewayConfig(taCtx, {
    gatewayId: gateway.id,
    apiKey: configured.apiKeyMasked,
    apiSecret: null,
    webhookSecret: null,
    isActive: false,
    isTestMode: true,
  });
  expect(deactivated.id).toBe(configured.id);
  expect(deactivated.isActive).toBe(false);
  expect(await api.getActivePaymentGatewayConfig(taCtx)).toBeNull();

  await taCtx.request.dispose();
  await renterCtx.request.dispose();
});
