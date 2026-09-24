package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The one door an uploaded workbook comes through, and the limits it has to clear.
 *
 * <p><b>Why a door at all.</b> A spreadsheet upload is an untrusted file parsed
 * in-process by a library that builds the whole thing in memory. If it exhausts
 * the heap it does not fail the import job — it kills the JVM, and with it every
 * other request the service was serving. That risk grew when the cut-over import
 * raised the size cap from 5 MB to 10 MB and opened the endpoint to a third role,
 * so the limits are stated here, in one place, for both importers.</p>
 *
 * <p><b>What POI gives for free, and what it does not.</b> Its XML factories are
 * configured against XXE and external entities, and this codebase never evaluates
 * a formula — {@link SheetCells#getCellString} reads the cached value, and no
 * {@code FormulaEvaluator} exists anywhere in the tree — so there is no
 * expression engine and no external-link resolution to attack. What POI's
 * defaults do <em>not</em> cover is scale: a minimum inflate ratio of 0.01 admits
 * a hundredfold expansion, which turns a 10 MB upload into a gigabyte of heap,
 * and nothing at all caps how many rows a sheet may claim.</p>
 *
 * <p><b>The limits, and why each one is where it is.</b></p>
 * <ul>
 *   <li>{@link #MIN_INFLATE_RATIO} 0.05 — five times stricter than POI's default.
 *       A 10 MB upload can now expand to about 200 MB rather than about 1 GB.
 *       It is a ratio rather than a size so that an honest, highly repetitive
 *       workbook is judged on the same scale as a hostile one.</li>
 *   <li>{@link #MAX_ENTRY_BYTES} 64 MB — no single part of the archive (a sheet's
 *       XML, the shared-string table) can be larger than this. A cut-over workbook
 *       of a thousand contracts is a few megabytes.</li>
 *   <li>{@link #MAX_TEXT_BYTES} 32 MB — the shared-string table specifically,
 *       which is the part a text bomb inflates.</li>
 *   <li>{@link #MAX_BYTE_ARRAY_BYTES} 128 MB — POI's own allocation ceiling, so
 *       a corrupt length field cannot ask for an array the size of the heap.</li>
 *   <li>{@link #MAX_ROWS_PER_SHEET} 50,000 and {@link #MAX_TOTAL_CELLS} 2,000,000
 *       — the business ceiling rather than the security one. A 600-contract
 *       cut-over is a few thousand rows; a workbook past this is a mistake, and
 *       saying so beats iterating it.</li>
 * </ul>
 *
 * <p>Macro-enabled and encrypted workbooks are refused outright: neither is
 * something an import needs, and both are ways of handing the server a file it
 * was not asked to understand.</p>
 */
public final class WorkbookGuard {

    /** Stricter than POI's 0.01 default: a 10 MB upload may expand to ~200 MB, not ~1 GB. */
    public static final double MIN_INFLATE_RATIO = 0.05;

    public static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    public static final long MAX_TEXT_BYTES = 32L * 1024 * 1024;
    /**
     * POI's own allocation ceiling.
     *
     * <p>128 MB rather than something tighter because POI asks for 100 MB of its
     * own accord while opening a perfectly ordinary workbook — a 64 MB override
     * refused the cut-over template itself, which is how that number was found.
     * What this closes is the unbounded case: a corrupt length field asking for an
     * array the size of the heap. The ratio and entry-size caps above are what
     * actually bound a hostile file.</p>
     */
    public static final int MAX_BYTE_ARRAY_BYTES = 128 * 1024 * 1024;

    /** A 600-contract cut-over is a few thousand rows. */
    public static final int MAX_ROWS_PER_SHEET = 50_000;
    public static final int MAX_TOTAL_CELLS = 2_000_000;

    /** A .xlsx is a zip; this is the local file header every one of them starts with. */
    private static final byte[] ZIP_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };

    /** The part that makes an .xlsm an .xlsm. */
    private static final String MACRO_PART = "vbaproject.bin";

    /** OOXML encryption wraps the real package in an OLE2 container under this name. */
    private static final String ENCRYPTED_PART = "encryptedpackage";

    static {
        applyLimits();
    }

    private WorkbookGuard() {
    }

    /**
     * Applies the process-wide POI limits. Idempotent, called from the static
     * initialiser, and exposed so a test can assert the process really is
     * configured rather than assert the constants agree with themselves.
     */
    public static void applyLimits() {
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_TEXT_BYTES);
        IOUtils.setByteArrayMaxOverride(MAX_BYTE_ARRAY_BYTES);
    }

    /** True when these bytes begin like a zip, which is what an .xlsx is. */
    public static boolean looksLikeXlsx(byte[] bytes) {
        if (bytes == null || bytes.length < ZIP_MAGIC.length) return false;
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * Open an uploaded workbook, or refuse it with a sentence.
     *
     * <p>Every refusal is a {@link BusinessRuleViolationException} carrying one
     * plain sentence, so the caller can put it on the job as a validation failure
     * rather than letting it surface as a 500 or, worse, as an OOM.</p>
     */
    public static Workbook open(byte[] bytes) {
        if (!looksLikeXlsx(bytes)) {
            throw new BusinessRuleViolationException(
                    "Only .xlsx workbooks are supported; this file is not one");
        }
        refuseMacrosAndEncryption(bytes);

        Workbook workbook;
        try {
            workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            // POI's own limits land here — an over-inflating entry, an entry past
            // MAX_ENTRY_BYTES, a truncated archive. One sentence, never a stack trace.
            throw new BusinessRuleViolationException(
                    "This workbook could not be read: " + rootMessage(e));
        }

        try {
            refuseOversizedSheets(workbook);
        } catch (RuntimeException e) {
            closeQuietly(workbook);
            throw e;
        }
        return workbook;
    }

    /**
     * Neither a macro nor an encrypted package belongs in an import, and both are
     * ways of handing the parser something it was not asked to understand.
     *
     * <p>Read from the archive's local headers rather than by opening the workbook,
     * so it is decided before POI allocates anything.</p>
     */
    private static void refuseMacrosAndEncryption(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 512) {
                    throw new BusinessRuleViolationException(
                            "This workbook has an unreasonable number of internal parts");
                }
                String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(MACRO_PART)) {
                    throw new BusinessRuleViolationException(
                            "Macro-enabled workbooks (.xlsm) are not accepted; save it as .xlsx and upload again");
                }
                if (name.contains(ENCRYPTED_PART)) {
                    throw new BusinessRuleViolationException(
                            "This workbook is password-protected; remove the protection and upload again");
                }
            }
        } catch (IOException e) {
            throw new BusinessRuleViolationException("This file is not a readable .xlsx workbook");
        }
    }

    /**
     * The business ceiling. Checked after opening because that is when the sheets
     * exist; the security ceiling above is what stops an open from being dangerous.
     */
    private static void refuseOversizedSheets(Workbook workbook) {
        long cells = 0;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            int rows = sheet.getLastRowNum() + 1;
            if (rows > MAX_ROWS_PER_SHEET) {
                // Worded for every importer that comes through this door — the
                // chart of accounts and the v1 portfolio as well as the cut-over.
                throw new BusinessRuleViolationException("Sheet '" + sheet.getSheetName() + "' has "
                        + rows + " rows, more than the " + MAX_ROWS_PER_SHEET
                        + " this import accepts; split it into smaller workbooks");
            }
            // Every row's own span, not rows × the first row's width (PR #353 review
            // P1-1): a sheet whose first row is one cell and whose other rows each hold
            // a cell at column XFD is 20,000 real cells but 330 million addressable
            // ones, and a reader that walks each row to getLastCellNum allocates them all.
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                cells += Math.max(row.getLastCellNum(), 1);
                if (cells > MAX_TOTAL_CELLS) break;
            }
            if (cells > MAX_TOTAL_CELLS) {
                throw new BusinessRuleViolationException("This workbook holds more than "
                        + MAX_TOTAL_CELLS + " cells; split it into smaller workbooks");
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static void closeQuietly(Workbook workbook) {
        try {
            workbook.close();
        } catch (IOException ignored) {
            // already refusing; the original refusal is what the caller needs
        }
    }
}
