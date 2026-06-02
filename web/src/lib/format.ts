/**
 * Currency and number formatting utilities for RentAxis.
 * UAE standard: AED 1,234.56
 */

const aedFormatter = new Intl.NumberFormat('en-AE', {
    style: 'currency',
    currency: 'AED',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

const aedCompactFormatter = new Intl.NumberFormat('en-AE', {
    style: 'currency',
    currency: 'AED',
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
});

/**
 * Format a number as AED currency.
 * Returns "AED 1,234.56" — standard UAE format with 2 decimal places.
 */
export function formatCurrency(value: number | null | undefined): string {
    if (value == null || isNaN(value)) return 'AED 0.00';
    return aedFormatter.format(value);
}

/**
 * Format a number as AED currency without decimals.
 * Returns "AED 1,235" — for dashboard KPIs and summary cards.
 */
export function formatCurrencyCompact(value: number | null | undefined): string {
    if (value == null || isNaN(value)) return 'AED 0';
    return aedCompactFormatter.format(value);
}

/**
 * Format an ISO date (YYYY-MM-DD, optionally with a time component) as DD/MM/YYYY
 * to match the native date-input display used across the app. Parsed by string
 * split to avoid timezone shifts from `new Date()`.
 * Returns "" for null/empty, and the original string if it isn't ISO-shaped.
 */
export function formatDate(value: string | null | undefined): string {
    if (!value) return '';
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
    if (!m) return value;
    return `${m[3]}/${m[2]}/${m[1]}`;
}

/**
 * Format a number with 2 decimal places (no currency symbol).
 * Returns "1,234.56"
 */
export function formatNumber(value: number | null | undefined): string {
    if (value == null || isNaN(value)) return '0.00';
    return value.toLocaleString('en-AE', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    });
}
