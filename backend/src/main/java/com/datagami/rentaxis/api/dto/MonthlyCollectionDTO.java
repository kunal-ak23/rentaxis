package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;

/**
 * One month of the dashboard "collection vs expected" series.
 *
 * @param month     short month label, e.g. "Jun"
 * @param ym        year-month key, e.g. "2026-06" (stable for sorting/keys)
 * @param expected  total scheduled amount due in the month (by dueDate)
 * @param collected total amount received against that month's dues
 *                  (status COLLECTED / DEPOSITED / CLEARED)
 */
public record MonthlyCollectionDTO(String month, String ym, BigDecimal expected, BigDecimal collected) {
}
