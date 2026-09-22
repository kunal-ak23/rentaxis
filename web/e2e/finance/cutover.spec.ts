import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures/auth.fixture';
import { buildXlsx } from '../../walkthrough/minimal-xlsx';

/**
 * Cut-over smoke — the accounting-v2 plan 4 contract import end to end, through
 * the UI: open the books, upload a workbook that is refused whole, upload the
 * corrected one, post the batch it made, and reverse it again.
 *
 * TENANT_ADMIN only, for the same reason `ledger.spec.ts` skips the other roles:
 * `ImportBatchController` and `PortfolioImportController`'s cut-over routes admit
 * SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT, and the suite's other role projects
 * would assert against an Access Denied card.
 *
 * Serial, because it is one story: the books-start date is a one-shot fact about
 * an organisation (`TenantFiscalSettingsService.setBooksStartDate` refuses a
 * change once an opening-balance journal is live), and the batch has to exist
 * before it can be posted.
 *
 * **The Properties sheet names no accounts.** Every account column is blank, so
 * the property's own template fills all six roles — which is the shape of a
 * first cut-over into an empty chart, and it keeps this spec from having to
 * scrape generated account names off another screen. A blank column is a
 * warning, never an error (`ContractImportValidator`).
 *
 * The full feature — per-line VAT, the opening-balance grid, reconciliation
 * against PACT's trial balance, the hand-derived balances, discard — is covered
 * scenario by scenario with a disposable tenant in
 * `walkthrough/accounting-v2-plan4.spec.ts`.
 */

/** Unique per run: this spec creates records and the dev DB is not reset between runs. */
const SUFFIX = Math.random().toString(36).slice(2, 7);
const PROPERTY = `E2E Cut-over Tower ${SUFFIX}`;
const UNITS = [`CO-${SUFFIX}-1`, `CO-${SUFFIX}-2`];
const RENTER_EMAIL = `e2e-cutover-${SUFFIX}@example.invalid`;
const CONTRACT_REF = `E2E-${SUFFIX}-C1`;

/**
 * The books open on 1 January of this year, so the imported contract and every
 * journal the bulk post writes for it sit in the period that is closed by
 * construction — which is the exemption a cut-over depends on.
 */
const YEAR = new Date().getFullYear();
const BOOKS_START = `${YEAR}-01-01`;
const CONTRACT_DATE = `${YEAR - 1}-11-15`;
const TERM_START = `${YEAR - 1}-12-01`;
const TERM_END = `${YEAR}-11-30`;

async function suppressTour(page: Page) {
    await page.addInitScript(() => {
        try {
            window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding']));
        } catch {
            /* storage unavailable — nothing to suppress */
        }
    });
}

/** The five sheets `PortfolioTemplateService.generateCutOverTemplate` emits. */
function workbook(opts: { bankAccountName?: string; duplicateNumber?: boolean }) {
    return buildXlsx([
        {
            name: 'Properties',
            rows: [
                ['PropertyName', 'PropertyNameAr', 'Emirate', 'Address', 'Type', 'MakaniNumber',
                    'RentalIncomeAccount', 'RentalReceivableAccount', 'AdvanceRentAccount',
                    'BankAccount', 'PdcReceivableAccount', 'SecurityDepositAccount'],
                [PROPERTY, '', 'DUBAI', '1 Cut-over Street, Dubai', 'RESIDENTIAL', '',
                    '', '', '', opts.bankAccountName ?? '', '', ''],
            ],
        },
        {
            name: 'Units',
            rows: [
                ['PropertyName', 'BuildingName', 'UnitNumber', 'UnitType', 'SizeSqft', 'ExpectedRent'],
                ...UNITS.map(u => [PROPERTY, '', u, 'BHK1', '900', '48000']),
            ],
        },
        {
            name: 'Renters',
            rows: [
                ['Name', 'NameAr', 'Email', 'Phone'],
                [`E2E Cut-over Renter ${SUFFIX}`, '', RENTER_EMAIL, '+971500000004'],
            ],
        },
        {
            name: 'Contracts',
            rows: [
                ['ContractNumber', 'EjariNumber', 'PropertyName', 'BuildingName', 'UnitNumber', 'RenterEmail',
                    'ContractDate', 'StartDate', 'EndDate', 'GracePeriodDays', 'LineNo', 'ChargeTypeCode',
                    'CreditAccount', 'GrossAmount', 'DiscountAmount', 'VatApplicable', 'Narration'],
                [CONTRACT_REF, `EJ-${SUFFIX}`, PROPERTY, '', UNITS[0], RENTER_EMAIL,
                    CONTRACT_DATE, TERM_START, TERM_END, '5', '1', 'RENT', '', '48000.00', '0', 'false', 'Annual rent'],
                ...(opts.duplicateNumber
                    // A second, different contract under the first one's number:
                    // `ContractImportValidator.HEADER_COLUMNS` refuses it by name.
                    ? [[CONTRACT_REF, '', PROPERTY, '', UNITS[1], RENTER_EMAIL,
                        CONTRACT_DATE, TERM_START, TERM_END, '5', '2', 'RENT', '', '24000.00', '0', 'false', 'Second']]
                    : []),
            ],
        },
        {
            name: 'Cheques',
            rows: [
                ['ContractNumber', 'SeqNo', 'PostingDate', 'ChequeNumber', 'ChequeDate', 'PayeeBank',
                    'DebitAccount', 'Amount', 'Narration', 'Mode', 'Status', 'DepositedDate', 'ClearedDate', 'BouncedDate'],
                [CONTRACT_REF, '1', CONTRACT_DATE, `${SUFFIX}01`, TERM_START, 'E2E Bank', '', '48000.00',
                    'Annual rent', 'PDC', 'REGISTERED', '', '', ''],
            ],
        },
    ]);
}

const upload = (name: string, buffer: Buffer) => ({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer,
});

test.describe('Cut-over import', () => {
    test.describe.configure({ mode: 'serial' });

    test.beforeEach(async ({ page }, testInfo) => {
        test.skip(testInfo.project.name !== 'tenant-admin', 'TENANT_ADMIN owns the cut-over pages');
        await suppressTour(page);
    });

    test('opens the books on a date the cut-over can post before', async ({ page }) => {
        await page.goto('/en/dashboard/settings/fiscal');
        const booksStart = page.locator('#fiscal-books-start');
        await expect(booksStart).toBeVisible({ timeout: 20_000 });
        if ((await booksStart.inputValue()) !== BOOKS_START) {
            await booksStart.fill(BOOKS_START);
            await page.getByRole('button', { name: 'Save', exact: true }).click();
        }
        await expect(booksStart).toHaveValue(BOOKS_START);
        // Setting it closes everything before it, which is the whole point.
        await expect(page.getByText(`${YEAR - 1}-12-31`).first()).toBeVisible({ timeout: 20_000 });
    });

    test('refuses a workbook whole and writes nothing', async ({ page }) => {
        await page.goto('/en/dashboard/finance/import-batches');
        await expect(page.getByTestId('download-template')).toBeVisible({ timeout: 20_000 });

        await page.getByTestId('upload-cutover').setInputFiles(
            upload('e2e-cutover-bad.xlsx', workbook({ bankAccountName: 'Bank Of Nowhere', duplicateNumber: true })),
        );
        await expect(page.getByTestId('import-job-status'))
            .toHaveAttribute('data-status', 'VALIDATION_FAILED', { timeout: 60_000 });
        await expect(page.getByTestId('import-errors-table'))
            .toContainText("No ledger account is named 'Bank Of Nowhere'");
        await expect(page.getByTestId('import-errors-table'))
            .toContainText('A second contract needs its own number');
        // One transaction: a workbook that does not validate leaves no batch.
        await expect(page.locator('[data-testid^="batch-row-"]').filter({ hasText: 'e2e-cutover-bad' }))
            .toHaveCount(0);
    });

    test('imports the corrected workbook as a draft batch', async ({ page }) => {
        await page.goto('/en/dashboard/finance/import-batches');
        await page.getByTestId('upload-cutover').setInputFiles(
            upload('e2e-cutover.xlsx', workbook({})),
        );
        await expect(page.getByTestId('import-job-status'))
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 60_000 });
        await expect(page.getByTestId('import-success')).toBeVisible();

        const row = page.locator('[data-testid^="batch-row-"][data-imported="true"]');
        await expect(row).toBeVisible({ timeout: 20_000 });
        await expect(row.locator('[data-testid^="batch-status-"]')).toHaveAttribute('data-status', 'DRAFT');
    });

    test('posts the batch and then reverses it', async ({ page }) => {
        await page.goto('/en/dashboard/finance/import-batches');
        const row = page.locator('tr').filter({ hasText: 'e2e-cutover.xlsx' }).first();
        await expect(row).toBeVisible({ timeout: 20_000 });
        const batchId = (await row.getAttribute('data-testid'))?.replace('batch-row-', '') ?? '';
        expect(batchId, 'the imported batch must have a row of its own').not.toBe('');

        await page.getByTestId(`post-batch-${batchId}`).click();
        await page.getByTestId('confirm-post-batch').click();
        await expect(page.getByTestId('post-job-status'))
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 120_000 });
        await expect(page.getByTestId('post-summary')).toContainText('Posted 1');
        await expect(page.getByTestId('post-result-0')).toHaveAttribute('data-outcome', 'POSTED');
        await expect(page.getByTestId(`batch-status-${batchId}`))
            .toHaveAttribute('data-status', 'POSTED', { timeout: 20_000 });

        await page.getByTestId(`reverse-batch-${batchId}`).click();
        await page.getByTestId('batch-reverse-reason').fill('e2e');
        await page.getByTestId('confirm-reverse-batch').click();
        await expect(page.getByTestId('batch-reversed-banner')).toBeVisible({ timeout: 60_000 });
        await expect(page.getByTestId(`batch-status-${batchId}`)).toHaveAttribute('data-status', 'REVERSED');
        // A reversed batch keeps its contracts, so the way back is Post again.
        await expect(page.getByTestId(`discard-batch-${batchId}`)).toHaveCount(0);
        await expect(page.getByTestId(`post-batch-${batchId}`)).toContainText('Post again');
    });
});
