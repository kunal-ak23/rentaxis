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
