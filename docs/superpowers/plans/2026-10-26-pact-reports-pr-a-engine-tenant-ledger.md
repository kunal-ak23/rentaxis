# PACT Report Parity — PR A: Shared Report Engine + R1 Tenant Ledger — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared report engine (a BigDecimal grid writer feeding JSON, PACT-layout Excel, PDF and CSV sinks) plus the web report kit and Reports hub, with PACT's Tenant Ledger (R1) as its first report.

**Architecture:** A report service reads its rows once, inside a read-only transaction, into immutable records (native SQL, `tenant_id` bound on every table). After the transaction a feeder replays those records as `ReportWriter` events; the writer does every sum, running balance and subtotal and forwards events to one sink per format, so the screen, Excel, PDF and CSV cannot disagree. Screen paging is by tenant groups, ≤ 200 rows a page, with SQL-aggregate ("authoritative") totals the writer cross-checks whenever a group or the whole set was written.

**Tech Stack:** Java 21 / Spring Boot 4.0.3 / NamedParameterJdbcTemplate / Postgres 16 / Apache POI 5.3.0 (`SXSSFWorkbook`) / openhtmltopdf 1.1.37 / JUnit 5 + Testcontainers (`AbstractPostgresIT`); Next.js 16 + TypeScript strict + next-intl (EN/AR) + Tailwind 4; vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-26-pact-reports-design.md` — §1 (shared engine) and §2 (R1 Tenant Ledger); decisions D1–D9 in §9 are binding. Resolutions of `maps/critique.md` §2a are applied (one `ChequeReturnClass`, authoritative totals, one ordering, numeric balance cell, `LedgerRowDTO` untouched, one xlsx writer with style profiles, real date cells, `/api/v1/finance/reports/<key>` + one `.{format}` handler, one labels bundle, materialise then render).

**Branch:** `feat/pact-reports` (already checked out in the working clone). One PR.

## Global Constraints

- Client PACT files stay in `~/Downloads`; no client name, unit code or amount in code, tests, fixtures, docs or commit messages. Tests use synthetic data ("Sample Tower", "Tenant A", "Tenant B").
- Tenant isolation is P0: every new native query binds `tenant_id` on **every** table it touches (`journal_lines`, `journal_entries`, `accounts`, `renters`, `cheques`, `properties`, `leases`, `landlord_org` by id). A named filter id that does not resolve in the caller's tenant is a 404.
- Report services are class-level `@Transactional(readOnly = true, timeout = 60)`; all name/DTO mapping happens inside; sinks render after the transaction from immutable records. Never `StreamingResponseBody`, never `@Async`.
- Reports never call `PostingService`. `LedgerRowDTO`, `JournalLineRepository.ledgerRows`, `LedgerQueryService` are not modified.
- One ledger ordering everywhere: `entry_date, created_at, entry_number, line_no`.
- Caps (D8): screen page ≤ 200 rows (`Search.MAX_PAGE_SIZE`), Excel/CSV ≤ 50,000 rows, PDF ≤ 5,000 rows. Above a cap: HTTP 422, code `report.tooManyRowsPdf` ("Too many rows for a PDF — export Excel or narrow the filter") or `report.tooManyRows` ("narrow the filter").
- Excel (D2/D3): row 1 merged title band (org, title, period/filters, `Generated dd/MM/yyyy HH:mm by <user>`), row 2 headers, data from row 3; money numeric `#,##0.00`; balance **numeric** with `#,##0.00"Dr";#,##0.00"Cr";0.00"Dr"`; real date cells in the column's PACT pattern; identifiers are text cells; Calibri 9 thin borders; default header navy `#000080`/white, Tenant Ledger profile white/black; bands ACCOUNT `#D2691E`/`#F0F8FF` bold 14, PARTY `#FFFF00` 13 pt, GROUP `#7F7F7F` bold; HIGHLIGHT `#FAC08F`.
- Dr/Cr (D4) on the new screens and exports in both languages: `12,345.00Dr`, `8,000.00Cr`, `0.00Dr` — no space, Latin digits. Existing `fmtBalance` screens are not changed.
- R1 Excel columns, PACT order: Doc Date (`dd-MM-yyyy`) · Particular · Debit · Credit · Balance · Chq No · Payee Bank · BDDays · ConvertedDate (`dd/MM/yyyy`) · Doc No (appended as column J).
- Access (D6): R1 is SA, TA, ACCOUNTANT. New endpoints are org-required (`OrganisationRequiredInterceptor`); do not touch `SUPER_ADMIN_PLATFORM_READS`.
- Endpoints live under `/api/v1/finance/reports/<key>` with exactly one `/<key>.{format:xlsx|pdf|csv}` file handler; explicit `@PreAuthorize` on every method; no generic `{key}` dispatcher.
- Labels: one bundle `backend/src/main/resources/messages/reports_{en,ar}.properties`. No apostrophes in bundle values (MessageFormat).
- D9: no export audit, no saved presets. No Liquibase changeset in this PR (next free number stays **176**).
- Web: calls go through `/api/proxy/v1`; EN/AR key parity; RTL; at 390 px no page-level horizontal scroll; no client arithmetic on report amounts (amounts arrive as decimal strings).
- Commits: `git add <new files>` then `git commit -m "<conventional subject>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- <every path of the task>`. Never `git add -A`, never a bare `git commit`.
- Gradle: before every `./gradlew` run, check `pgrep -f "[G]radleWrapperMain|[G]radleWorkerMain"`; while it prints a PID another build is running — wait and re-check; never kill it.

## Review Focus

1. **A tenant group longer than one screen page** (a "No tenant" group of hundreds of manual JVs, or a multi-year window): the page must end at 200 rows and the next page must open with "(continued)" bands and a *carried-forward* balance equal to the last balance shown — never a reset to the brought-forward figure → `GroupPagerTest.splitsAGroupLargerThanAPage` (Task 2) and `TenantLedgerReportServiceIT.pagesSplitAGroupAndCarryTheBalanceForward` (Task 7).
2. **A reversal straddling `from`** (original before the window, mirror inside it) with "Hide reversed pairs": both legs leave the opening *and* the rows, and the closing balance is identical with the switch on or off → `TenantLedgerReportServiceIT.hideReversedDropsBothLegsAcrossTheWindowEdge` (Task 7).
3. **An empty result** (a property with no receivable lines, or every group filtered by "only with a balance"): JSON shows the empty state, and every file format still renders a valid document with a zero REPORT TOTAL — no 500, no zero-byte file → `ReportWriterTest.anEmptyReportStillHasAReportTotal` (Task 2), `XlsxGridSinkTest.anEmptyGridIsAValidWorkbook` (Task 3), `FinanceRegisterReportControllerIT.anEmptySelectionExportsZeroTotals` (Task 8).
4. **Same-day ordering ties** (several PDRs and a JV on one date): the tenant ledger's running balances must equal the General Ledger's row for row for a one-tenant account → `TenantLedgerReportServiceIT.runningBalancesMatchTheAccountLedgerRowForRow` (Task 7).
5. **User-typed text that looks like a formula or carries bidi controls** (renter "=Tenant", narration "@SUM(1)", RLM marks): stays a plain string in xlsx (quote-prefixed), apostrophe-escaped in CSV, escaped in PDF, never a formula or hyperlink → `XlsxGridSinkTest.formulaLookingTextStaysAString`, `CsvGridSinkTest.guardsFormulas` (Tasks 3, 4).

---

## File structure

Backend (`backend/src/main/java/com/datagami/rentaxis/…`):
- `core/service/report/grid/` — the engine: `Kind`, `TotalRule`, `Band`, `RowStyle`, `ReportColumn`, `ReportFilter`, `ReportMeta`, `GridHeader`, `GroupTotals`, `ReportSink`, `ReportWriter`, `GroupPager`, `JsonGridSink`, `XlsxStyle`, `XlsxGridSink`, `PdfGridSink`, `CsvGridSink`, `ReportFormat`, `ReportLabels`, `ExportFormat`, `ReportCaps`, `ReportFiles`, `GridRenderer`.
- `core/service/report/ReportPdf.java` — upgraded (thead, page x/y, bands, org header, auto-landscape).
- `core/service/report/tenantledger/` — `TenantLedgerFilter`, `TenantLedgerQueries`, `TenantLedgerResult`, `TenantLedgerRules`, `TenantLedgerReportService`, `TenantLedgerGrid`.
- `core/service/cheque/ChequeReturnClass.java`.
- `core/security/PropertyScope.java` — gains `requireSelection`.
- `api/FinanceRegisterReportController.java`; `api/dto/report/ReportGridDTO.java`, `api/dto/report/ReceivableAccountDTO.java`; `api/exception/ReportTooLargeException.java`; `api/GlobalExceptionHandler.java` (422 handler).
- `resources/messages/reports_en.properties`, `reports_ar.properties`.

Web (`web/src/…`):
- `lib/reports/format.ts`, `lib/reports/catalog.ts`, `lib/api/reports.ts`.
- `components/reports/ReportShell.tsx`, `ExportMenu.tsx`, `GroupedReportTable.tsx`.
- `app/[locale]/dashboard/reports/page.tsx` (hub).
- `app/[locale]/dashboard/finance/tenant-ledger/page.tsx` + `_components/{AllAccountsView,ReceivableView,TenantLedgerFilters,query}.ts(x)`.
- `app/globals.css` tokens; `messages/{en,ar}.json` (`Reports` namespace, `AccountingNav.reportsHub`).
- nav/registry/help/rbac edits.

---

### Task 1: `ReportFormat` and the `ReportLabels` bundle

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/report/grid/ReportFormat.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/report/grid/ReportLabels.java`
- Create: `backend/src/main/resources/messages/reports_en.properties`, `backend/src/main/resources/messages/reports_ar.properties`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/report/grid/ReportFormatTest.java`, `ReportLabelsTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class ReportFormat {
      public static final List<String> DATE_PATTERNS = List.of("dd/MM/yyyy", "dd-MM-yyyy", "dd/MMM/yyyy");
      public static String money(BigDecimal v);              // null → ""; "#,##0.00", HALF_UP, Latin digits
      public static String drCr(BigDecimal v);               // null → ""; "12,345.00Dr" / "8,000.00Cr" / "0.00Dr"
      public static String day(LocalDate d, String pattern); // null → ""; Locale.ENGLISH (Latin digits, "Apr")
      public static String stamp(ZonedDateTime t);           // "dd/MM/yyyy HH:mm" in Asia/Dubai
      public static String duration(LocalDate from, LocalDate to); // PACT spelling, both ends inclusive
      public static String cellText(String s);               // strips control + bidi chars, truncates at 32,767
      public static String sheetName(String s);              // ≤ 31 chars, no []:*?/\, no edge apostrophes, blank → "Report"
  }
  @Component public class ReportLabels {
      public ReportLabels();                                  // builds its own ReloadableResourceBundleMessageSource
      public static String lang(String raw);                  // "ar" → "ar", anything else → "en"
      public String get(String key, String lang, Object... args);
      public Bound bind(String lang);
      public record Bound(ReportLabels labels, String lang) { public String get(String key, Object... args); }
  }
  ```
- The bundle is **not** a Spring `MessageSource` bean: `EmailMessageSourceConfig` registers its source under the alias `messageSource` and a second bean of that type would make by-type injection ambiguous. `ReportLabels` owns a private `ReloadableResourceBundleMessageSource` (`classpath:messages/reports`, UTF-8, `setFallbackToSystemLocale(false)`, `setUseCodeAsDefaultMessage(false)`); locale `ar` for "ar", `Locale.ENGLISH` otherwise. A missing key throws `NoSuchMessageException`.

- [ ] **Step 1: Write the failing tests**

```java
class ReportFormatTest {
    @Test void drCrIsPactsForm() {
        assertThat(ReportFormat.drCr(new BigDecimal("12345"))).isEqualTo("12,345.00Dr");
        assertThat(ReportFormat.drCr(new BigDecimal("-8000"))).isEqualTo("8,000.00Cr");
        assertThat(ReportFormat.drCr(BigDecimal.ZERO)).isEqualTo("0.00Dr");
        assertThat(ReportFormat.drCr(new BigDecimal("-0.004"))).isEqualTo("0.00Dr");
        assertThat(ReportFormat.money(new BigDecimal("1234567.891"))).isEqualTo("1,234,567.89");
        assertThat(ReportFormat.money(null)).isEmpty();
    }
    @Test void daysUseLatinDigitsAndEnglishMonths() {
        LocalDate d = LocalDate.of(2027, 4, 2);
        assertThat(ReportFormat.day(d, "dd-MM-yyyy")).isEqualTo("02-04-2027");
        assertThat(ReportFormat.day(d, "dd/MM/yyyy")).isEqualTo("02/04/2027");
        assertThat(ReportFormat.day(d, "dd/MMM/yyyy")).isEqualTo("02/Apr/2027");
        assertThat(ReportFormat.stamp(ZonedDateTime.parse("2026-10-26T06:05:00Z"))).isEqualTo("26/10/2026 10:05");
    }
    @Test void durationIsPactsSpellingWithTheTrailingSpace() {
        assertThat(ReportFormat.duration(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEqualTo("1 Years ");
        assertThat(ReportFormat.duration(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 31))).isEqualTo("1 Years 1 Months ");
        assertThat(ReportFormat.duration(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 8))).isEqualTo("1 Years 8 Days");
        assertThat(ReportFormat.duration(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15))).isEqualTo("1 Days");
    }
    @Test void cellTextStripsControlAndBidiAndTruncates() {
        assertThat(ReportFormat.cellText("A‏b\u0007c‮d")).isEqualTo("Abcd");
        assertThat(ReportFormat.cellText("x".repeat(40_000))).hasSize(32_767);
        assertThat(ReportFormat.cellText(null)).isEmpty();
    }
    @Test void sheetNamesAreExcelSafe() {
        assertThat(ReportFormat.sheetName("Tenant [Ledger]: 2026/27?")).isEqualTo("Tenant Ledger 202627");
        assertThat(ReportFormat.sheetName("x".repeat(40))).hasSize(31);
        assertThat(ReportFormat.sheetName("  ")).isEqualTo("Report");
    }
}

class ReportLabelsTest {
    private static Properties load(String lang) throws IOException {
        Properties p = new Properties();
        try (var in = ReportLabelsTest.class.getResourceAsStream("/messages/reports_" + lang + ".properties")) {
            p.load(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
        }
        return p;
    }
    @Test void englishAndArabicHaveTheSameKeysAndNoBlankValue() throws IOException {
        Properties en = load("en"), ar = load("ar");
        assertThat(ar.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
        en.forEach((k, v) -> assertThat((String) v).as("en " + k).isNotBlank());
        ar.forEach((k, v) -> assertThat((String) v).as("ar " + k).isNotBlank());
    }
    @Test void resolvesPactsExactEnglishHeadersAndArabic() {
        ReportLabels l = new ReportLabels();
        assertThat(l.get("col.chqNo", "en")).isEqualTo("Chq No");
        assertThat(l.get("col.bdDays", "en")).isEqualTo("BDDays");
        assertThat(l.get("col.convertedDate", "en")).isEqualTo("ConvertedDate");
        assertThat(l.get("tenantLedger.tenantBand", "en", "Tenant A")).isEqualTo("Tenant Name : Tenant A");
        assertThat(l.get("col.docDate", "ar")).isEqualTo("تاريخ المستند");
        assertThat(l.get("col.docDate", "fr")).isEqualTo("Doc Date"); // unknown lang reads English
        assertThatThrownBy(() -> l.get("no.such.key", "en")).isInstanceOf(NoSuchMessageException.class);
    }
}
```

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.report.grid.ReportFormatTest' --tests 'com.datagami.rentaxis.core.service.report.grid.ReportLabelsTest'` → FAIL: compilation errors (`ReportFormat`, `ReportLabels` not found).

- [ ] **Step 3: Implement** both classes and the two bundles. Rules the tests do not show:
  - `money`: `DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT))`, value `setScale(2, HALF_UP)`; `drCr`: scale first, then `signum() >= 0 ? money + "Dr" : money(abs) + "Cr"`.
  - `duration`: `Period p = Period.between(from, to.plusDays(1))`; output `"<y> Years "` if y>0, then `"<m> Months "` if m>0, then `"<d> Days"` if d>0 (so the trailing space survives when days is 0). Both 0 → `""`.
  - `cellText`: remove `[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F‎‏‪-‮⁦-⁩]`; keep `\t \n \r`.
  - `sheetName`: delete `[]:*?/\`, collapse runs of spaces, trim, strip leading/trailing `'`, cut to 31.
  - Bundle keys (EN value | AR value), identical key sets in both files:

  | key | en | ar |
  |---|---|---|
  | `report.generated` | `Generated {0} by {1}` | `أُنشئ في {0} بواسطة {1}` |
  | `report.period` | `{0} – {1}` | `{0} – {1}` |
  | `report.asOf` | `As of {0}` | `كما في {0}` |
  | `report.subTotal` | `Sub Total` | `المجموع الفرعي` |
  | `report.reportTotal` | `REPORT TOTAL` | `إجمالي التقرير` |
  | `report.broughtForward` | `Balance brought forward` | `الرصيد المرحّل` |
  | `report.carriedForward` | `Balance carried forward` | `الرصيد المنقول` |
  | `report.continued` | `{0} (continued)` | `{0} (تابع)` |
  | `report.drCr` | `Dr/Cr` | `Dr/Cr` |
  | `report.yes` | `Yes` | `نعم` |
  | `report.tooManyRowsPdf` | `Too many rows for a PDF ({0} rows, the limit is {1}) - export Excel or narrow the filter` | `عدد الصفوف كبير على ملف PDF ({0} صفاً والحد {1}) - صدّر إلى Excel أو ضيّق عامل التصفية` |
  | `report.tooManyRows` | `Too many rows ({0} rows, the limit is {1}) - narrow the filter` | `عدد الصفوف كبير ({0} صفاً والحد {1}) - ضيّق عامل التصفية` |
  | `filter.property` | `Property` | `العقار` |
  | `filter.accounts` | `Accounts` | `الحسابات` |
  | `filter.allReceivable` | `All receivable accounts` | `كل حسابات الذمم المدينة` |
  | `filter.tenant` | `Tenant` | `المستأجر` |
  | `filter.contract` | `Contract` | `العقد` |
  | `filter.onlyWithBalance` | `Only tenants with a balance` | `المستأجرون ذوو الرصيد فقط` |
  | `filter.hideReversed` | `Reversed pairs hidden` | `القيود المعكوسة مخفية` |
  | `col.docDate` | `Doc Date` | `تاريخ المستند` |
  | `col.particular` | `Particular` | `البيان` |
  | `col.debit` | `Debit` | `مدين` |
  | `col.credit` | `Credit` | `دائن` |
  | `col.balance` | `Balance` | `الرصيد` |
  | `col.chqNo` | `Chq No` | `رقم الشيك` |
  | `col.payeeBank` | `Payee Bank` | `بنك المستفيد` |
  | `col.bdDays` | `BDDays` | `حالة الارتجاع` |
  | `col.convertedDate` | `ConvertedDate` | `تاريخ الاستحقاق` |
  | `col.docNo` | `Doc No` | `رقم المستند` |
  | `tenantLedger.title` | `Tenant Ledger` | `دفتر أستاذ المستأجر` |
  | `tenantLedger.tenantBand` | `Tenant Name : {0}` | `اسم المستأجر : {0}` |
  | `tenantLedger.noTenant` | `No tenant` | `بدون مستأجر` |
  | `tenantLedger.accountSubTotal` | `Sub Total ({0})` | `المجموع الفرعي ({0})` |

- [ ] **Step 4: Run** the Step 2 command → PASS.
- [ ] **Step 5: Commit** `feat(reports): ReportFormat and the reports EN/AR label bundle` (paths: the two classes, two bundles, two tests).

---

### Task 2: Grid model, `ReportWriter`, `GroupPager`, `JsonGridSink` / `ReportGridDTO`

**Files:**
- Create in `backend/src/main/java/com/datagami/rentaxis/core/service/report/grid/`: `Kind.java`, `TotalRule.java`, `Band.java`, `RowStyle.java`, `ReportColumn.java`, `ReportFilter.java`, `ReportMeta.java`, `GridHeader.java`, `GroupTotals.java`, `ReportSink.java`, `ReportWriter.java`, `GroupPager.java`, `JsonGridSink.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/report/ReportGridDTO.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/report/grid/ReportWriterTest.java`, `GroupPagerTest.java`, `JsonGridSinkTest.java`, plus test helpers `RecordingSink.java` and `SampleGrid.java` (reused by Tasks 3–5). `RecordingSink` keeps: `bands()` as `"<level>|<band>|<label>|<continued>"`, `openings()` as `"<level>|<label>|<balance.stripTrailingZeros().toPlainString()>|<carriedForward>"`, `rowBalances()` (the BALANCE cell of each row), `subtotal(level)` (the first subtotal map at that level), `total()`, and the `end` flags.

**Interfaces:**
- Consumes: `ReportFormat`, `ReportLabels.Bound` (Task 1).
- Produces:
  ```java
  public enum Kind { TEXT, ID_TEXT, DATE, MONEY, BALANCE, INT, SERIAL, PERCENT }
  public enum TotalRule { NONE, SUM, CLOSING_BALANCE, COUNT }
  public enum Band { ACCOUNT, PARTY, GROUP }
  public enum RowStyle { PLAIN, HIGHLIGHT }
  public record ReportColumn(String key, String labelKey, Kind kind, int widthChars, TotalRule totalRule, boolean hidden,
                             String datePattern, String debitKey, String creditKey, String link) {
      // compact ctor: DATE needs datePattern ∈ ReportFormat.DATE_PATTERNS; BALANCE needs debitKey+creditKey and CLOSING_BALANCE
      public static ReportColumn text(String key, String labelKey, int width);
      public static ReportColumn idText(String key, String labelKey, int width);              // link null
      public static ReportColumn idText(String key, String labelKey, int width, String link); // "journal" | "lease" | "cheque"
      public static ReportColumn date(String key, String labelKey, int width, String pattern);
      public static ReportColumn money(String key, String labelKey, int width);               // SUM
      public static ReportColumn balance(String key, String labelKey, int width, String debitKey, String creditKey);
      public static ReportColumn integer(String key, String labelKey, int width, TotalRule rule);
      public static ReportColumn serial(String key, String labelKey, int width);
      public ReportColumn hiddenColumn();                                                     // copy with hidden=true
  }
  public record ReportFilter(String labelKey, String value) {}
  public record ReportMeta(String reportKey, String titleKey, String orgName, List<ReportFilter> filters,
                           LocalDate from, LocalDate to, LocalDate asOf, ZonedDateTime generatedAt, String generatedBy, String lang) {}
  public record GridHeader(String title, String orgName, List<String> metaLines, String generatedLine,
                           List<ReportColumn> columns, List<String> columnLabels, String lang) {}
  public record GroupTotals(Map<String, BigDecimal> sums, long count, BigDecimal closing) {
      public static GroupTotals debitCredit(BigDecimal opening, BigDecimal debit, BigDecimal credit); // sums {debit, credit}, closing = opening+debit−credit
      public GroupTotals plus(GroupTotals other);
  }
  public interface ReportSink {
      void begin(GridHeader header);
      void band(int level, Band band, String label, boolean continued);
      void opening(int level, String label, BigDecimal balance, boolean carriedForward);
      void row(int level, Map<String, Object> cells, RowStyle style, Map<String, UUID> refs);
      void subtotal(int level, String label, Map<String, Object> totals);
      void total(String label, Map<String, Object> totals);
      void end(boolean truncated, boolean continuesOnNextPage);
  }
  public final class ReportWriter {
      public ReportWriter(ReportSink sink, ReportLabels.Bound labels, int rowCap);
      public void markPartial();                                   // feeding one page: open groups may remain at end()
      public void begin(ReportMeta meta, List<ReportColumn> columns);
      public void openGroup(int level, Band band, String label);
      public void continueGroup(int level, Band band, String label, BigDecimal carriedForward); // null = no balance row
      public void opening(BigDecimal balance);
      public void row(Map<String, Object> cells, RowStyle style, Map<String, UUID> refs);
      public void closeGroup(String subtotalLabel);                // null label = no subtotal row, still rolls up
      public void closeGroup(String subtotalLabel, GroupTotals authoritative);
      public void end();
      public void end(GroupTotals authoritative);
      public boolean truncated(); public long rowsWritten();
  }
  public final class GroupPager {
      public record PageSpan(int firstGroup, int firstRow, int lastGroup, int lastRow) {} // rows 1-based, inclusive
      public static List<PageSpan> plan(List<Integer> groupRowCounts, int pageRows);
  }
  public final class JsonGridSink implements ReportSink {
      public JsonGridSink(ReportGridDTO.Page page);
      public ReportGridDTO result();                               // after end(); page.continuesOnNextPage/truncated from end()
  }
  // api/dto/report/ReportGridDTO.java
  public record ReportGridDTO(Meta meta, List<Column> columns, List<Item> items, Page page) {
      public record Meta(String reportKey, String title, String orgName, List<String> lines, String generated, String lang) {}
      public record Column(String key, String label, String kind, boolean hidden, String link) {}
      public record Item(String type /* band|opening|row|subtotal|total */, int level, String band, String label,
                         boolean continued, boolean carriedForward, Map<String, String> cells, String style, Map<String, String> refs) {}
      public record Page(int page, int pageCount, int pageRows, long totalRows, int totalGroups, int firstGroup, int lastGroup,
                         boolean truncated, boolean continuesOnNextPage) {
          public static Page whole(long totalRows, int totalGroups);
      }
  }
  ```
- Cell value types the writer accepts: `String` (TEXT, ID_TEXT), `LocalDate` (DATE), `BigDecimal` (MONEY, PERCENT; `null` = blank and counts as 0), `Integer` (INT). The writer adds the BALANCE and SERIAL cells itself.
- JSON cell encoding: MONEY/BALANCE `setScale(2, HALF_UP).toPlainString()` (`"12345.00"`, `"-8000.00"`), DATE ISO `"2026-09-16"`, INT/SERIAL decimal, text as is; absent/null cells are omitted from the map. Refs are UUID strings.

**Writer rules** (the part the tests pin; implement exactly):
- Every MONEY/BALANCE value the writer emits (row balances, openings, subtotals, totals) is `setScale(2, HALF_UP)`, so sinks and tests compare `"50.00"`, never `"50"`.
- A frame per open group: `level, opening (null until opening()/continueGroup), running, sums (per SUM column), count, childClosings, serial, continued`.
- `row`: if `rowsWritten == rowCap` → `truncated = true`, drop. Else on the innermost frame: add each SUM cell (null → 0), `count++`, `serial++`; BALANCE cell = `running += debit − credit` (running starts at the frame's opening, else 0); SERIAL cell = `serial`.
- `opening(b)` sets opening and running and emits `sink.opening(level, labels "report.broughtForward", b, false)`. It is never added to Debit/Credit sums.
- `continueGroup(level, band, label, cf)` emits `sink.band(level, band, labels "report.continued"(label), true)`; when `cf != null` it sets opening/running and emits `sink.opening(level, labels "report.carriedForward", cf, true)`.
- `closeGroup`: `closing = (opening ?? 0) + Σ(debit − credit of own rows) + childClosings`; computed totals = sums + count + closing. With `authoritative`: when the frame was opened by `openGroup` **and** nothing was truncated, every SUM key and the closing must `compareTo == 0` with the computed value, else `IllegalStateException("Report totals disagree for <label>: computed <x>, SQL <y>")`; the printed totals are the authoritative ones. Subtotal cells: SUM keys, COUNT columns = count, the BALANCE column = closing. Totals roll into the parent frame (sums, count, childClosings += closing) or into the report.
- `end()`: open frames remaining → `IllegalStateException` unless `markPartial()` was called, in which case `continuesOnNextPage = true` and they are not rolled up. REPORT TOTAL = Σ top-level totals (BALANCE = Σ group closings); `end(authoritative)` asserts like `closeGroup` only when not partial and not truncated, then prints the authoritative totals.
- `begin` resolves the header: title `labels(meta.titleKey)`, meta lines = period (`report.period` with `dd/MM/yyyy` days, or `report.asOf`) and one line of filters `"<label>: <value>"` joined by `" · "`, generated line `report.generated(stamp(generatedAt), generatedBy ?? "—")`, column labels `labels(col.labelKey)`.

**Pager algorithm** (greedy, groups never split unless one alone exceeds a page):
```
weight(g) = max(rowCount(g), 1)          // an opening-only group still prints a row
pages = []; cur = null; used = 0
for g in 0..n-1:
  start = 1; left = weight(g)
  while left > 0:
    space = pageRows - used
    if left <= space:            add(g, start, start+left-1); used += left; left = 0
    else if used > 0 && left <= pageRows: close(); used = 0          // move the whole group to a fresh page
    else:                        add(g, start, start+space-1); start += space; left -= space; close(); used = 0
close()
n == 0 → [PageSpan(0, 1, -1, 0)]          // one empty page
```
`add` extends the current span (first add sets firstGroup/firstRow); `close` emits it if non-empty. `lastRow` is reported against the real `rowCount` (a weight-1 empty group reports `lastRow = 0`).

- [ ] **Step 1: Write the failing tests.** `SampleGrid` (test helper) defines R1-shaped columns — `date("docDate","col.docDate",12,"dd-MM-yyyy")`, `text("particular","col.particular",36)`, `money("debit",…)`, `money("credit",…)`, `balance("balance","col.balance",16,"debit","credit")`, `idText("chqNo",…)`, `text("payeeBank",…)`, `integer("bdDays","col.bdDays",8, NONE)`, `date("convertedDate",…,"dd/MM/yyyy")`, `idText("docNo","col.docNo",14,"journal")` — `meta(lang)` = `ReportMeta("sample", "tenantLedger.title", "Sample Org", [ReportFilter("filter.property", "Sample Tower")], 2026-09-01, 2027-09-30, null, 2026-10-26T10:05+04:00[Asia/Dubai], "Test User", lang)`, `E1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1")`, and `feed(ReportWriter w)`: ACCOUNT "Rent Receivable - Sample Tower" → PARTY "Tenant Name : Tenant A" → opening 0 → row (2026-09-16, "Advance Rent - Sample Tower", debit 60000, docNo "TCO-26/1", refs {entryId: E1}) → row (2026-09-16, "PDC Receivable Sample Tower", credit 9500, chqNo "000201", payeeBank "Emirates NBD", bdDays 1, convertedDate 2026-10-02, docNo "PDR-26/1") → row (2026-09-20, "=HYPERLINK(\"x\")", debit 0.10, docNo "JV-26/1") → `closeGroup("Sub Total")` → `closeGroup(null)` → `end()`.

```java
class ReportWriterTest {
    // w = new ReportWriter(sink, new ReportLabels().bind("en"), 50_000); sink = new RecordingSink()
    @Test void runningBalanceStartsAtTheOpeningWhichIsNeverSummed() {
        // PARTY "Tenant A", opening 100, rows debit 50 then credit 30, closeGroup("Sub Total"), end()
        assertThat(sink.rowBalances()).containsExactly(new BigDecimal("150.00"), new BigDecimal("120.00"));
        assertThat(sink.subtotal(2)).containsEntry("debit", new BigDecimal("50.00")).containsEntry("credit", new BigDecimal("30.00"))
                .containsEntry("balance", new BigDecimal("120.00"));
        assertThat(sink.openings()).containsExactly("2|Balance brought forward|100|false");
    }
    @Test void nestedGroupsRollUpAndTheReportTotalSumsClosings() {
        // ACCOUNT; PARTY A (opening 0, debit 100); PARTY B (opening -20, credit 10); closeGroup("Sub Total (RR)"); end()
        assertThat(sink.subtotal(1)).containsEntry("debit", new BigDecimal("100.00")).containsEntry("credit", new BigDecimal("10.00"))
                .containsEntry("balance", new BigDecimal("70.00"));
        assertThat(sink.total()).containsEntry("balance", new BigDecimal("70.00"));
    }
    @Test void aNullLabelClosesWithoutASubtotalRowButStillRollsUp() { /* closeGroup(null) → no subtotal(1); total balance unchanged */ }
    @Test void serialRestartsPerGroupAndCountTotalsCount() { /* serial column: 1,2 then 1; COUNT column subtotal = 2 */ }
    @Test void authoritativeTotalsMustAgreeWhenTheGroupWasWrittenWhole() {
        // openGroup + rows debit 50, closeGroup("Sub Total", GroupTotals.debitCredit(ZERO, new BigDecimal("51"), ZERO))
        assertThatThrownBy(...).isInstanceOf(IllegalStateException.class).hasMessageContaining("Report totals disagree");
    }
    @Test void aContinuedGroupPrintsItsCarriedBalanceAndTheAuthoritativeTotals() {
        // markPartial(); continueGroup(2, PARTY, "Tenant A", 500); row debit 10; closeGroup("Sub Total", debitCredit(0, 999, 1)); end()
        assertThat(sink.bands()).containsExactly("2|PARTY|Tenant A (continued)|true");
        assertThat(sink.openings()).containsExactly("2|Balance carried forward|500|true");
        assertThat(sink.rowBalances()).containsExactly(new BigDecimal("510.00"));
        assertThat(sink.subtotal(2)).containsEntry("debit", new BigDecimal("999.00")).containsEntry("balance", new BigDecimal("998.00"));
    }
    @Test void endWithAnOpenGroupIsRefusedUnlessPartial() { /* not partial → IllegalStateException; partial → sink.end(false, true) */ }
    @Test void truncatesAtTheRowCap() { /* cap 2, three rows → two row events, truncated(), sink.end(true, false) */ }
    @Test void bigDecimalSumsAreExact() {
        // 10,000 rows of debit 0.10 in one group
        assertThat(sink.subtotal(1).get("debit").toPlainString()).isEqualTo("1000.00");
    }
    @Test void anEmptyReportStillHasAReportTotal() {
        // begin, end() with no groups
        assertThat(sink.total()).containsEntry("debit", new BigDecimal("0.00")).containsEntry("balance", new BigDecimal("0.00"));
    }
}

class GroupPagerTest {
    @Test void packsWholeGroupsUpToThePageSize() {
        assertThat(GroupPager.plan(Collections.nCopies(17, 12), 200)).containsExactly(
                new PageSpan(0, 1, 15, 12), new PageSpan(16, 1, 16, 12));
    }
    @Test void movesAGroupThatDoesNotFitToTheNextPage() {
        assertThat(GroupPager.plan(List.of(150, 100), 200)).containsExactly(new PageSpan(0, 1, 0, 150), new PageSpan(1, 1, 1, 100));
    }
    @Test void splitsAGroupLargerThanAPage() {
        assertThat(GroupPager.plan(List.of(250, 4), 200)).containsExactly(new PageSpan(0, 1, 0, 200), new PageSpan(0, 201, 1, 4));
        assertThat(GroupPager.plan(List.of(12, 4), 5)).containsExactly(
                new PageSpan(0, 1, 0, 5), new PageSpan(0, 6, 0, 10), new PageSpan(0, 11, 0, 12), new PageSpan(1, 1, 1, 4));
    }
    @Test void anOpeningOnlyGroupTakesOneSlotAndNoGroupsIsOneEmptyPage() {
        assertThat(GroupPager.plan(List.of(0, 0), 200)).containsExactly(new PageSpan(0, 1, 1, 0));
        assertThat(GroupPager.plan(List.of(), 200)).containsExactly(new PageSpan(0, 1, -1, 0));
    }
}

class JsonGridSinkTest {
    @Test void itemsCarryDecimalStringsIsoDatesAndRefs() {
        JsonGridSink json = new JsonGridSink(ReportGridDTO.Page.whole(3, 1));
        ReportWriter w = new ReportWriter(json, new ReportLabels().bind("en"), 50_000);
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS); SampleGrid.feed(w);
        ReportGridDTO g = json.result();
        assertThat(g.items()).extracting(ReportGridDTO.Item::type)
                .containsExactly("band", "band", "opening", "row", "row", "row", "subtotal", "total");
        assertThat(g.items().get(3).cells()).containsEntry("docDate", "2026-09-16").containsEntry("debit", "60000.00")
                .containsEntry("balance", "60000.00").doesNotContainKey("credit");
        assertThat(g.items().get(4).cells()).containsEntry("balance", "50500.00").containsEntry("chqNo", "000201").containsEntry("bdDays", "1");
        assertThat(g.items().get(3).refs()).containsEntry("entryId", E1.toString());
        assertThat(g.columns()).extracting(ReportGridDTO.Column::label).startsWith("Doc Date", "Particular", "Debit");
        assertThat(g.columns().get(9).link()).isEqualTo("journal");
        assertThat(g.meta().title()).isEqualTo("Tenant Ledger");
    }
}
```

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.report.grid.*'` → FAIL (types missing).
- [ ] **Step 3: Implement** the types above, the writer rules and the pager algorithm as written. `RecordingSink` lives in the test tree only.
- [ ] **Step 4: Run** the Step 2 command → PASS. Mutation-check: make `opening()` add to the debit sum, re-run, see `runningBalanceStartsAtTheOpeningWhichIsNeverSummed` fail; restore.
- [ ] **Step 5: Commit** `feat(reports): grid writer engine, group pager and JSON sink`.

---

### Task 3: `XlsxGridSink` with style profiles

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/report/grid/XlsxStyle.java`, `XlsxGridSink.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/report/grid/XlsxGridSinkTest.java`

**Interfaces:**
- Consumes: `ReportSink`, `GridHeader`, `ReportFormat`, `SampleGrid` (Task 2).
- Produces:
  ```java
  public record XlsxStyle(String headerFill, String headerFont, String fontName, short fontPt, HorizontalAlignment moneyAlign) {
      public static final XlsxStyle PACT = new XlsxStyle("000080", "FFFFFF", "Calibri", (short) 9, HorizontalAlignment.RIGHT);
      public static final XlsxStyle TENANT_LEDGER = new XlsxStyle("FFFFFF", "000000", "Calibri", (short) 9, HorizontalAlignment.RIGHT);
  }
  public final class XlsxGridSink implements ReportSink, AutoCloseable {
      public XlsxGridSink(XlsxStyle style);
      public byte[] bytes();          // valid after end()
      @Override public void close();  // dispose() + close() if end() never ran
  }
  ```

**Layout and styles** (create every `CellStyle` once in `begin`, never per cell — SXSSF shares styles and Excel caps them at 64k):

| Element | Rule |
|---|---|
| Workbook | `new SXSSFWorkbook(null, 200, true)` (compressed temp files); sheet name `ReportFormat.sheetName(title)`; `setRightToLeft(true)` when `lang = ar` |
| Row 1 (index 0) | merged A1:<last>1, text = org, title, meta lines, generated line joined by `\n`; bold 12, wrap, top-aligned; height 64 pt |
| Row 2 (index 1) | headers: bold, centred, wrap, thin borders, fill `headerFill`, font colour `headerFont`; autofilter A2:<last>2; `createFreezePane(0, 2)`; `setRepeatingRows(1..1)`; print setup landscape, `setFitToPage(true)`, fit width 1, height 0 |
| Columns | width `widthChars * 256`; `setColumnHidden` for hidden columns |
| ACCOUNT band | merged across all columns, fill `D2691E`, font `F0F8FF` bold 14 |
| PARTY band | merged, fill `FFFF00`, font black 13 |
| GROUP band | merged, fill `7F7F7F`, font black bold `fontPt + 2` |
| Data cells | `fontName` `fontPt`, thin borders; HIGHLIGHT rows fill `FAC08F` |
| MONEY | numeric, `#,##0.00`, `moneyAlign`; null → blank styled cell |
| BALANCE | numeric, `#,##0.00"Dr";#,##0.00"Cr";0.00"Dr"` |
| DATE | `setCellValue(LocalDate)`, format = the column pattern lower-cased (`dd-mm-yyyy`, `dd/mm/yyyy`, `dd/mmm/yyyy`) |
| ID_TEXT | string cell, data format `@` |
| TEXT | string cell via `ReportFormat.cellText`; text starting with `= + - @` gets the **quote-prefixed** twin style (`setQuotePrefixed(true)`, Excel's own apostrophe) — the value itself is unchanged |
| INT / SERIAL | numeric `0` |
| Opening row | label merged from A to the column before the BALANCE column; balance in the BALANCE column |
| Sub Total / REPORT TOTAL | label in A, merged from A to the column before the first totalled column whenever that span is ≥ 2 cells; Sub Total not bold, REPORT TOTAL bold |
| Last row | blank, merged across all columns, height 11.25 pt |
| Safety | never `setCellFormula`, never a hyperlink; `bytes()` writes to a `ByteArrayOutputStream` then `dispose()` + `close()` in `finally` |

- [ ] **Step 1: Write the failing tests** (render via `SampleGrid.feed`, then reopen with `new XSSFWorkbook(new ByteArrayInputStream(bytes))`):

```java
@Test void titleBandHeadersAndFrame() {
    XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
    assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A1:J1"));
    assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("Sample Org").contains("Tenant Ledger").contains("Generated");
    assertThat(headers(s)).containsExactly("Doc Date", "Particular", "Debit", "Credit", "Balance", "Chq No", "Payee Bank",
            "BDDays", "ConvertedDate", "Doc No");
    XSSFCellStyle h = s.getRow(1).getCell(0).getCellStyle();
    assertThat(h.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
    assertThat(h.getFont().getXSSFColor().getARGBHex()).isEqualTo("FF000000");
    assertThat(h.getFont().getBold()).isTrue();
    assertThat(h.getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
    assertThat(s.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A2:J2");
    assertThat(s.getPaneInformation().getHorizontalSplitTopRow()).isEqualTo((short) 2);
    assertThat(s.getRepeatingRows()).isEqualTo(CellRangeAddress.valueOf("2:2"));
}
@Test void thePactProfileHasANavyHeader() {
    XSSFCellStyle h = render(XlsxStyle.PACT, "en").getRow(1).getCell(0).getCellStyle();
    assertThat(h.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FF000080");
    assertThat(h.getFont().getXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
}
@Test void bandsAreMergedAndColoured() {
    XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
    assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A3:J3"), CellRangeAddress.valueOf("A4:J4"));
    XSSFCellStyle account = s.getRow(2).getCell(0).getCellStyle();
    assertThat(account.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFD2691E");
    assertThat(account.getFont().getXSSFColor().getARGBHex()).isEqualTo("FFF0F8FF");
    assertThat(account.getFont().getFontHeightInPoints()).isEqualTo((short) 14);
    XSSFCellStyle party = s.getRow(3).getCell(0).getCellStyle();
    assertThat(party.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFF00");
    assertThat(party.getFont().getFontHeightInPoints()).isEqualTo((short) 13);
}
@Test void cellsAreRealNumbersDatesAndText() {
    XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
    Row opening = s.getRow(4), tco = s.getRow(5), pdr = s.getRow(6);
    assertThat(opening.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00\"Dr\";#,##0.00\"Cr\";0.00\"Dr\"");
    assertThat(tco.getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 16));
    assertThat(tco.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("dd-mm-yyyy");
    assertThat(tco.getCell(2).getNumericCellValue()).isEqualTo(60000.0);
    assertThat(tco.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
    assertThat(tco.getCell(3) == null || tco.getCell(3).getCellType() == CellType.BLANK).isTrue();
    assertThat(pdr.getCell(4).getNumericCellValue()).isEqualTo(50500.0);
    assertThat(pdr.getCell(5).getCellType()).isEqualTo(CellType.STRING);
    assertThat(pdr.getCell(5).getStringCellValue()).isEqualTo("000201");
    assertThat(pdr.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("@");
    assertThat(pdr.getCell(7).getNumericCellValue()).isEqualTo(1.0);
    assertThat(pdr.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
}
@Test void formulaLookingTextStaysAString() {
    XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
    Cell c = s.getRow(7).getCell(1);
    assertThat(c.getCellType()).isEqualTo(CellType.STRING);
    assertThat(c.getStringCellValue()).isEqualTo("=HYPERLINK(\"x\")");
    assertThat(c.getCellStyle().getQuotePrefixed()).isTrue();
    s.forEach(r -> r.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA)));
    assertThat(s.getHyperlinkList()).isEmpty();
}
@Test void totalsMergeTheLabelAndCloseWithABlankRow() {
    XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
    // rows: 8 = Sub Total, 9 = REPORT TOTAL, 10 = blank
    assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A9:B9"), CellRangeAddress.valueOf("A10:B10"),
            CellRangeAddress.valueOf("A11:J11"));
    assertThat(s.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Sub Total");
    assertThat(s.getWorkbook().getFontAt(s.getRow(8).getCell(0).getCellStyle().getFontIndex()).getBold()).isFalse();
    assertThat(s.getRow(9).getCell(0).getStringCellValue()).isEqualTo("REPORT TOTAL");
    assertThat(s.getWorkbook().getFontAt(s.getRow(9).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
    assertThat(s.getRow(9).getCell(2).getNumericCellValue()).isEqualTo(60000.1);
    assertThat(s.getRow(10).getHeightInPoints()).isEqualTo(11.25f);
}
@Test void arabicIsRightToLeftWithArabicHeaders() {
    XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "ar");
    assertThat(s.isRightToLeft()).isTrue();
    assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("تاريخ المستند");
}
@Test void hiddenColumnsAreHidden() { /* SampleGrid columns with bdDays.hiddenColumn() → s.isColumnHidden(7) */ }
@Test void anEmptyGridIsAValidWorkbook() { /* begin + end only → reopen OK, REPORT TOTAL row C = 0.0 */ }
@Test void leavesNoTempFilesBehind(@TempDir Path dir) {
    TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy(dir.toFile()));
    try { render(XlsxStyle.PACT, "en"); try (var files = Files.walk(dir)) { assertThat(files.filter(Files::isRegularFile)).isEmpty(); } }
    finally { TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy()); }
}
```

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.report.grid.XlsxGridSinkTest'` → FAIL (classes missing).
- [ ] **Step 3: Implement** per the table. Colours via `((XSSFCellStyle) style).setFillForegroundColor(new XSSFColor(HexFormat.of().parseHex(hex), null))` with `FillPatternType.SOLID_FOREGROUND`; font colour via `((XSSFFont) font).setColor(new XSSFColor(...))`.
- [ ] **Step 4: Run** the Step 2 command → PASS.
- [ ] **Step 5: Commit** `feat(reports): PACT-layout xlsx sink with style profiles`.

---

### Task 4: `ReportPdf` upgrades, `PdfGridSink`, `CsvGridSink`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/report/ReportPdf.java:26-89`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/report/grid/PdfGridSink.java`, `CsvGridSink.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/report/ReportPdfTest.java`, `backend/src/test/java/com/datagami/rentaxis/core/service/report/grid/PdfGridSinkTest.java`, `CsvGridSinkTest.java`

**Interfaces:**
- Consumes: `ReportSink`, `ReportFormat`, `ReportCsv.encode(List<List<String>>, boolean)` (existing, `core/service/report/statement/ReportCsv.java:141`), `SampleGrid`.
- Produces:
  ```java
  // ReportPdf
  public enum Style { PLAIN, HEAD, STRONG, TOTAL, OK, BAD, BAND_ACCOUNT, BAND_PARTY, BAND_GROUP, OPENING, HIGHLIGHT }
  public record Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes, boolean landscape,
                    String orgName) {
      public Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes, boolean landscape) {
          this(lang, title, meta, tables, notes, landscape, null);   // every existing caller keeps compiling
      }
  }
  public final class PdfGridSink implements ReportSink { public byte[] bytes(); }
  public final class CsvGridSink implements ReportSink { public byte[] bytes(); }
  ```
- `ReportPdf.html` changes: header row inside `<thead>` with `table { -fs-table-paginate: paginate; }` and rows inside `<tbody>`; `@page { @bottom-center { content: "<Page|صفحة> " counter(page) " / " counter(pages); font-size: 7pt; } }`; when `orgName != null` an `<div class="org">` above the `<h1>`; rows whose style is `BAND_*` render one `<td colspan="<header size>">`; CSS `tr.BAND_ACCOUNT td {background:#D2691E;color:#F0F8FF;font-weight:bold}`, `tr.BAND_PARTY td {background:#FFFF00}`, `tr.BAND_GROUP td {background:#7F7F7F;color:#FFFFFF;font-weight:bold}`, `tr.HIGHLIGHT td {background:#FAC08F}`, `tr.OPENING td {font-style:italic}`; effective landscape = `landscape || any table header has more than 9 columns`.
- `PdfGridSink`: one `ReportPdf.Table` (caption null) of the visible columns; numeric flags for MONEY/BALANCE/INT/SERIAL/PERCENT; bands → `BAND_*` rows; opening → `OPENING` row (label in column 0, `drCr` in the BALANCE column); data rows `PLAIN`/`HIGHLIGHT` with DATE via `ReportFormat.day(value, column.datePattern())`, MONEY `money`, BALANCE `drCr`; subtotal → `STRONG`; total → `TOTAL`. `Doc(lang, title, metaLines + generatedLine, [table], [], false, orgName)`.
- `CsvGridSink`: header = visible column labels with `labels "report.drCr"` inserted right after each BALANCE column; every line has the same width; band → `[label, "", …]`; opening → label in column 0, signed balance + `Dr|Cr`; rows: DATE in the column pattern, MONEY plain (`toPlainString`, blank when null), BALANCE signed plain + `Dr` (≥ 0) / `Cr`; subtotal/total → label in column 0 + totals; `ReportCsv.encode(rows, true)` (BOM + formula guard).

- [ ] **Step 1: Write the failing tests**

```java
class ReportPdfTest {
    @Test void headersRepeatPagesAreNumberedAndBandsSpanTheTable() {
        List<String> header = IntStream.range(0, 10).mapToObj(i -> "H" + i).toList();
        var t = new ReportPdf.Table(null, header, Collections.nCopies(10, false), List.of(
                ReportPdf.Row.of(ReportPdf.Style.BAND_ACCOUNT, List.of("<b>Rent</b>")),
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, header)));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false, "Sample Org"));
        assertThat(html).contains("<thead>").contains("-fs-table-paginate: paginate").contains("counter(pages)")
                .contains("size: A4 landscape").contains("<td colspan=\"10\">&lt;b&gt;Rent&lt;/b&gt;</td>").contains("Sample Org");
        assertThat(ReportPdf.html(new ReportPdf.Doc("ar", "T", List.of(), List.of(), List.of(), false))).contains("صفحة");
    }
}
class PdfGridSinkTest {
    @Test void rendersAndRepeatsTheHeaderOnPageTwo() throws IOException {
        byte[] pdf = /* SampleGrid columns, 150 PLAIN rows in one PARTY group, through PdfGridSink */;
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            PDFTextStripper s = new PDFTextStripper(); s.setStartPage(2); s.setEndPage(2);
            assertThat(s.getText(doc)).contains("Particular").contains("Page 2 /");
        }
    }
}
class CsvGridSinkTest {
    @Test void signedBalancesWithADrCrColumn() {
        String csv = /* SampleGrid through CsvGridSink */;
        assertThat(csv).startsWith("﻿");
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines.get(0)).isEqualTo("Doc Date,Particular,Debit,Credit,Balance,Dr/Cr,Chq No,Payee Bank,BDDays,ConvertedDate,Doc No");
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("16-09-2026,Advance Rent - Sample Tower,60000.00,,60000.00,Dr,"));
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("REPORT TOTAL,"));
    }
    @Test void guardsFormulas() {
        assertThat(/* csv */).contains("\"'=HYPERLINK(\"\"x\"\")\"");
    }
}
```

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests '*ReportPdfTest' --tests '*PdfGridSinkTest' --tests '*CsvGridSinkTest' --tests '*BalanceSheetServiceIT'` → FAIL (new constructor/styles/sinks missing; BalanceSheetServiceIT still passes).
- [ ] **Step 3: Implement** the `ReportPdf` changes and the two sinks as specified.
- [ ] **Step 4: Run** the Step 2 command, plus `--tests '*VatReturnServiceIT'` → all PASS (existing callers unchanged).
- [ ] **Step 5: Commit** `feat(reports): ReportPdf bands, repeating header and page numbers; PDF and CSV grid sinks`.

---

### Task 5: Export plumbing (`ExportFormat`, `ReportCaps`, `ReportFiles`, `GridRenderer`, 422) and `PropertyScope.requireSelection`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/report/grid/ExportFormat.java`, `ReportCaps.java`, `ReportFiles.java`, `GridRenderer.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/exception/ReportTooLargeException.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/GlobalExceptionHandler.java` (new handler next to `handleBusinessRule`, ~L87)
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/security/PropertyScope.java` (new record + method after `requirePropertyNamedByManager`, ~L118)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/report/grid/ReportFilesTest.java`, `ReportCapsTest.java`, `GridRendererTest.java`; `backend/src/test/java/com/datagami/rentaxis/api/GlobalExceptionHandlerReportTooLargeTest.java`; `backend/src/test/java/com/datagami/rentaxis/core/security/PropertyScopeTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum ExportFormat {
      XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      PDF("pdf", "application/pdf"), CSV("csv", "text/csv; charset=UTF-8");
      public String ext(); public MediaType mediaType();
      public static ExportFormat of(String ext);                 // IllegalArgumentException (→ 400) on anything else
  }
  @Component public class ReportCaps {
      public ReportCaps(@Value("${rentaxis.reports.max-rows.xlsx:50000}") int xlsx,
                        @Value("${rentaxis.reports.max-rows.csv:50000}") int csv,
                        @Value("${rentaxis.reports.max-rows.pdf:5000}") int pdf);
      public int screenPageRows();                               // Search.MAX_PAGE_SIZE (200)
      public int maxRows(ExportFormat f);
      public void requireWithin(ExportFormat f, long rows);      // throws ReportTooLargeException
  }
  public final class ReportFiles {
      public static ResponseEntity<byte[]> response(byte[] body, ExportFormat f, String filename); // ContentDisposition.attachment().filename(name, UTF_8)
  }
  public final class GridRenderer {
      public static byte[] render(ExportFormat f, XlsxStyle style, ReportLabels.Bound labels, int rowCap, Consumer<ReportWriter> feed);
  }
  public class ReportTooLargeException extends BusinessRuleViolationException {
      public ReportTooLargeException(ExportFormat f, long rows, int cap); // code "report.tooManyRowsPdf" for PDF else "report.tooManyRows"; args {rows, cap}
  }
  // GlobalExceptionHandler
  @ExceptionHandler(ReportTooLargeException.class)
  public ResponseEntity<Map<String, Object>> handleReportTooLarge(ReportTooLargeException ex); // 422 {error, message, status: 422, code, args}
  // PropertyScope
  public record Selection(boolean unrestricted, List<UUID> ids, boolean empty) {
      public Collection<UUID> sqlIds() { return Search.scopeIds(ids); }   // never an empty IN list
  }
  public Selection requireSelection(Collection<UUID> requested);
  ```
- 422 is new: today `BusinessRuleViolationException` maps to 400 (`GlobalExceptionHandler.java:87-104`) and nothing maps to 422. Spring picks the most specific `@ExceptionHandler`, so the subclass handler wins. Use `ResponseEntity.status(422)`.
- `ReportTooLargeException` English message: `"Too many rows for a PDF (%,d rows, the limit is %,d) — export Excel or narrow the filter"` / `"Too many rows (%,d rows, the limit is %,d) — narrow the filter"` (`Locale.ROOT`).
- `requireSelection` semantics: requested ids are de-duplicated, nulls dropped. Not property-scoped (`scopedPropertyIds() == null`): `(true, [], false)` when none requested, else `(false, requested, false)`. Property manager: any requested id outside the assignment → `NotFoundException("Property not found")`; requested → `(false, requested, false)`; none requested → `(false, assigned, false)`, or `(false, [], true)` when assigned to nothing. Existence of a requested id in the tenant is the caller's tenant-bound lookup. The name matches `PropertyScopeCoverageTest`'s `ENFORCING` regex.
- `GridRenderer.render` picks the sink by format (`XlsxGridSink(style)` in try-with-resources, `PdfGridSink`, `CsvGridSink`), builds `new ReportWriter(sink, labels, rowCap)`, runs `feed`, returns `sink.bytes()`.

- [ ] **Step 1: Write the failing tests**

```java
class ReportFilesTest {
    @Test void namesTheFileTheRfc5987Way() {
        ResponseEntity<byte[]> r = ReportFiles.response(new byte[]{1}, ExportFormat.XLSX, "tenant-ledger-2026-09-01-2027-09-30.xlsx");
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("filename*=UTF-8''tenant-ledger-2026-09-01-2027-09-30.xlsx");
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(ReportFiles.response(new byte[]{1}, ExportFormat.PDF, "دفتر.pdf").getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename*=UTF-8''%D8%AF%D9%81%D8%AA%D8%B1.pdf");
        assertThat(ExportFormat.of("csv")).isEqualTo(ExportFormat.CSV);
        assertThatThrownBy(() -> ExportFormat.of("xls")).isInstanceOf(IllegalArgumentException.class);
    }
}
class ReportCapsTest {
    @Test void defaultsAreTheD8Caps() {
        ReportCaps caps = new ReportCaps(50_000, 50_000, 5_000);
        caps.requireWithin(ExportFormat.PDF, 5_000);
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.PDF, 5_001)).isInstanceOfSatisfying(ReportTooLargeException.class, e -> {
            assertThat(e.getCode()).isEqualTo("report.tooManyRowsPdf");
            assertThat(e.getArgs()).containsEntry("rows", 5_001L).containsEntry("cap", 5_000);
            assertThat(e.getMessage()).contains("export Excel or narrow the filter");
        });
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.XLSX, 50_001))
                .isInstanceOfSatisfying(ReportTooLargeException.class, e -> assertThat(e.getCode()).isEqualTo("report.tooManyRows"));
        assertThat(caps.screenPageRows()).isEqualTo(200);
    }
}
class GlobalExceptionHandlerReportTooLargeTest {
    @Test void tooManyRowsIsA422CarryingItsCode() {
        var r = new GlobalExceptionHandler().handleReportTooLarge(new ReportTooLargeException(ExportFormat.PDF, 6_000, 5_000));
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody()).containsEntry("status", 422).containsEntry("code", "report.tooManyRowsPdf");
    }
}
class GridRendererTest {
    @Test void everyFormatRendersTheSampleGrid() { /* xlsx reopens; pdf starts "%PDF-"; csv starts with BOM */ }
}
class PropertyScopeTest {
    LeaseAccessPolicy policy = mock(LeaseAccessPolicy.class);
    PropertyScope scope = new PropertyScope(policy);
    UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
    void as(String role) { SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void tenantWideRolesAreUnrestrictedOrNarrowedToWhatTheyNamed() {
        as("ACCOUNTANT");
        assertThat(scope.requireSelection(List.of())).isEqualTo(new PropertyScope.Selection(true, List.of(), false));
        assertThat(scope.requireSelection(List.of(p1, p1))).isEqualTo(new PropertyScope.Selection(false, List.of(p1), false));
    }
    @Test void aManagerGetsTheAssignmentA404ForAForeignIdAndEmptyWhenAssignedNothing() {
        as("PROPERTY_MANAGER");
        when(policy.visiblePropertyIds()).thenReturn(List.of(p1, p2));
        assertThat(scope.requireSelection(null).ids()).containsExactly(p1, p2);
        assertThat(scope.requireSelection(List.of(p2)).ids()).containsExactly(p2);
        assertThatThrownBy(() -> scope.requireSelection(List.of(UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
        when(policy.visiblePropertyIds()).thenReturn(List.of());
        assertThat(scope.requireSelection(List.of()).empty()).isTrue();
        assertThat("requireSelection").matches("^(require.*|filter.*|canManage|canRead|canAccess.*)$");
    }
}
```

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests '*ReportFilesTest' --tests '*ReportCapsTest' --tests '*GridRendererTest' --tests '*GlobalExceptionHandlerReportTooLargeTest' --tests '*PropertyScopeTest' --tests '*PropertyScopeCoverageTest'` → FAIL (types missing).
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** the Step 2 command → PASS (`PropertyScopeCoverageTest` stays green).
- [ ] **Step 5: Commit** `feat(reports): export formats, row caps with a 422, file responses and PropertyScope.requireSelection`.

---

### Task 6: `ChequeReturnClass` and the tenant-ledger read model (`TenantLedgerQueries`)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeReturnClass.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/report/tenantledger/TenantLedgerQueries.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/report/ReceivableAccountDTO.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeReturnClassTest.java`, `backend/src/test/java/com/datagami/rentaxis/core/service/report/tenantledger/TenantLedgerQueriesIT.java`

**Interfaces:**
- Consumes: `PropertyScope.Selection` (Task 5), `NamedParameterJdbcTemplate` (existing bean, as `BankAccountLedgerService` uses it).
- Produces:
  ```java
  public enum ChequeReturnClass {
      BOUNCED(1), REPLACED_BEFORE_DEPOSIT(2);       // 2 has no flow yet (R2 phase B) and is never produced
      public final int code;
      /** 1 ⇔ a CBR exists and the cheque bounced on or before asOf; else null. Shared by R1 and R2. */
      public static ChequeReturnClass of(UUID cbrJournalId, LocalDate bouncedAt, LocalDate asOf);
  }
  public record ReceivableAccountDTO(UUID id, String code, String name, String nameAr, UUID propertyId, String propertyName) {}
  @Component public class TenantLedgerQueries {
      public TenantLedgerQueries(NamedParameterJdbcTemplate jdbc);
      public record Scope(UUID tenantId, LocalDate from, LocalDate to, List<UUID> accountIds, UUID renterId, UUID leaseId,
                          PropertyScope.Selection properties, boolean onlyWithBalance, boolean hideReversed) {}
      public record GroupKey(UUID accountId, UUID renterId) {}          // renterId null = "No tenant"
      public record GroupRow(UUID accountId, UUID renterId, String accountCode, String accountName, String accountNameAr,
                             String renterName, String renterNameAr, BigDecimal opening, BigDecimal debit, BigDecimal credit, int rowCount) {
          public GroupKey key();
      }
      public record LineRow(UUID accountId, UUID renterId, int rn, BigDecimal movement, UUID entryId, String entryNumber, String docType,
                            LocalDate entryDate, BigDecimal debit, BigDecimal credit, UUID leaseId, UUID chequeId,
                            String contraName, String contraNameAr, String chequeNumber, String chequeMode, String payeeBank,
                            LocalDate chequeDate, UUID cbrJournalId, LocalDate bouncedAt, UUID pdrJournalId) {
          public GroupKey key();
      }
      public record CounterRow(UUID entryId, UUID accountId, String names, String namesAr) {}
      public record NamedRef(UUID id, String name, String nameAr) {}

      public List<GroupRow> groups(Scope s);                              // ordered: account code, account id, renter (No tenant last), lower(name_en), renter id
      public List<LineRow> lines(Scope s, Collection<GroupKey> only);     // only == null → every group; else exactly those keys; ordered by group then rn
      public List<CounterRow> counterNames(UUID tenantId, Collection<UUID> entryIds, Collection<UUID> accountIds); // entry ids chunked by 1,000
      public List<UUID> receivableLeafIds(UUID tenantId);
      public List<ReceivableAccountDTO> receivableLeaves(UUID tenantId, UUID propertyId);  // propertyId null = all
      public Optional<String> orgName(UUID tenantId);
      public Optional<NamedRef> property(UUID tenantId, UUID id);
      public Optional<NamedRef> renter(UUID tenantId, UUID id);
      public Optional<NamedRef> lease(UUID tenantId, UUID id);           // name = contract_number
      public Map<UUID, NamedRef> accounts(UUID tenantId, Collection<UUID> ids);
  }
  ```
- Binding convention: an absent id is bound as `new UUID(0, 0)` with a companion boolean (`:hasRenter`, `:hasLease`); `:propertyIds` is `selection.sqlIds()`; empty `accountIds` short-circuits to an empty list (never an empty `IN`).

**SQL** (shared WHERE, then the two queries; every join binds `tenant_id`):
```sql
-- FILTER (both queries)
  l.tenant_id = :t
  and l.account_id in (:accountIds)
  and e.entry_date <= :to
  and (:hasRenter = false or l.renter_id = :renterId)
  and (:hasLease = false or l.lease_id = :leaseId)
  and (:unrestricted or coalesce(l.property_id, a.property_id) in (:propertyIds))
  and (:hideReversed = false or (e.reversed_by_id is null and e.reversal_of_id is null))

-- groups(): openings, window sums and row counts per (account, renter) — the authoritative totals
with base as (
    select l.account_id, l.renter_id, e.entry_date, l.debit, l.credit
    from journal_lines l
    join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
    join accounts a on a.id = l.account_id and a.tenant_id = :t
    where <FILTER>
), g as (
    select account_id, renter_id,
           coalesce(sum(debit - credit) filter (where entry_date < :from), 0) as opening,
           coalesce(sum(debit)  filter (where entry_date >= :from), 0)        as debit,
           coalesce(sum(credit) filter (where entry_date >= :from), 0)        as credit,
           count(*) filter (where entry_date >= :from)                         as row_count
    from base group by account_id, renter_id
)
select g.*, a.code as account_code, a.name as account_name, a.name_ar as account_name_ar,
       r.name_en as renter_name, r.name_ar as renter_name_ar
from g
join accounts a on a.id = g.account_id and a.tenant_id = :t
left join renters r on r.id = g.renter_id and r.tenant_id = :t
where (g.row_count > 0 or g.opening <> 0)
  and (:onlyWithBalance = false or g.opening + g.debit - g.credit <> 0)
order by a.code, a.id, (g.renter_id is null), lower(r.name_en), g.renter_id

-- lines(): rows with the per-(account, renter) window, in the one ledger ordering
with base as (
    select l.account_id, l.renter_id, l.lease_id, l.cheque_id, l.contra_account_id, l.line_no, l.debit, l.credit,
           e.id as entry_id, e.entry_number, e.doc_type, e.entry_date, e.created_at
    from journal_lines l
    join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
    join accounts a on a.id = l.account_id and a.tenant_id = :t
    where <FILTER>
), closing as (
    select account_id, renter_id, sum(debit - credit) as bal from base group by account_id, renter_id
), win as (
    select b.*, row_number() over w as rn, sum(b.debit - b.credit) over w as movement
    from base b
    where b.entry_date >= :from
    window w as (partition by b.account_id, b.renter_id
                 order by b.entry_date, b.created_at, b.entry_number, b.line_no
                 rows between unbounded preceding and current row)
)
select s.account_id, s.renter_id, s.rn, s.movement, s.entry_id, s.entry_number, s.doc_type, s.entry_date,
       s.debit, s.credit, s.lease_id, s.cheque_id,
       ca.name as contra_name, ca.name_ar as contra_name_ar,
       c.cheque_number, c.mode as cheque_mode, c.payee_bank, c.cheque_date, c.cbr_journal_id, c.bounced_at, c.pdr_journal_id
from win s
join closing k on k.account_id = s.account_id and k.renter_id is not distinct from s.renter_id
left join accounts ca on ca.id = s.contra_account_id and ca.tenant_id = :t
left join cheques  c  on c.id  = s.cheque_id        and c.tenant_id  = :t
where (:onlyWithBalance = false or k.bal <> 0)
  and (:allGroups or (s.account_id in (:pageAccounts)
       and (s.renter_id in (:pageRenters) or (:pageNoTenant and s.renter_id is null))))
order by s.account_id, s.renter_id nulls last, s.rn
```
`lines(s, only)`: `allGroups = only == null`; otherwise `pageAccounts`/`pageRenters` are the distinct ids of `only` (sentinel when no renter), `pageNoTenant` = any key with a null renter, and the result is then filtered in Java to exactly the keys in `only` (the IN lists can cross-match).

Counter names (the "Particular" of unpaired lines, EN and AR):
```sql
select x.entry_id, x.account_id,
       string_agg(distinct a2.name, ' / ' order by a2.name) as names,
       string_agg(distinct coalesce(nullif(a2.name_ar, ''), a2.name), ' / '
                  order by coalesce(nullif(a2.name_ar, ''), a2.name)) as names_ar
from (select distinct l.journal_entry_id as entry_id, l.account_id
      from journal_lines l
      where l.tenant_id = :t and l.journal_entry_id in (:entryIds) and l.account_id in (:accountIds)) x
join journal_lines l2 on l2.journal_entry_id = x.entry_id and l2.tenant_id = :t and l2.account_id <> x.account_id
join accounts a2 on a2.id = l2.account_id and a2.tenant_id = :t
group by x.entry_id, x.account_id
```
Receivable leaves: `accounts a left join properties p on p.id = a.property_id and p.tenant_id = :t where a.tenant_id = :t and a.account_sub_type = 'RECEIVABLE' and a.is_group = false and (:hasProperty = false or a.property_id = :propertyId) order by a.code`. Name lookups: `select … from <table> where id = :id and tenant_id = :t` (`landlord_org where id = :t` for the org name).

- [ ] **Step 1: Write the failing tests.**

```java
class ChequeReturnClassTest {
    @Test void bouncedOnlyWhenTheCbrExistsOnOrBeforeAsOf() {
        UUID cbr = UUID.randomUUID(); LocalDate d = LocalDate.of(2027, 4, 2);
        assertThat(ChequeReturnClass.of(cbr, d, d)).isEqualTo(ChequeReturnClass.BOUNCED);
        assertThat(ChequeReturnClass.of(cbr, d, d.minusDays(1))).isNull();
        assertThat(ChequeReturnClass.of(null, d, d)).isNull();
        assertThat(ChequeReturnClass.BOUNCED.code).isEqualTo(1);
    }
}
```
`TenantLedgerQueriesIT` (`@SpringBootTest`, extends `AbstractPostgresIT`; fixture `LeaseTestFixtures(...).bootstrap().withLeaseServices(leaseService, chequeGeneration, leasePosting)`; renter "Tenant A"; `postedLease(2026-09-16, 2026-10-02, 2027-10-01, [line("SECURITY_DEPOSIT","3000"), line("RENT","60000"), line("ADMIN_FEE","500")], 4, null)`; reads inside `TransactionTemplate`; `rr = resolver.resolve(RENT_RECEIVABLE, propertyId)`):
  - `groupsCarryOpeningsWindowSumsAndRowCounts`: whole window (2026-09-01..2027-09-30) → one group `(rr, tenantA)` with opening 0, debit 63500, credit 63500, rowCount = 3 + grid size; window from 2026-09-17 → the same group with opening 0 (contract nets to zero) and rowCount 0 is **dropped**.
  - `aRenterlessReceivableLineIsItsOwnGroupListedLast`: `posting.post(new PostingRequest(JournalDocType.JV, 2026-10-05, "Manual", Dimensions.ofProperty(propertyId), JournalSourceType.MANUAL, null, null, List.of(dr(RENT_RECEIVABLE, 750), cr(CASH, 750))))` → last group has `renterId == null`, debit 750.
  - `linesFollowTheLedgerOrderWithAWindowMovementAndChequeColumns`: rn 1..n; movement after row 3 = 63500, final = 0; PDR rows have `chequeNumber` = the grid numbers, `payeeBank` "Emirates NBD", `chequeDate` = grid date, `pdrJournalId == entryId`; TCO rows have null cheque fields.
  - `hideReversedDropsBothLegs`: JV on the renter's dims (Dr RR 1000 / Cr CASH 1000, 2026-10-05) then `posting.reverse(id, 2026-10-06, "test")` → `hideReversed=false` has 2 more rows than `true`; group sums identical.
  - `onlyWithBalanceKeepsOnlyNonZeroClosings`: after the contract the tenant nets to 0 → excluded; the No-tenant group (750) stays.
  - `counterNamesNameTheOtherAccountsInBothLanguages`: JV Dr RR 300 (no renter) / Cr CASH 100 / Cr OTHER_INCOME 200 → names = the two account names sorted and joined by `" / "`.
  - `foreignIdsMatchNothing`: a second `LeaseTestFixtures` org with the same lease shape; back in org A, `groups(scope with renterId = org B's renter)` is empty, `renter(orgA, orgBRenter)` / `property(...)` / `lease(...)` are `Optional.empty()`, `accounts(orgA, [orgB rr])` is empty. Mutation-check: drop `and tenant_id = :t` from the renter lookup, see this fail, restore.
  - `receivableLeavesAreLeavesOfTheReceivableSubtype`: returns `rr` (code, name, property name) and no group account; with an extra `accountService.createLeaf("Rent Receivable B - " + propertyName, accountService.getAccountByCode("A-02-01"), propertyId)` it returns both, ordered by code.
- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests '*ChequeReturnClassTest' --tests '*TenantLedgerQueriesIT'` → FAIL (types missing).
- [ ] **Step 3: Implement** `ChequeReturnClass`, `ReceivableAccountDTO` and `TenantLedgerQueries` with the SQL above (row mappers: `rs.getObject(col, UUID.class)`, `rs.getObject(col, LocalDate.class)`, `rs.getBigDecimal`).
- [ ] **Step 4: Run** the Step 2 command → PASS.
- [ ] **Step 5: Commit** `feat(reports): tenant-ledger read model and the shared ChequeReturnClass`.

---

### Task 7: `TenantLedgerReportService` + `TenantLedgerGrid`, service IT and golden extension

**Files:**
- Create in `backend/src/main/java/com/datagami/rentaxis/core/service/report/tenantledger/`: `TenantLedgerFilter.java`, `TenantLedgerResult.java`, `TenantLedgerRules.java`, `TenantLedgerReportService.java`, `TenantLedgerGrid.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/report/tenantledger/TenantLedgerRulesTest.java`, `TenantLedgerReportServiceIT.java`
- Modify: `backend/src/test/java/com/datagami/rentaxis/golden/GoldenLedgerTenantOneIT.java` (one new test + two `@Autowired` fields)

**Interfaces:**
- Consumes: `TenantLedgerQueries` (Task 6), `ReportWriter`/`GroupTotals`/`GroupPager`/`JsonGridSink`/`GridRenderer`/`XlsxStyle`/`ReportCaps`/`ExportFormat` (Tasks 2–5), `ReportLabels` (Task 1), `PropertyScope.requireSelection`, `Search.requireTenant()`, `Clock` bean (`config/ClockConfig`).
- Produces:
  ```java
  public record TenantLedgerFilter(LocalDate from, LocalDate to, UUID propertyId, List<UUID> accountIds, UUID renterId,
                                   UUID leaseId, boolean onlyWithBalance, boolean hideReversed, String lang) {
      // compact ctor: from/to required and to >= from else BusinessRuleViolationException; accountIds null → List.of();
      // lang → ReportLabels.lang(lang)
  }
  public record TenantLedgerResult(ReportMeta meta, List<AccountGroup> accounts, List<Line> lines, GroupPager.PageSpan span,
                                   int page, int pageCount, int pageRows, long totalRows, int totalGroups) {   // span null + pageRows 0 for an export
      public record AccountGroup(UUID accountId, String code, String label, String subTotalLabel, List<TenantGroup> tenants) {
          public GroupTotals totals();                    // Σ tenants
      }
      public record TenantGroup(int ordinal, UUID renterId, String label, BigDecimal opening, BigDecimal debit, BigDecimal credit,
                                int rowCount) { public GroupTotals totals(); }
      public record Line(UUID accountId, UUID renterId, int rn, BigDecimal movement, UUID entryId, String entryNumber, String docType,
                         LocalDate entryDate, String particular, BigDecimal debit, BigDecimal credit, String chqNo, String payeeBank,
                         Integer bdDays, LocalDate convertedDate, UUID leaseId, UUID chequeId) {}
      public GroupTotals reportTotals();                  // Σ accounts
  }
  public final class TenantLedgerRules {
      public static String chqNo(UUID chequeId, String number, String mode);       // no cheque → null; number; TRANSFER → "TT"; CASH → "CASH"; ONLINE → "ONLINE"; else null
      public static String payeeBank(UUID chequeId, String bank, String mode);     // no cheque → null; bank; TRANSFER → "TRANSFER"; CASH → "CASH"; ONLINE → "ONLINE"; else null
      public static LocalDate convertedDate(UUID entryId, UUID pdrJournalId, LocalDate chequeDate); // only on the PDR row
      public static Integer bdDays(UUID cbrJournalId, LocalDate bouncedAt, LocalDate to);           // ChequeReturnClass code or null
      public static String particular(String lang, String contraName, String contraNameAr, String fallback, String fallbackAr);
  }
  @Service @Transactional(readOnly = true, timeout = 60)
  public class TenantLedgerReportService {
      public TenantLedgerResult page(TenantLedgerFilter f, int page, int pageRows, String generatedBy);
      public TenantLedgerResult export(TenantLedgerFilter f, ExportFormat format, String generatedBy); // ReportTooLargeException before reading lines
      public List<ReceivableAccountDTO> receivableAccounts(UUID propertyId);
  }
  public final class TenantLedgerGrid {
      public static final String KEY = "tenant-ledger";
      public static final List<ReportColumn> COLUMNS;   // see below
      public static void feed(TenantLedgerResult r, ReportWriter w, ReportLabels.Bound labels);
      public static ReportGridDTO json(TenantLedgerResult r, ReportLabels labels);
      public static byte[] file(TenantLedgerResult r, ExportFormat f, ReportLabels labels, int rowCap); // XlsxStyle.TENANT_LEDGER
      public static String filename(TenantLedgerResult r, ExportFormat f);   // "tenant-ledger-<from>-<to>.<ext>", "-ar" before the dot when lang=ar
  }
  ```
- `COLUMNS` (PACT order + Doc No): `date("docDate","col.docDate",12,"dd-MM-yyyy")`, `text("particular","col.particular",36)`, `money("debit","col.debit",14)`, `money("credit","col.credit",14)`, `balance("balance","col.balance",16,"debit","credit")`, `idText("chqNo","col.chqNo",12)`, `text("payeeBank","col.payeeBank",18)`, `integer("bdDays","col.bdDays",8, TotalRule.NONE)` (never summed), `date("convertedDate","col.convertedDate",14,"dd/MM/yyyy")`, `idText("docNo","col.docNo",14,"journal")`.

**Service steps** (`page` and `export` share 1–6):
1. `t = Search.requireTenant()`; `selection = propertyScope.requireSelection(propertyId == null ? List.of() : List.of(propertyId))`.
2. Resolve every named id through the tenant-bound lookups, 404 (`NotFoundException`) on a miss: property ("Property not found"), renter ("Renter not found"), lease ("Lease not found"), each account ("Account not found"). These names feed `ReportMeta.filters`: `filter.property`, `filter.accounts` (names joined `", "`, or `filter.allReceivable`), `filter.tenant`, `filter.contract`, `filter.onlyWithBalance`/`filter.hideReversed` → `report.yes`. Arabic picks `name_ar` when non-blank.
3. `accountIds` = the filter's, else `receivableLeafIds(t)`. Empty, or `selection.empty()` → a result with no groups (no further query).
4. `groups = queries.groups(scope)` → `AccountGroup` per account in order (label = account name in lang; `subTotalLabel = labels("tenantLedger.accountSubTotal", label)`), `TenantGroup` per row (`ordinal` = global index; label = `labels("tenantLedger.tenantBand", renter name in lang, or labels("tenantLedger.noTenant") when renterId is null)`).
5. Meta: `ReportMeta("tenant-ledger", "tenantLedger.title", orgName, filters, from, to, null, ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of("Asia/Dubai")), generatedBy, lang)`.
6. Lines → `Line` via `TenantLedgerRules` (zero debit/credit become `null` so cells stay blank as PACT); unpaired lines (`contraName == null`) take `counterNames` for their (entry, account).
7. `page(f, p, pageRows)`: `plan = GroupPager.plan(rowCounts, pageRows)`; clamp `p` to `[0, plan.size()-1]`; span = `plan.get(p)`; `lines(scope, keys of groups firstGroup..lastGroup)` then keep, for the first group, `rn >= span.firstRow`, for the last, `rn <= span.lastRow`.
8. `export(f, format)`: `caps.requireWithin(format, Σ rowCount)` **before** step 6, then `lines(scope, null)`; `span = null`.

**Feed algorithm** (`TenantLedgerGrid.feed`):
```
if (r.span() != null) w.markPartial();
w.begin(r.meta(), COLUMNS);
linesByKey = r.lines() grouped by (accountId, renterId), insertion order
for a in r.accounts():
  ts = a.tenants() with ordinal in [span.firstGroup, span.lastGroup] (all when span == null); if empty → continue
  first = ts[0]
  if span != null && first.ordinal == span.firstGroup && a.tenants()[0].ordinal < first.ordinal
       → w.continueGroup(1, ACCOUNT, a.label(), null)          else → w.openGroup(1, ACCOUNT, a.label())
  for t in ts:
    ls = linesByKey(a.accountId, t.renterId) or []
    if span != null && t.ordinal == span.firstGroup && span.firstRow > 1:
         l0 = ls[0]; cf = t.opening + l0.movement − (nz(l0.debit) − nz(l0.credit)); w.continueGroup(2, PARTY, t.label(), cf)
    else w.openGroup(2, PARTY, t.label()); w.opening(t.opening)
    for l in ls: w.row(cells(l), PLAIN, refs {entryId, leaseId, chequeId (non-null only)})
    ends = span == null || t.ordinal < span.lastGroup || span.lastRow >= t.rowCount
    if !ends → w.end(r.reportTotals()); return                 // page stops inside this tenant
    w.closeGroup(labels("report.subTotal"), t.totals())
  if last tenant of a closed on this page → w.closeGroup(a.tenants().size() > 1 ? a.subTotalLabel() : null, a.totals())
  else → w.end(r.reportTotals()); return
w.end(r.reportTotals())
```
`cells(l)`: `docDate=entryDate, particular, debit, credit, chqNo, payeeBank, bdDays, convertedDate, docNo=entryNumber`. `json`: `JsonGridSink(new Page(r.page(), r.pageCount(), r.pageRows(), r.totalRows(), r.totalGroups(), span.firstGroup(), span.lastGroup(), false, false))`, or `Page.whole(r.totalRows(), r.totalGroups())` when `span == null`; the writer's `end` flags overwrite `truncated`/`continuesOnNextPage`.

- [ ] **Step 1: Write the failing tests.**

```java
class TenantLedgerRulesTest {
    UUID chq = UUID.randomUUID(), pdr = UUID.randomUUID();
    @Test void chequeColumnsFallBackToPactsTokens() {
        assertThat(TenantLedgerRules.chqNo(chq, "000201", "PDC")).isEqualTo("000201");
        assertThat(TenantLedgerRules.chqNo(chq, null, "TRANSFER")).isEqualTo("TT");
        assertThat(TenantLedgerRules.chqNo(chq, " ", "CASH")).isEqualTo("CASH");
        assertThat(TenantLedgerRules.chqNo(null, null, null)).isNull();
        assertThat(TenantLedgerRules.payeeBank(chq, null, "TRANSFER")).isEqualTo("TRANSFER");
        assertThat(TenantLedgerRules.payeeBank(chq, "Emirates NBD", "PDC")).isEqualTo("Emirates NBD");
    }
    @Test void convertedDateOnlyOnTheRegistrationRow() {
        LocalDate d = LocalDate.of(2027, 8, 2);
        assertThat(TenantLedgerRules.convertedDate(pdr, pdr, d)).isEqualTo(d);
        assertThat(TenantLedgerRules.convertedDate(UUID.randomUUID(), pdr, d)).isNull();
    }
    @Test void particularPrefersTheArabicContraUnderAr() {
        assertThat(TenantLedgerRules.particular("ar", "Advance Rent - X", "إيجار مقدم - X", null, null)).isEqualTo("إيجار مقدم - X");
        assertThat(TenantLedgerRules.particular("ar", "Advance Rent - X", " ", null, null)).isEqualTo("Advance Rent - X");
        assertThat(TenantLedgerRules.particular("en", null, null, "Cash / Other", "نقد / أخرى")).isEqualTo("Cash / Other");
    }
}
```
`TenantLedgerReportServiceIT` — `@Import(LaterBusinessDayConfig.class) @SpringBootTest`, extends `AbstractPostgresIT` (bounces in 2027 need the moved business day: `ChequeService.bounce` refuses a future date, `ChequeService.java:723-725`). Ground: `LeaseTestFixtures(...).bootstrap().withLeaseServices(...)`; `tenantA = fixtures.createRenter("Tenant A")`; lease A drafted on `fixtures.unit()` (contract 2026-09-16, term 2026-10-02..2027-10-01, lines SECURITY_DEPOSIT 3000, RENT 60000, ADMIN_FEE 500) with `grid.saveRows(leaseA, rows)` where every row has `postingDate` 2026-09-16:

| seq | mode | number | cheque date | amount | narration |
|---|---|---|---|---|---|
| 1 | PDC | 000201 | 2026-10-02 | 9500 | Rent - 1st Installment |
| 2 | PDC | 000202 | 2026-10-02 | 3000 | Security Deposit |
| 3 | PDC | 000203 | 2026-12-02 | 10200 | Rent - 2nd Installment |
| 4 | PDC | 000204 | 2027-02-02 | 10200 | Rent - 3rd Installment |
| 5 | PDC | 000205 | 2027-04-02 | 10200 | Rent - 4th Installment |
| 6 | PDC | 000206 | 2027-06-02 | 10200 | Rent - 5th Installment |
| 7 | TRANSFER | — (payee bank null) | 2027-08-02 | 10200 | Rent - 6th Installment |

PDC rows carry payee bank "Emirates NBD". Then `leasePosting.post(leaseA)`; rows 1–6 `deposit` + `clear` on their cheque date; row 7 `receive` on its date; rows 5 and 6 `bounce(id, new ChequeActionRequest(date, null, ChequeFailureReason.BOUNCE, null))` on their cheque date. Helpers: `json(filter)` = `TenantLedgerGrid.json(service.export(filter, XLSX, "it"), labels)`; `rows(grid)`, `bands(grid)`, `subtotal(grid, level)`, `total(grid)` read `ReportGridDTO.Item`s. Names from `resolver.resolve(role, propertyId).getName()`.

```java
@Test void oneLeaseReadsLikePactsTenantLedger() {
    ReportGridDTO g = json(filter(D("2026-09-01"), D("2027-09-30")));
    assertThat(bands(g)).containsExactly("ACCOUNT|" + rrName, "PARTY|Tenant Name : Tenant A");
    List<Map<String, String>> r = rows(g);
    assertThat(r).hasSize(12);
    assertThat(r).extracting(c -> c.get("particular")).startsWith(depositName, advanceRentName, adminFeeName);
    assertThat(r.subList(3, 10)).allSatisfy(c -> assertThat(c.get("particular")).isEqualTo(pdcName));
    assertThat(r.subList(10, 12)).allSatisfy(c -> assertThat(c.get("particular")).isEqualTo(bankName));
    assertThat(r).extracting(c -> c.get("balance")).containsExactly("3000.00", "63000.00", "63500.00", "54000.00", "51000.00",
            "40800.00", "30600.00", "20400.00", "10200.00", "0.00", "10200.00", "20400.00");
    assertThat(r.subList(3, 10)).extracting(c -> c.get("chqNo")).containsExactly("000201", "000202", "000203", "000204", "000205", "000206", "TT");
    assertThat(r.get(9)).containsEntry("payeeBank", "TRANSFER").containsEntry("convertedDate", "2027-08-02");
    assertThat(r.get(3)).containsEntry("payeeBank", "Emirates NBD").containsEntry("convertedDate", "2026-10-02");
    assertThat(r.get(10)).containsEntry("chqNo", "000205").doesNotContainKey("convertedDate");
    assertThat(r).extracting(c -> c.get("bdDays")).containsExactly(null, null, null, null, null, null, null, "1", "1", null, "1", "1");
    assertThat(subtotal(g, 2)).containsEntry("debit", "83900.00").containsEntry("credit", "63500.00").containsEntry("balance", "20400.00");
    assertThat(g.items()).noneMatch(i -> i.type().equals("subtotal") && i.level() == 1);   // one tenant → no account Sub Total
    assertThat(subtotal(g, 2)).doesNotContainKey("bdDays");
}
@Test void bdDaysFollowsTheAsOfRule() {
    assertThat(rows(json(filter(D("2026-09-01"), D("2027-04-01"))))).allSatisfy(c -> assertThat(c).doesNotContainKey("bdDays"));
    assertThat(rows(json(filter(D("2026-09-01"), D("2027-05-01"))))).filteredOn(c -> "1".equals(c.get("bdDays"))).hasSize(2);
}
@Test void twoTenantsMidWindowHaveTheirOwnOpeningsAndAnAccountSubTotal() {
    // Tenant B on unit "102": lines RENT 12000, fixtures.generateGrid(leaseB, 2, 2026-10-02), post, deposit+clear+bounce its first cheque on 2026-10-02
    ReportGridDTO g = json(filter(D("2027-05-01"), D("2027-09-30")));
    assertThat(bands(g)).containsExactly("ACCOUNT|" + rrName, "PARTY|Tenant Name : Tenant A", "PARTY|Tenant Name : Tenant B");
    assertThat(openings(g)).containsExactly("10200.00", "6000.00");
    assertThat(subtotal(g, 1)).containsEntry("debit", "10200.00").containsEntry("balance", "26400.00");
    assertThat(subtotalLabel(g, 1)).isEqualTo("Sub Total (" + rrName + ")");
    assertThat(total(g)).containsEntry("balance", "26400.00");
}
@Test void aRenterlessJvIsTheNoTenantGroupAndTheAccountTiesToTheLedgerAndTrialBalance() {
    // JV 2027-05-10 Dr RENT_RECEIVABLE 750 / Cr CASH 750, Dimensions.ofProperty(propertyId)
    ReportGridDTO g = json(filter(D("2027-05-01"), D("2027-09-30")));
    assertThat(bands(g)).last().isEqualTo("PARTY|Tenant Name : No tenant");
    BigDecimal closing = new BigDecimal(subtotal(g, 1).get("balance"));          // Tenant A 20,400 + No tenant 750
    assertThat(closing).isEqualByComparingTo("21150.00");
    assertThat(closing).isEqualByComparingTo(ledger.accountLedger(rr.getId(), new LedgerFilter(D("2027-05-01"), D("2027-09-30"), null, null, null, null)).closingBalance());
    assertThat(closing).isEqualByComparingTo(ledger.trialBalance(D("2027-09-30"), null).stream()
            .filter(t -> t.accountId().equals(rr.getId())).findFirst().orElseThrow().balance());
}
@Test void aLeaseWithAReceivableOverrideFormsItsOwnAccountBand() {
    // override = accountService.createLeaf("Rent Receivable B - " + propertyName, accountService.getAccountByCode("A-02-01"), propertyId);
    // Tenant C on unit "103", lease C (RENT 12000, grid 2) with setReceivableOverride(leaseC, override) before posting
    assertThat(bands(json(filter(D("2026-09-01"), D("2027-09-30")))).filteredOn(b -> b.startsWith("ACCOUNT|"))
            .containsExactlyInAnyOrder("ACCOUNT|" + rrName, "ACCOUNT|" + override.getName());
}
@Test void hideReversedDropsBothLegsAcrossTheWindowEdge() {
    // JV on Tenant A's dims (property, unit, leaseA, tenantA) 2027-04-20 Dr RR 1000 / Cr CASH 1000; posting.reverse(id, 2027-05-05, "test")
    ReportGridDTO shown = json(filter(D("2027-05-01"), D("2027-09-30")));
    ReportGridDTO hidden = json(filterHideReversed(D("2027-05-01"), D("2027-09-30")));
    assertThat(openings(shown).get(0)).isEqualTo("11200.00");
    assertThat(openings(hidden).get(0)).isEqualTo("10200.00");
    assertThat(rows(hidden)).hasSize(rows(shown).size() - 1);
    assertThat(subtotal(hidden, 2).get("balance")).isEqualTo(subtotal(shown, 2).get("balance"));
}
@Test void runningBalancesMatchTheAccountLedgerRowForRow() {
    // same-day ties: TCO + 7 PDRs on 2026-09-16
    List<String> ours = rows(json(filterRenter(tenantA, D("2026-09-01"), D("2027-09-30")))).stream().map(c -> c.get("balance")).toList();
    List<String> gl = ledger.accountLedger(rr.getId(), new LedgerFilter(D("2026-09-01"), D("2027-09-30"), null, null, null, tenantA.getId()))
            .rows().stream().map(x -> x.balance().setScale(2).toPlainString()).toList();
    assertThat(ours).isEqualTo(gl);
}
@Test void pagesSplitAGroupAndCarryTheBalanceForward() {
    // + Tenant B as above; pageRows = 5; whole window → A has 12 rows, B 4: pages A1-5, A6-10, A11-12, B1-4
    List<ReportGridDTO> pages = IntStream.range(0, 4).mapToObj(p -> TenantLedgerGrid.json(service.page(whole, p, 5, "it"), labels)).toList();
    assertThat(pages.get(0).page().pageCount()).isEqualTo(4);
    assertThat(pages).extracting(p -> total(p)).allSatisfy(t -> assertThat(t).isEqualTo(total(json(whole))));
    ReportGridDTO p1 = pages.get(1);
    assertThat(p1.items().get(0)).satisfies(i -> { assertThat(i.continued()).isTrue(); assertThat(i.band()).isEqualTo("ACCOUNT"); });
    assertThat(p1.items().get(1).label()).isEqualTo("Tenant Name : Tenant A (continued)");
    assertThat(p1.items().get(2).carriedForward()).isTrue();
    assertThat(p1.items().get(2).cells().get("balance")).isEqualTo(rows(pages.get(0)).get(4).get("balance"));
    assertThat(pages.stream().flatMap(p -> rows(p).stream()).map(c -> c.get("docNo") + c.get("balance")).toList())
            .isEqualTo(rows(json(whole)).stream().map(c -> c.get("docNo") + c.get("balance")).toList());
    assertThat(pages.get(1).items()).noneMatch(i -> i.type().equals("subtotal"));
    assertThat(pages.get(2).items()).anyMatch(i -> i.type().equals("subtotal") && i.level() == 2);
    assertThat(pages.get(3).items()).anyMatch(i -> i.type().equals("subtotal") && i.level() == 1);
    assertThat(pages.get(1).page().continuesOnNextPage()).isTrue();
}
@Test void arabicUsesArabicNamesAndBands() { /* tenantA.setNameAr("المستأجر أ"); lang ar → "PARTY|اسم المستأجر : المستأجر أ"; particulars use name_ar when set */ }
@Test void anotherOrganisationWithIdenticalNamesNeverLeaks() {
    // org B: PropertyService property with the SAME nameEn as org A's, renter "Tenant A", same lease shape posted; switch back to org A
    TenantLedgerResult r = service.export(filter(D("2026-09-01"), D("2027-09-30")), ExportFormat.XLSX, "it");
    Set<UUID> orgAEntries = Set.copyOf(jdbc.queryForList("select id from journal_entries where tenant_id = ?", UUID.class, orgA));
    assertThat(r.lines()).allSatisfy(l -> assertThat(orgAEntries).contains(l.entryId()));
    assertThat(r.totalGroups()).isEqualTo(groupsBeforeOrgB);
    for (var foreign : List.of(filterProperty(orgBProperty), filterRenter(orgBRenter), filterLease(orgBLease), filterAccounts(orgBRr)))
        assertThatThrownBy(() -> service.export(foreign, ExportFormat.XLSX, "it")).isInstanceOf(NotFoundException.class);
}
```
The cap path (422 before any line is read) is asserted over HTTP in Task 8, where the PDF cap property is lowered.

Helpers in the IT: `D(s)` = `LocalDate.parse(s)`; `filter(from, to)` = all receivable accounts, no other narrowing, `lang "en"`; `filterHideReversed`, `filterRenter(renter, from, to)`, `filterProperty(id)`, `filterLease(id)`, `filterAccounts(id)` set the one field they name (the last four over 2026-09-01..2027-09-30); `bands(g)` = `"<band>|<label>"` of the band items; `openings(g)` = the opening items' `balance` cells; `subtotal(g, level)` / `subtotalLabel(g, level)` = cells / label of the first subtotal item at that level; `total(g)` = cells of the last item; `rows(g)` = cells of the row items.

Golden extension in `GoldenLedgerTenantOneIT` (class is `@Tag("golden")`): add `@Autowired TenantLedgerReportService tenantLedger; @Autowired ReportLabels reportLabels;` and

```java
@Test
void theTenantLedgerReportReproducesTheReceivableRows() {
    replay();
    ReportGridDTO g = TenantLedgerGrid.json(tenantLedger.export(new TenantLedgerFilter(LEDGER_FROM, LEDGER_TO, null, List.of(),
            renterId, null, false, false, "en"), ExportFormat.XLSX, "golden"), reportLabels);
    List<GoldenRow> expected = GoldenLedgerFixture.ledgerByAccount(LEDGER).get(RENT_RECEIVABLE);
    List<ReportGridDTO.Item> rows = g.items().stream().filter(i -> i.type().equals("row")).toList();
    assertThat(rows).hasSize(expected.size());
    for (int i = 0; i < rows.size(); i++) {
        Map<String, String> c = rows.get(i).cells(); GoldenRow e = expected.get(i);
        assertThat(c.get("docDate")).as("row %d", i).isEqualTo(e.entryDate().toString());
        assertThat(c.get("particular")).as("row %d", i).isEqualTo(e.particular());
        assertThat(c.getOrDefault("debit", "0.00")).as("row %d", i).isEqualTo(e.debit().setScale(2).toPlainString());
        assertThat(c.getOrDefault("credit", "0.00")).as("row %d", i).isEqualTo(e.credit().setScale(2).toPlainString());
        assertThat(c.get("balance")).as("row %d", i).isEqualTo(e.balance().setScale(2).toPlainString());
        assertThat(c.get("docNo")).as("row %d", i).startsWith(e.docType());
    }
    assertThat(g.items()).filteredOn(i -> i.type().equals("band")).extracting(ReportGridDTO.Item::label)
            .containsExactly(RENT_RECEIVABLE, "Tenant Name : " + RENTER);
    assertThat(rows.subList(3, 10)).extracting(i -> i.cells().get("chqNo"))
            .containsExactly("000101", "000102", "000103", "000104", "000105", "000106", "000107");
    assertThat(rows).extracting(i -> i.cells().get("bdDays"))
            .containsExactly(null, null, null, null, null, null, null, null, "1", "1", "1", "1");
    ReportGridDTO.Item total = g.items().get(g.items().size() - 1);
    assertThat(total.cells()).containsEntry("debit", "77050.00").containsEntry("credit", "58050.00").containsEntry("balance", "19000.00");
}
```
(The fixture's 12 receivable rows are TCO ×3, PDR ×7 in grid order, CBR ×2; cheques 6 and 7 bounced, so `bdDays` is 1 on PDR rows 9–10 and both CBR rows.)

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests '*TenantLedgerRulesTest' --tests '*TenantLedgerReportServiceIT'` → FAIL (types missing). Then `./gradlew test -PincludeTags=golden --tests '*GoldenLedgerTenantOneIT'` → FAIL to compile the new test.
- [ ] **Step 3: Implement** the filter, result, rules, service and grid as specified.
- [ ] **Step 4: Run** both Step 2 commands → PASS; the golden fixture files are **not** edited. Mutation-check: make `bdDays` ignore `to`, see `bdDaysFollowsTheAsOfRule` fail; drop `markPartial()` from `feed`, see the paging test fail; restore both.
- [ ] **Step 5: Commit** `feat(reports): R1 tenant ledger service with PACT grouping, cheque columns and paging; golden coverage`.

---

### Task 8: `FinanceRegisterReportController` and its HTTP IT

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/FinanceRegisterReportController.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/FinanceRegisterReportControllerIT.java`

**Interfaces:**
- Consumes: `TenantLedgerReportService`, `TenantLedgerGrid`, `ReportLabels`, `ReportCaps`, `ReportFiles`, `ExportFormat`, `UserRepository`, `CallerIdentity.callerId()`.
- Produces (base `/api/v1/finance/reports`, class and **every** method `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")`):
  ```java
  @GetMapping("/tenant-ledger")
  public ReportGridDTO tenantLedger(@RequestParam @DateTimeFormat(iso = DATE) LocalDate from,
          @RequestParam @DateTimeFormat(iso = DATE) LocalDate to,
          @RequestParam(required = false) UUID propertyId, @RequestParam(required = false) List<UUID> accountIds,
          @RequestParam(required = false) UUID renterId, @RequestParam(required = false) UUID leaseId,
          @RequestParam(defaultValue = "false") boolean onlyWithBalance, @RequestParam(defaultValue = "false") boolean hideReversed,
          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "en") String lang);
  @GetMapping("/tenant-ledger.{format:xlsx|pdf|csv}")
  public ResponseEntity<byte[]> tenantLedgerFile(@PathVariable String format, /* same filter params, no page */);
  @GetMapping("/tenant-ledger/accounts")
  public List<ReceivableAccountDTO> tenantLedgerAccounts(@RequestParam(required = false) UUID propertyId);
  ```
- JSON: `TenantLedgerGrid.json(service.page(filter, page, caps.screenPageRows(), callerName()), labels)`. File: `f = ExportFormat.of(format)`; `r = service.export(filter, f, callerName())`; `ReportFiles.response(TenantLedgerGrid.file(r, f, labels, caps.maxRows(f)), f, TenantLedgerGrid.filename(r, f))`. `callerName()` is a private copy of `PropertyReportController.callerName()` (`PropertyReportController.java:246-254`).

- [ ] **Step 1: Write the failing IT** — `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "rentaxis.reports.max-rows.pdf=5")`, extends `AbstractPostgresIT`. Ground: `LeaseTestFixtures(...).bootstrap().withLeaseServices(...)`; `postedLease(2026-09-16, 2026-10-02, 2027-10-01, [line("RENT","51000"), line("ADMIN_FEE","2000")], 4, null)` (TCO 2 rows + PDR 5 rows on the receivable = 7 rows); `emptyProperty = fixtures.createProperty("EMP")` (its own receivable leaf, no lines); `adminFeeLeaf = resolver.resolve(ADMIN_FEE, fixtures.property().getId()).getId()`; `http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService)`; users via `http.user(fixtures.tenantId(), role)`; `TenantContextHolder.clear()` and `LeaseTestFixtures.clearAuth()` at the end of `setUp`. `Q = "from=2026-09-01&to=2027-09-30"`.

```java
@Test void financeRolesOnlyEveryHandler() {
    for (UserRole ok : List.of(SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT))
        for (String p : List.of("/tenant-ledger?" + Q, "/tenant-ledger.xlsx?" + Q, "/tenant-ledger/accounts"))
            assertThat(http.status(http.user(tenant, ok), GET, BASE + p)).as(ok + p).isEqualTo(200);
    for (UserRole no : List.of(PROPERTY_MANAGER, TENANT_USER, RENTER, SECURITY_GUARD))
        for (String p : List.of("/tenant-ledger?" + Q, "/tenant-ledger.csv?" + Q, "/tenant-ledger/accounts"))
            assertThat(http.status(http.user(tenant, no), GET, BASE + p)).as(no + p).isEqualTo(403);
}
@Test void aSuperAdminWithNoOrganisationIsToldToPickOne() {
    // RestClient with only X-User-Id / X-User-Role (as ImportBatchControllerIT.callWithoutTenant) for a SUPER_ADMIN user with tenantId null
    assertThat(res.getStatusCode().value()).isEqualTo(400);
    assertThat(res.getBody()).contains("Select an organisation first");
}
@Test void filesCarryTheirTypeAndAnRfc5987Name() {
    ResponseEntity<byte[]> x = file("/tenant-ledger.xlsx?" + Q);
    assertThat(x.getHeaders().getContentType().toString()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(x.getHeaders().getFirst(CONTENT_DISPOSITION)).contains("filename*=UTF-8''tenant-ledger-2026-09-01-2027-09-30.xlsx");
    assertThat(file("/tenant-ledger.csv?" + Q).getHeaders().getContentType().toString()).startsWith("text/csv");
    // a one-row party ledger fits the lowered PDF cap
    ResponseEntity<byte[]> pdf = file("/tenant-ledger.pdf?" + Q + "&accountIds=" + adminFeeLeaf + "&lang=ar");
    assertThat(new String(pdf.getBody(), 0, 5, ISO_8859_1)).isEqualTo("%PDF-");
    assertThat(pdf.getHeaders().getFirst(CONTENT_DISPOSITION)).contains("tenant-ledger-2026-09-01-2027-09-30-ar.pdf");
}
@Test void aPdfOverTheCapIsA422WithItsCode() {
    ResponseEntity<Map> r = http.call(accountant, GET, BASE + "/tenant-ledger.pdf?" + Q, null);
    assertThat(r.getStatusCode().value()).isEqualTo(422);
    assertThat(r.getBody()).containsEntry("code", "report.tooManyRowsPdf");
    assertThat((Map<String, Object>) r.getBody().get("args")).containsEntry("rows", 7).containsEntry("cap", 5);
}
@Test void jsonXlsxAndCsvShowTheSameReportTotal() {
    // JSON: last item cells; XLSX: the row whose A cell is "REPORT TOTAL" → C, D, E numeric; CSV: line starting "REPORT TOTAL"
    assertThat(List.of(xlsxDebit, xlsxCredit, xlsxBalance)).isEqualTo(List.of(jsonDebit, jsonCredit, jsonBalance));
    assertThat(csvTotalFields).startsWith("REPORT TOTAL", "", jsonDebit.toPlainString(), jsonCredit.toPlainString());
}
@Test void theWorkbookIsInPactLayout() {
    XSSFSheet s = sheet("/tenant-ledger.xlsx?" + Q);
    assertThat(headers(s)).containsExactly("Doc Date", "Particular", "Debit", "Credit", "Balance", "Chq No", "Payee Bank", "BDDays",
            "ConvertedDate", "Doc No");
    assertThat(s.getRow(1).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
    assertThat(firstDataRow(s).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("dd-mm-yyyy");
    assertThat(firstPdrRow(s).getCell(8).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
    XSSFSheet ar = sheet("/tenant-ledger.xlsx?" + Q + "&lang=ar");
    assertThat(ar.isRightToLeft()).isTrue();
    assertThat(ar.getRow(1).getCell(0).getStringCellValue()).isEqualTo("تاريخ المستند");
}
@Test void anEmptySelectionExportsZeroTotals() {
    // a second property with no lines: propertyId=<it>
    assertThat(sheetReportTotalBalance("/tenant-ledger.xlsx?" + Q + "&propertyId=" + emptyProperty)).isEqualTo(0.0);
    assertThat(json("/tenant-ledger?" + Q + "&propertyId=" + emptyProperty).items()).extracting("type").containsExactly("total");
}
@Test void thePickerListsReceivableLeavesOnly() { /* /tenant-ledger/accounts → both properties' Rent Receivable leaves (code, name, propertyName), no group account; ?propertyId=<emptyProperty> → its own leaf only */ }
@Test void anotherOrganisationsPropertyIs404() { /* propertyId of a second org → 404 on JSON and xlsx */ }
```

- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests '*FinanceRegisterReportControllerIT' --tests '*PropertyScopeCoverageTest'` → FAIL (404 for the new paths).
- [ ] **Step 3: Implement** the controller.
- [ ] **Step 4: Run** the Step 2 command → PASS.
- [ ] **Step 5: Commit** `feat(reports): tenant-ledger endpoints — JSON page, xlsx/pdf/csv file and receivable picker`.

---

### Task 9: Web — `lib/reports/format.ts`, report colour tokens, `Reports` i18n namespace

**Files:**
- Create: `web/src/lib/reports/format.ts`; Test: `web/src/lib/reports/__tests__/format.test.ts`
- Modify: `web/src/app/globals.css` (`:root`, dark `@media`, `@theme inline`)
- Modify: `web/src/components/finance/LedgerTable.tsx:106`, `web/src/app/[locale]/dashboard/finance/trial-balance/page.tsx:263`, `web/src/components/finance/reports/PnlTable.tsx:158` (replace `style={{ background: "#C8651B" }}` + `text-white` with `bg-report-band text-report-band-fg`); update `web/src/components/finance/reports/__tests__/__snapshots__/PnlTable.test.tsx.snap`
- Modify: `web/messages/en.json`, `web/messages/ar.json` (new `Reports` namespace; `AccountingNav.reportsHub`)

**Interfaces:**
- Produces:
  ```ts
  export function fmtMoney(v: string | null | undefined): string;   // "60000" → "60,000.00"; "" / null → ""; string arithmetic only
  export function fmtDrCr(v: string | null | undefined): string;    // "12345" → "12,345.00Dr"; "-8000.5" → "8,000.50Cr"; "0"/"-0.00" → "0.00Dr"; null → ""
  export function fmtReportDate(iso: string | null | undefined): string; // "2026-09-16" → "16/09/2026" in every locale
  ```
- Tokens (light | dark): `--report-band #C8651B | #9A4A12`, `--report-band-fg #FFFFFF | #FFF4EA`, `--report-party #FFF4B8 | #4A4210`, `--report-party-fg #0B1F3A | #F2F5F9`, `--report-group #7F7F7F | #3A4558`, `--report-group-fg #FFFFFF | #F2F5F9`, `--report-highlight #FAC08F | #5A3A1C`; `@theme inline` maps each as `--color-report-band: var(--report-band);` etc. (via `var()`, unlike the hard-coded entries there, so dark mode switches them). The `-party-fg`/`-group-fg` pair is added to the spec's five so text stays readable in dark mode.
- `Reports` keys (flat, EN | AR) — `navModel.test` reads labels as `messages[ns][key]`, so no nesting:

  | key | en | ar |
  |---|---|---|
  | hubTitle | Reports | التقارير |
  | hubDesc | Every report you can run, in one place. | كل التقارير المتاحة لك في مكان واحد. |
  | hubSearch | Search reports | ابحث في التقارير |
  | hubNoMatches | No report matches “{q}”. | لا يوجد تقرير يطابق «{q}». |
  | hubOpen | Open | فتح |
  | hubPmScoped | Your assigned properties only | العقارات المسندة إليك فقط |
  | groupRegisters | Registers & ledgers | السجلات ودفاتر الأستاذ |
  | groupFinal | Final reports | التقارير الختامية |
  | formatXlsx / formatPdf / formatCsv | Excel / PDF / CSV | Excel / PDF / CSV |
  | descGeneralLedger | Every posting, grouped by account, with a running balance. | كل قيد مرحّل مجمّعاً حسب الحساب مع الرصيد الجاري. |
  | descTenantLedger | Receivable accounts by tenant, with cheque columns, in PACT's layout. | حسابات الذمم المدينة حسب المستأجر مع أعمدة الشيكات بتنسيق PACT. |
  | descTrialBalance | Account balances as of a date. | أرصدة الحسابات كما في تاريخ معين. |
  | descBalanceSheet | Assets, liabilities and equity as at a date. | الأصول والالتزامات وحقوق الملكية كما في تاريخ معين. |
  | descCompanyPl | Income and expenses for the whole company. | إيرادات ومصروفات المؤسسة كلها. |
  | descPropertyPl | Income, expenses and NOI per property. | الإيرادات والمصروفات وصافي الدخل التشغيلي لكل عقار. |
  | descPropertyStatement | Billed, collected and outstanding for one property. | المفوتر والمحصّل والمتبقي لعقار واحد. |
  | descVatReturn | The quarter in the FTA's VAT 201 form. | الربع بصيغة نموذج VAT 201 للهيئة الاتحادية للضرائب. |
  | descPayablesAging | What is owed to suppliers, by age. | المستحق للموردين حسب العمر. |
  | exportMenu | Export | تصدير |
  | exportXlsx | Excel (PACT layout) | Excel (تنسيق PACT) |
  | exportPdfEn | PDF — English | PDF — الإنجليزية |
  | exportPdfAr | PDF — Arabic | PDF — العربية |
  | exportCsv | CSV | CSV |
  | exportBusy | Preparing… | جارٍ التحضير… |
  | exportTooManyRowsPdf | Too many rows for a PDF ({rows} rows, the limit is {cap}). Export Excel or narrow the filter. | عدد الصفوف كبير على ملف PDF ({rows} صفاً والحد {cap}). صدّر إلى Excel أو ضيّق عامل التصفية. |
  | exportTooManyRows | Too many rows ({rows} rows, the limit is {cap}). Narrow the filter. | عدد الصفوف كبير ({rows} صفاً والحد {cap}). ضيّق عامل التصفية. |
  | exportRateLimited | Too many exports in a row. Try again in a minute. | عمليات تصدير كثيرة متتالية. حاول بعد دقيقة. |
  | exportFailed | The export failed. Try again. | تعذّر التصدير. حاول مرة أخرى. |
  | tablePrevious / tableNext | Previous / Next | السابق / التالي |
  | tablePageOf | Page {page} of {count} | صفحة {page} من {count} |
  | tableEmpty | Nothing to show for these filters. | لا شيء لعرضه لهذه الاختيارات. |
  | tableTruncated | Showing the first {shown} of {total} rows. Narrow the filter or export. | يُعرض أول {shown} من {total} صفاً. ضيّق عامل التصفية أو صدّر. |
  | tableContinues | Continues on the next page | يتبع في الصفحة التالية |
  | shellAccessDenied | Your role cannot open this report. | لا يسمح دورك بفتح هذا التقرير. |
  | tlViewReceivable | Receivable (PACT) | الذمم المدينة (PACT) |
  | tlViewAll | All accounts | كل الحسابات |
  | tlDesc | Receivable accounts by tenant, with cheque columns. | حسابات الذمم المدينة حسب المستأجر مع أعمدة الشيكات. |
  | tlReceivableAccounts | Receivable accounts | حسابات الذمم المدينة |
  | tlAllReceivable | All receivable accounts | كل حسابات الذمم المدينة |
  | tlAddReceivable | Add a receivable account | أضف حساب ذمم مدينة |
  | tlOtherAccount | Other account (party ledger) | حساب آخر (دفتر طرف) |
  | tlTenantOptional | Tenant (optional) | المستأجر (اختياري) |
  | tlContract | Contract | العقد |
  | tlOnlyWithBalance | Only tenants with a balance | المستأجرون ذوو الرصيد فقط |
  | tlHideReversed | Hide reversed pairs | إخفاء القيود المعكوسة |
  | tlPickScope | Pick a tenant, a property or an account to run the ledger | اختر مستأجرًا أو عقارًا أو حسابًا لعرض الدفتر |
  | tlGroups | Tenants {from}–{to} of {total} | المستأجرون {from}–{to} من {total} |
  | tlRemoveAccount | Remove {name} | إزالة {name} |

  Plus `AccountingNav.reportsHub`: "All reports" | "كل التقارير".

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from "vitest";
import { fmtDrCr, fmtMoney, fmtReportDate } from "../format";
describe("report formats", () => {
    it("prints Dr/Cr in PACT's form without float arithmetic", () => {
        expect(fmtDrCr("12345")).toBe("12,345.00Dr");
        expect(fmtDrCr("-8000.5")).toBe("8,000.50Cr");
        expect(fmtDrCr("0")).toBe("0.00Dr");
        expect(fmtDrCr("-0.00")).toBe("0.00Dr");
        expect(fmtDrCr("90071992547409.93")).toBe("90,071,992,547,409.93Dr");
        expect(fmtMoney("1234567.8")).toBe("1,234,567.80");
        expect(fmtMoney(undefined)).toBe("");
    });
    it("prints dd/mm/yyyy whatever the locale", () => {
        expect(fmtReportDate("2026-09-16")).toBe("16/09/2026");
        expect(fmtReportDate(null)).toBe("");
    });
});
```
- [ ] **Step 2: Run** `cd web && npx vitest run src/lib/reports/__tests__/format.test.ts` → FAIL (module not found).
- [ ] **Step 3: Implement** `format.ts` (split on `.`, pad the fraction to 2, group the integer part with `/\B(?=(\d{3})+(?!\d))/g`; sign from the leading `-` unless every digit is 0), the tokens, the three `#C8651B` replacements and the i18n keys.
- [ ] **Step 4: Run** `cd web && npx vitest run src/components/finance/reports/__tests__/PnlTable.test.tsx -u`, inspect the snapshot diff (only the band's `style` → token classes), then `npx vitest run src/lib/reports src/components/finance "src/app/[locale]/dashboard/finance/trial-balance" src/lib/__tests__/catalog-parity.test.ts && node scripts/check-i18n-parity.mjs` → PASS.
- [ ] **Step 5: Commit** `feat(web): report formats, report colour tokens and the Reports i18n namespace`.

---

### Task 10: Web report kit — `lib/api/reports.ts`, `ReportShell`, `ExportMenu`, `GroupedReportTable`

**Files:**
- Create: `web/src/lib/api/reports.ts`
- Create: `web/src/components/reports/ReportShell.tsx`, `ExportMenu.tsx`, `GroupedReportTable.tsx`, `useReportQuery.ts`
- Test: `web/src/components/reports/__tests__/GroupedReportTable.test.tsx`, `ExportMenu.test.tsx`, `ReportShell.test.tsx`, `useReportQuery.test.tsx`, `web/src/components/reports/__tests__/grid.fixture.ts`

**Interfaces:**
- Consumes: `apiGet`, `qs` (`lib/api/ledger.ts:13,24`), `ApiError` (`lib/api/facilities.ts:37`), `saveResponse` (`lib/download.ts:63`), `AccessDeniedState` (`components/ui/PageStates.tsx:48`), `LoadErrorBanner`, `Link` (`@/i18n/routing`), format helpers (Task 9).
- Produces:
  ```ts
  // lib/api/reports.ts — mirrors ReportGridDTO exactly
  export type GridKind = "TEXT" | "ID_TEXT" | "DATE" | "MONEY" | "BALANCE" | "INT" | "SERIAL" | "PERCENT";
  export interface GridColumn { key: string; label: string; kind: GridKind; hidden: boolean; link: "journal" | "lease" | "cheque" | null }
  export interface GridItem { type: "band" | "opening" | "row" | "subtotal" | "total"; level: number; band: "ACCOUNT" | "PARTY" | "GROUP" | null;
      label: string | null; continued: boolean; carriedForward: boolean; cells: Record<string, string>; style: "PLAIN" | "HIGHLIGHT";
      refs: Record<string, string> }
  export interface GridPage { page: number; pageCount: number; pageRows: number; totalRows: number; totalGroups: number;
      firstGroup: number; lastGroup: number; truncated: boolean; continuesOnNextPage: boolean }
  export interface ReportGrid { meta: { reportKey: string; title: string; orgName: string; lines: string[]; generated: string; lang: "en" | "ar" };
      columns: GridColumn[]; items: GridItem[]; page: GridPage }
  export interface TenantLedgerQuery { from: string; to: string; propertyId?: string; accountIds?: string[]; renterId?: string;
      leaseId?: string; onlyWithBalance?: boolean; hideReversed?: boolean }
  export interface ReceivableAccount { id: string; code: string; name: string; nameAr: string | null; propertyId: string | null; propertyName: string | null }
  export type FileFormat = "xlsx" | "pdf" | "csv";
  export const reportsApi: { tenantLedger: {
      get(q: TenantLedgerQuery, page: number, lang: string, signal?: AbortSignal): Promise<ReportGrid>;   // GET /finance/reports/tenant-ledger
      accounts(propertyId?: string): Promise<ReceivableAccount[]>;                                        // GET …/tenant-ledger/accounts
      fileUrl(format: FileFormat, q: TenantLedgerQuery, lang: string): string;                            // "/api/proxy/v1/finance/reports/tenant-ledger.<fmt>?…&lang="
  } };
  // components/reports
  export interface ExportOption { id: "xlsx" | "pdfEn" | "pdfAr" | "csv"; url: string; fallbackName: string }
  export function ExportMenu(props: { options: ExportOption[]; disabled?: boolean }): JSX.Element;
  export function GroupedReportTable(props: { grid: ReportGrid; onPage?: (page: number) => void; groupsLabel?: string }): JSX.Element;
  export function ReportShell(props: { title: string; description?: string; period?: string; scopeNote?: string; allowed: boolean;
      filterBar: React.ReactNode; exportMenu?: React.ReactNode;
      state: "idle" | "loading" | "error" | "empty" | "ready"; idleMessage: string; error?: string | null; onRetry?: () => void;
      truncated?: { shown: number; total: number } | null; children?: React.ReactNode }): JSX.Element;
  /** The filter bar's draft/applied pair, the applied side mirrored into the URL (replaceState; other params kept). */
  export function useReportQuery<Q>(read: (sp: URLSearchParams) => Q, write: (q: Q) => Record<string, string | undefined>):
      { draft: Q; setDraft: (q: Q) => void; applied: Q; apply: (next?: Q) => void };
  ```
- `useReportQuery` reads once from `useSearchParams()` on mount (no `useRouter`, so pages keep working under the existing `next/navigation` test mocks); `apply` sets `applied` (and `draft`) and writes every key of `write(q)` — `undefined`/`""` deletes the key.
- `GroupedReportTable` rendering: one `<table className="w-full min-w-[900px]">` inside `<div className="bg-surface border border-border rounded-xl overflow-x-auto">`; sticky `<thead>`; visible columns only. Band rows: one `<td colSpan>` with `bg-report-band text-report-band-fg font-bold` (ACCOUNT), `bg-report-party text-report-party-fg font-semibold` (PARTY), `bg-report-group text-report-group-fg font-bold` (GROUP), label in `<bdi>`; `data-testid="grid-band-{level}"`. Opening: label spans the columns before the BALANCE column, balance via `fmtDrCr`, `data-testid="grid-opening"`. Row: DATE `fmtReportDate`, MONEY `fmtMoney`, BALANCE `fmtDrCr`, others as text; every MONEY/BALANCE/INT cell is `<bdi dir="ltr" className="tabular-nums">`, end-aligned; HIGHLIGHT rows get `bg-report-highlight`; a column with `link: "journal"` and `refs.entryId` renders `<Link href={`/dashboard/finance/journals/${refs.entryId}`}>`, `link: "lease"` with `refs.leaseId` → `/dashboard/leases/${id}`. Subtotal (`data-testid="grid-subtotal-{level}"`) and total (`data-testid="grid-total"`, bold) print the server's cells as given. Footer when `page.pageCount > 1`: Previous/Next buttons, `tablePageOf` (1-based), the optional `groupsLabel`, and `tableContinues` when `continuesOnNextPage`.
- `ExportMenu`: a button `exportMenu` toggling a `role="menu"`; each option a `role="menuitem"` labelled `exportXlsx|exportPdfEn|exportPdfAr|exportCsv`. Click → `fetch(url)`; while pending the trigger is `disabled` with `aria-busy="true"` and text `exportBusy`. `res.ok` → `saveResponse(res, fallbackName)`. Otherwise read the JSON body: `code === "report.tooManyRowsPdf" | "report.tooManyRows"` → `t(code key, { rows, cap })` with numbers pre-formatted by `new Intl.NumberFormat("en-US")`; status 429 → `exportRateLimited`; else the body's `message` or `exportFailed`; shown in a `role="alert"` under the menu. Never `window.open`.

- [ ] **Step 1: Write the failing tests** (`vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl())`; `vi.mock("@/i18n/routing", …)` as in `on-demand.test.tsx`). `grid.fixture.ts` exports a `ReportGrid` with columns docDate (DATE), particular (TEXT), debit/credit (MONEY), balance (BALANCE), docNo (ID_TEXT, link journal) and items: band ACCOUNT "Rent Receivable - Sample Tower", band PARTY "Tenant Name : Tenant A", opening `{balance:"0.00"}`, row `{docDate:"2026-09-16", particular:"Advance Rent - Sample Tower", debit:"60000.00", balance:"60000.00", docNo:"TCO-26/1"}` refs `{entryId:"e1"}`, row `{credit:"68000.00", balance:"-8000.00", docNo:"PDR-26/1"}`, subtotal level 2 `{debit:"999.00", credit:"68000.00", balance:"-8000.00"}`, total `{debit:"999.00", …}`; page 1 of 4.

```tsx
it("renders bands, opening, rows, subtotal and total in order", () => {
    render(<GroupedReportTable grid={grid} onPage={onPage} />);
    const ids = [...document.querySelectorAll("[data-testid^='grid-']")].map(e => e.getAttribute("data-testid"));
    expect(ids).toEqual(["grid-band-1", "grid-band-2", "grid-opening", "grid-row", "grid-row", "grid-subtotal-2", "grid-total"]);
    expect(screen.getByText("60,000.00Dr")).toBeInTheDocument();
    expect(screen.getAllByText("8,000.00Cr").length).toBeGreaterThan(0);
    expect(screen.getByText("16/09/2026")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "TCO-26/1" })).toHaveAttribute("href", "/dashboard/finance/journals/e1");
    expect(screen.getByTestId("grid-subtotal-2")).toHaveTextContent("999.00");   // the server's figure, not a client sum
    expect(screen.getByText("60,000.00Dr").closest("bdi")).toHaveAttribute("dir", "ltr");
    expect(document.querySelector(".overflow-x-auto table")).not.toBeNull();
});
it("pages forward with a 1-based label", () => {
    render(<GroupedReportTable grid={{ ...grid, page: { ...grid.page, page: 1 } }} onPage={onPage} />);
    expect(screen.getByText("Page 2 of 4")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onPage).toHaveBeenCalledWith(2);
});
it("shows a continued band as the server labels it", () => { /* band continued=true label "Tenant Name : Tenant A (continued)" is rendered verbatim */ });

// ExportMenu.test.tsx — vi.mock("@/lib/download", () => ({ saveResponse: vi.fn() }))
it("downloads through fetch and saveResponse", async () => {
    global.fetch = vi.fn(async () => new Response("x", { status: 200 })) as unknown as typeof fetch;
    render(<ExportMenu options={[{ id: "xlsx", url: "/api/proxy/v1/finance/reports/tenant-ledger.xlsx?from=a", fallbackName: "tenant-ledger.xlsx" }]} />);
    fireEvent.click(screen.getByRole("button", { name: "Export" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Excel (PACT layout)" }));
    await waitFor(() => expect(saveResponse).toHaveBeenCalledWith(expect.any(Response), "tenant-ledger.xlsx"));
    expect(global.fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/reports/tenant-ledger.xlsx?from=a");
});
it("puts the server's 422 inline", async () => {
    global.fetch = vi.fn(async () => new Response(JSON.stringify({ code: "report.tooManyRowsPdf", args: { rows: 6000, cap: 5000 }, message: "x" }),
        { status: 422, headers: { "Content-Type": "application/json" } })) as unknown as typeof fetch;
    // open, click "PDF — English"
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many rows for a PDF (6,000 rows, the limit is 5,000)");
    expect(saveResponse).not.toHaveBeenCalled();
});
it("says try later on a 429", async () => { /* status 429 → "Too many exports in a row. Try again in a minute." */ });

// ReportShell.test.tsx
it("denies a role that may not open the report", () => { /* allowed=false → getByTestId("page-access-denied") */ });
it("asks for a scope before loading", () => { /* state idle → idleMessage text; no table */ });
it("shows the truncated banner", () => { /* truncated {shown:200,total:900} → "Showing the first 200 of 900 rows. Narrow the filter or export." */ });

// useReportQuery.test.tsx (vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) })))
it("seeds from the URL, keeps edits as a draft and writes only what is applied", () => {
    query.current = "a=1&other=keep";
    // renderHook(() => useReportQuery(sp => ({ a: sp.get("a") ?? "" }), q => ({ a: q.a || undefined })))
    // setDraft({ a: "2" }) → URL unchanged; apply() → URL "a=2&other=keep"; apply({ a: "" }) → URL "other=keep"
});
```
`grid.fixture.ts` page: `{ page: 0, pageCount: 4, pageRows: 200, totalRows: 500, totalGroups: 41, firstGroup: 0, lastGroup: 15, truncated: false, continuesOnNextPage: false }`.
- [ ] **Step 2: Run** `cd web && npx vitest run src/components/reports` → FAIL (modules missing).
- [ ] **Step 3: Implement** the API module and three components as specified.
- [ ] **Step 4: Run** the Step 2 command → PASS; `npx tsc --noEmit` clean.
- [ ] **Step 5: Commit** `feat(web): report kit — ReportShell, ExportMenu and GroupedReportTable`.

---

### Task 11: Web — report catalog, Reports hub, nav entry, route, help, RBAC

**Files:**
- Create: `web/src/lib/reports/catalog.ts`; Test: `web/src/lib/reports/__tests__/catalog.test.ts`
- Create: `web/src/app/[locale]/dashboard/reports/page.tsx`; Test: `web/src/app/[locale]/dashboard/reports/__tests__/hub.test.tsx`
- Modify: `web/src/lib/rbac.ts` (add `canViewReportsHub` next to `canViewPropertyReports`, ~L236)
- Modify: `web/src/lib/nav/accountingNav.ts:44-67` (registers and finalReports report items come from the catalog; hub item first in finalReports)
- Modify: `web/src/lib/nav/routeRegistry.ts:67` (add `r(`${D}/reports`, "canViewReportsHub")`)
- Modify: `web/src/lib/help.ts:115` (add `'/dashboard/reports': REPORTS`)
- Modify: `web/src/lib/helpArticles.ts:776-` and `web/src/lib/helpArticlesAr.ts:719-` (`finance--ledgers-and-reports`: the Reports hub and the PACT Tenant Ledger)
- Modify tests: `web/src/lib/nav/__tests__/models.test.ts:15-49`, `navModel.test.ts:19-46`, `rbacParity.test.ts:12-25`

**Interfaces:**
- Consumes: `Permission`, `hasPermission`, `PERMISSIONS` (`lib/rbac.ts`), `Label` (`lib/nav/types`), `DASHBOARD_ROUTES`, `HELP_PAGE_MAP`.
- Produces:
  ```ts
  export type ReportFileFormat = "xlsx" | "pdf" | "csv";
  export type ReportGroup = "registers" | "final";
  export interface ReportEntry {
      key: string; group: ReportGroup; title: Label; descKey: string; href: string; permission: Permission;
      formats: ReportFileFormat[]; pmScoped: boolean;
      nav: { group: "registers" | "finalReports"; testId: string } | null;
  }
  export const REPORT_CATALOG: ReportEntry[];
  export function visibleReports(role: UserRole | undefined): ReportEntry[];
  export function catalogNavItems(group: "registers" | "finalReports"): { id: string; href: string; label: Label; testId: string; allow: Permission }[];
  // rbac.ts
  canViewReportsHub: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
  ```
- `REPORT_CATALOG`, in today's nav order (F = `/dashboard/finance`):

  | key | group | title | descKey | href | permission | formats | pmScoped | nav |
  |---|---|---|---|---|---|---|---|---|
  | general-ledger | registers | Ledger.generalLedger | descGeneralLedger | F/general-ledger | canAccessFinance | csv | no | registers, sidebar-general-ledger |
  | tenant-ledger | registers | Ledger.tenantLedger | descTenantLedger | F/tenant-ledger | canAccessFinance | xlsx, pdf, csv | no | registers, sidebar-tenant-ledger |
  | trial-balance | registers | Ledger.trialBalance | descTrialBalance | F/trial-balance | canAccessFinance | csv | no | registers, sidebar-trial-balance |
  | balance-sheet | final | PropertyReports.balanceSheet | descBalanceSheet | F/reports/balance-sheet | canViewPropertyReports | pdf, csv | yes | finalReports, sidebar-balance-sheet |
  | company-pl | final | PropertyReports.companyPl | descCompanyPl | F/reports/company-pl | canViewCompanyReports | pdf, csv | no | finalReports, sidebar-company-pl |
  | property-pl | final | PropertyReports.propertyPl | descPropertyPl | F/reports/property-pl | canViewPropertyReports | pdf, csv | yes | finalReports, sidebar-property-pl |
  | property-statement | final | PropertyReports.propertyStatement | descPropertyStatement | F/reports/property-statement | canViewPropertyReports | pdf, csv | yes | finalReports, sidebar-property-statement |
  | vat-return | final | PropertyReports.vatReturn | descVatReturn | F/reports/vat-return | canViewVatReturn | pdf, csv | no | finalReports, sidebar-vat-return |
  | payables-aging | final | Payables.aging | descPayablesAging | F/payables/aging | canViewPayablesAging | csv | yes | null (stays hand-written in receivablesPayables) |
- `accountingNav` registers = `[...catalogNavItems("registers"), cheque-registers crossLink]`; finalReports = `[item("reports-hub", "/dashboard/reports", "AccountingNav", "reportsHub", "sidebar-reports-hub", "canViewReportsHub"), ...catalogNavItems("finalReports")]`. Catalog `key` is the nav item id.
- The catalog drives the help map by test (every catalog href has a `HELP_PAGE_MAP` entry); `HELP_PAGE_MAP` itself stays a literal.
- Hub page: client component; `role` from `useSession`; `!hasPermission(role, "canViewReportsHub")` → `AccessDeniedState`; header `Reports.hubTitle` / `hubDesc`; `<input type="search" aria-label={t("hubSearch")}>` filtering on the translated title + description (case-insensitive); sections `groupRegisters` / `groupFinal` (hidden when empty); cards in `grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3`, each `data-testid="report-card-{key}"` with title, description, format chips (`formatXlsx|formatPdf|formatCsv`), `hubPmScoped` note for a PROPERTY_MANAGER on `pmScoped` entries, and a `Link` "Open" to `href`; `hubNoMatches` when the search empties everything.
- Help article additions (EN): under "Journals and registers" replace the Tenant Ledger bullet with "**Accounting › Registers › Tenant Ledger** — PACT's tenant ledger: receivable accounts by tenant with Chq No, Payee Bank, BDDays and ConvertedDate; switch to **All accounts** for one tenant's full statement. Export Excel (PACT layout), PDF or CSV." and add "**Accounting › Final Reports › All reports** — every report your role can run, with a search box." AR mirrors it with the Arabic labels (`المحاسبة › السجلات › دفتر أستاذ المستأجر`, `كل الحسابات`, `المحاسبة › التقارير الختامية › كل التقارير`); Latin tokens other than PDF/CSV/Excel/PACT go in backticks (the Arabic-article test rejects bare Latin words).

- [ ] **Step 1: Write the failing tests and update the expectations**

```ts
// catalog.test.ts
it("every entry has a real permission, a registered route with the same gate and a help entry", () => {
    for (const e of REPORT_CATALOG) {
        expect(PERMISSIONS[e.permission], e.key).toBeDefined();
        const route = DASHBOARD_ROUTES.find(r => r.path === e.href);
        expect(route?.allow, e.key).toBe(e.permission);
        expect(HELP_PAGE_MAP[e.href], e.key).toBeDefined();
    }
    expect(DASHBOARD_ROUTES.find(r => r.path === "/dashboard/reports")?.allow).toBe("canViewReportsHub");
    expect(HELP_PAGE_MAP["/dashboard/reports"]).toBeDefined();
});
it("the hub admits exactly the roles that can open some report", () => {
    const roles = new Set(REPORT_CATALOG.flatMap(e => PERMISSIONS[e.permission]));
    expect(new Set(PERMISSIONS.canViewReportsHub)).toEqual(roles);
});
it("filters by role", () => {
    expect(visibleReports("PROPERTY_MANAGER").map(e => e.key)).toEqual(["balance-sheet", "property-pl", "property-statement", "payables-aging"]);
    expect(visibleReports("ACCOUNTANT").map(e => e.key)).toContain("tenant-ledger");
    expect(visibleReports("RENTER")).toEqual([]);
});
it("drives the Accounting nav's report items", () => {
    expect(buildAccountingNav("TENANT_ADMIN", { booksLive: true }).find(g => g.id === "registers")!.items.map(i => i.id))
        .toEqual(["general-ledger", "tenant-ledger", "trial-balance", "cheque-registers"]);
});
// hub.test.tsx (session role via vi.mock("next-auth/react"), englishIntl)
it("shows an accountant the tenant ledger with its three formats", () => { /* getByTestId("report-card-tenant-ledger") has "Excel", "PDF", "CSV"; link href "/dashboard/finance/tenant-ledger" */ });
it("shows a property manager only scoped reports, each noted", () => { /* no report-card-tenant-ledger; report-card-property-pl contains "Your assigned properties only" */ });
it("searches titles and descriptions", () => { /* type "cheque" → only tenant-ledger; type "zzz" → "No report matches “zzz”." */ });
it("refuses a renter", () => { /* getByTestId("page-access-denied") */ });
```
Expectation updates: `models.test.ts` finalReports → `["reports-hub", "balance-sheet", "company-pl", "property-pl", "property-statement", "vat-return"]` and the PM href list gains `"/dashboard/reports"`; `navModel.test.ts` TENANT_ADMIN accounting list gains `"reports-hub"` before `"balance-sheet"`, PROPERTY_MANAGER → `["aging", "penalties", "reports-hub", "balance-sheet", "property-pl", "property-statement"]`; `rbacParity.test.ts` `NEW_DESTINATIONS` gains `"/dashboard/reports": "/dashboard/finance/reports/property-pl"` with the comment "the Reports hub lists pages the role already had (canViewReportsHub = the union of the catalog's gates)".
- [ ] **Step 2: Run** `cd web && npx vitest run src/lib/reports src/app/[locale]/dashboard/reports src/lib/nav src/lib/__tests__/help-coverage.test.ts src/lib/__tests__/help-arabic-articles.test.ts` → FAIL.
- [ ] **Step 3: Implement** catalog, rbac key, nav, route, help entry, article text and the hub page.
- [ ] **Step 4: Run** the Step 2 command plus `npx vitest run src/lib/__tests__` → PASS.
- [ ] **Step 5: Commit** `feat(web): report catalog and the Reports hub, wired into nav, routes, help and RBAC`.

---

### Task 12: Web — Tenant Ledger page: PACT receivable view + "All accounts"

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/tenant-ledger/page.tsx` (becomes the view switch)
- Create: `web/src/app/[locale]/dashboard/finance/tenant-ledger/_components/AllAccountsView.tsx` (today's `page.tsx:20-153` body moved verbatim, behaviour unchanged)
- Create: `…/_components/ReceivableView.tsx`, `…/_components/TenantLedgerFilters.tsx`, `…/_components/query.ts`
- Modify test: `web/src/app/[locale]/dashboard/finance/tenant-ledger/__tests__/on-demand.test.tsx`; Create: `…/__tests__/receivable-view.test.tsx`, `…/__tests__/query.test.ts`

**Interfaces:**
- Consumes: `reportsApi.tenantLedger`, `ReportShell`, `ExportMenu`, `GroupedReportTable` (Task 10), `defaultLedgerRange` (`components/finance/LedgerFilters.tsx:21`), `RenterPicker`, `LeasePicker`, `AccountPicker`, `useNameLookup("properties")`, `hasPermission(role, "canAccessFinance")`.
- Produces:
  ```ts
  // _components/query.ts
  export type TenantLedgerView = "receivable" | "all";
  export function viewFrom(sp: URLSearchParams): TenantLedgerView;       // "all" when view=all, or no view and a leaseId (the lease "Ledger" link)
  export function readQuery(sp: URLSearchParams): { q: TenantLedgerQuery; page: number };  // defaults from defaultLedgerRange()
  export function toUrl(q: TenantLedgerQuery): Record<string, string | undefined>;          // the filter keys, for useReportQuery
  export function hasScope(q: TenantLedgerQuery): boolean;               // renterId || propertyId || leaseId || accountIds.length
  ```
- URL keys: `view`, `from`, `to`, `propertyId`, `accountIds` (comma), `renterId`, `leaseId`, `onlyWithBalance=1`, `hideReversed=1`, `page` (0-based, omitted when 0).
- `page.tsx`: uses only `useSearchParams` and `window.history.replaceState` (the existing tests mock nothing else from `next/navigation`); resolves the view once on mount with `viewFrom`, holds it in state and writes `view` into the URL straight away (so an "All accounts" apply that drops `leaseId` cannot flip the view); title `Ledger.tenantLedger`; a two-button segmented control (`aria-pressed`) `Reports.tlViewReceivable` / `Reports.tlViewAll`; renders `ReceivableView` or `AllAccountsView`; switching writes `view` and keeps `from/to/renterId/leaseId`.
- `ReceivableView`: draft/applied `TenantLedgerQuery` through `useReportQuery(sp => readQuery(sp).q, toUrl)` (Task 10); `TenantLedgerFilters` (from/to date inputs; property `<select>`; receivable accounts as chips fed by `reportsApi.tenantLedger.accounts(propertyId)` plus `AccountPicker` labelled `tlOtherAccount`; `RenterPicker` labelled `tlTenantOptional`; `LeasePicker` labelled `tlContract`; checkboxes `tlOnlyWithBalance`, `tlHideReversed`; Apply). Apply → `apply()` + page 0 (`page` and `view=receivable` written alongside). Fetch only when `hasScope(applied)`, with an `AbortController` per request; `ReportShell` state idle (`tlPickScope`) / loading / error / empty (no `row` and no `band` items) / ready; `GroupedReportTable` with `groupsLabel = t("tlGroups", { from: firstGroup+1, to: lastGroup+1, total: totalGroups })` and `onPage` → refetch + URL; `ExportMenu` options from the **applied** query: xlsx (`locale`), pdf `en`, pdf `ar`, csv (`locale`); fallback names `tenant-ledger.<ext>`; disabled until a grid is loaded.

- [ ] **Step 1: Write the failing tests / update the existing ones** (`vi.mock("@/lib/api/reports", …)` with `get`, `accounts` spies, `fileUrl` real; `get` resolves the Task 10 fixture grid; `vi.mock("@/lib/download", () => ({ saveResponse: vi.fn() }))`; `vi.mock("@/components/finance/AccountPicker", () => ({ default: () => null, loadAccounts: async () => [] }))`; the `useNameLookup` mock returns `options: [{ id: "p1", label: "Sample Tower" }]`; the other mocks as in `on-demand.test.tsx`):

```tsx
// query.test.ts
it("keeps the lease deep link on All accounts", () => {
    expect(viewFrom(new URLSearchParams("renterId=r1&leaseId=l1"))).toBe("all");
    expect(viewFrom(new URLSearchParams("renterId=r1"))).toBe("receivable");
    expect(viewFrom(new URLSearchParams("view=receivable&leaseId=l1"))).toBe("receivable");
    expect(readQuery(new URLSearchParams("accountIds=a,b&onlyWithBalance=1&page=2")).q.accountIds).toEqual(["a", "b"]);
});
// receivable-view.test.tsx
it("opens on the PACT view and fetches nothing until a scope is picked", async () => {
    render(<Page />);
    expect(await screen.findByText("Pick a tenant, a property or an account to run the ledger")).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled(); expect(renter).not.toHaveBeenCalled();
});
it("runs for a property over the last 12 months and keeps the filters in the URL", async () => {
    // select the property option, click Apply
    const range = defaultLedgerRange();
    await waitFor(() => expect(get).toHaveBeenCalledWith(expect.objectContaining({ from: range.from, to: range.to, propertyId: "p1" }), 0, "en", expect.anything()));
    expect(Object.fromEntries(new URL(window.location.href).searchParams)).toEqual({ view: "receivable", propertyId: "p1", from: range.from, to: range.to });
    expect(await screen.findByTestId("grid-total")).toBeInTheDocument();
});
it("exports the applied filter", async () => {
    // after a load: open Export, click "Excel (PACT layout)"
    expect(global.fetch).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/proxy\/v1\/finance\/reports\/tenant-ledger\.xlsx\?.*propertyId=p1.*lang=en/));
});
it("pages by tenant groups", async () => { /* page 1 of 4 → Next → get called with page 1; URL page=1; "Tenants 1–16 of 41" */ });
it("switches to All accounts and back without losing the tenant", async () => { /* query "renterId=r1&view=receivable" → click "All accounts" → renter("r1", …) called; URL view=all */ });
```
In `on-demand.test.tsx`: the test "fetches the picked tenant over the last 12 months…" sets `query.current = "view=all"` first and expects the URL to equal `{ view: "all", renterId: "r1", from, to }`; "names the linked tenant in the sub-band…" uses `query.current = "view=all&renterId=r3"`; the two `renterId&leaseId` tests stay as they are (no `view` + `leaseId` → All accounts); "asks for a tenant first" uses `query.current = "view=all"`.
- [ ] **Step 2: Run** `cd web && npx vitest run src/app/[locale]/dashboard/finance/tenant-ledger` → FAIL.
- [ ] **Step 3: Implement** `query.ts`, the move to `AllAccountsView.tsx`, `TenantLedgerFilters`, `ReceivableView` and the new `page.tsx`. `LeaseJournalsTab` and the lease page link (`leases/[id]/page.tsx:586`) are not edited.
- [ ] **Step 4: Run** the Step 2 command, then `npx vitest run src/components/leases` (LeaseJournalsTab unaffected) → PASS; `npx tsc --noEmit` clean.
- [ ] **Step 5: Commit** `feat(web): Tenant Ledger opens on PACT's receivable view; All accounts keeps the statement`.

---

### Task 13: Docs and final verification

**Files:**
- Add: `docs/superpowers/specs/2026-10-26-pact-reports-design.md` (already on disk, untracked), `docs/superpowers/plans/2026-10-26-pact-reports-pr-a-engine-tenant-ledger.md` (this plan)
- Modify: `CLAUDE.md:29` — `(next: \`93-*.yaml\`)` → `(next: \`176-*.yaml\`)` (highest changeset on the branch is `175-parking-attached-unit.yaml`; this PR adds none)

- [ ] **Step 1: Edit** `CLAUDE.md:29` as above.
- [ ] **Step 2: Commit** `git add docs/superpowers/specs/2026-10-26-pact-reports-design.md docs/superpowers/plans/2026-10-26-pact-reports-pr-a-engine-tenant-ledger.md && git commit -m "docs: PACT report parity spec and PR A plan; next changeset is 176" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- CLAUDE.md docs/superpowers/specs/2026-10-26-pact-reports-design.md docs/superpowers/plans/2026-10-26-pact-reports-pr-a-engine-tenant-ledger.md`
- [ ] **Step 3: Backend full suite** (after the Gradle wait check), run in the background — it is long: `cd backend && ./gradlew test` → BUILD SUCCESSFUL. Read the counts from the Gradle report (`backend/build/reports/tests/test/index.html`), not from grep.
- [ ] **Step 4: Golden gate**: `cd backend && ./gradlew test -PincludeTags=golden` → BUILD SUCCESSFUL, including `theTenantLedgerReportReproducesTheReceivableRows`; `git diff --stat main -- backend/src/test/resources/golden` is empty.
- [ ] **Step 5: Web gates**, each must pass: `cd web && npx vitest run` (= `npm test`), `npx tsc --noEmit`, `npm run lint`, `node scripts/check-i18n-parity.mjs`, `npm run build` (`next build`).
- [ ] **Step 6: Screen check** (dev stack, synthetic org): Tenant Ledger and Reports hub at 1366 px and 390 px, `/en` and `/ar` — no page-level horizontal scroll, bands readable, export each format once (xlsx opens in Excel/LibreOffice with the title band, white header row, Dr/Cr balances, dd-mm-yyyy Doc Date).
- [ ] **Step 7:** No commit unless Steps 3–6 forced fixes (commit those with the task's own message prefix).
