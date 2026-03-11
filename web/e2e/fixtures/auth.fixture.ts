import { test as base } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

export type TestContext = {
  adminId: string;
  adminRole: string;
  adminTenantId: string;
  testTenantId: string;
  propertyId: string;
  unitId: string;
  renterId: string;
  leaseId: string;
  renterEmail: string;
  users: {
    superAdmin: { email: string; password: string };
    tenantAdmin: { email: string; password: string };
    propertyManager: { email: string; password: string };
    tenantUser: { email: string; password: string };
    renter: { email: string; password: string };
  };
};

const CONTEXT_PATH = path.join(__dirname, '..', '.test-context.json');

export const test = base.extend<{ testContext: TestContext }>({
  testContext: async ({}, use) => {
    const raw = fs.readFileSync(CONTEXT_PATH, 'utf-8');
    const ctx: TestContext = JSON.parse(raw);
    await use(ctx);
  },
});

export { expect } from '@playwright/test';
