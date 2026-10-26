package com.datagami.rentaxis.core.service.report.grid;

import org.apache.poi.ss.usermodel.HorizontalAlignment;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The parts of PACT's Excel house style that differ between reports. Everything else (bands,
 * highlight, number and date formats, borders) is fixed in {@link XlsxGridSink}.
 *
 * @param headerFill the header row's fill, six hex digits ({@code 000080})
 * @param headerFont the header row's font colour, six hex digits
 * @param fontName   the font of every cell
 * @param fontPt     the data font size in points (bands and the title band size from it or fixed sizes)
 * @param moneyAlign how money, balance and percent cells align
 */
public record XlsxStyle(String headerFill, String headerFont, String fontName, short fontPt, HorizontalAlignment moneyAlign) {

    /** Declared before the profiles: their constructors read it during class initialisation. */
    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]{6}");

    /** PACT's default: navy header with white text, Calibri 9, money right-aligned. */
    public static final XlsxStyle PACT = new XlsxStyle("000080", "FFFFFF", "Calibri", (short) 9, HorizontalAlignment.RIGHT);

    /** PACT's Tenant Ledger: a white header with black text. */
    public static final XlsxStyle TENANT_LEDGER = new XlsxStyle("FFFFFF", "000000", "Calibri", (short) 9, HorizontalAlignment.RIGHT);

    public XlsxStyle {
        requireHex(headerFill, "header fill");
        requireHex(headerFont, "header font colour");
        if (fontName == null || fontName.isBlank()) {
            throw new IllegalArgumentException("An xlsx style needs a font name");
        }
        if (fontPt < 1) {
            throw new IllegalArgumentException("An xlsx style needs a positive font size, not " + fontPt);
        }
        Objects.requireNonNull(moneyAlign, "moneyAlign");
    }

    private static void requireHex(String hex, String what) {
        if (hex == null || !HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("The " + what + " must be six hex digits, not " + hex);
        }
    }
}
