package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S19-01 review P3-3: clearing the session mid-import changes nothing that is written or posted.
 *
 * <p>{@code ContractImportPersistService} flushes and clears its persistence context every
 * few rows so the import stays linear. That is only safe if nothing is mutated through a
 * reference kept from before a clear, and if every pending write is flushed before the
 * clear. Counting rows cannot show either, so this imports the same workbook into two
 * fresh organisations — once clearing every {@value #CHUNK} rows, so clears land in the
 * middle of every phase (properties, units, renters, both contract passes, the cheque
 * pass), and once
 * never clearing — posts both batches, and compares the rows themselves.</p>
 *
 * <p>Each row is reduced to a natural key and its columns as JSON, with ids, timestamps,
 * "by" columns and per-tenant sequence numbers taken out, and with references replaced by
 * what they point at (a contract reference, a unit number, an account name). The
 * {@code version} columns stay in: a lease or cheque saved one time more or less on one
 * path is a difference this must see.</p>
 */
@SpringBootTest
class ContractImportChunkEquivalenceIT extends AbstractPostgresIT {

    /** Prime, and smaller than every phase, so clears never line up with a phase boundary. */
    static final int CHUNK = 7;
    static final int CONTRACTS = 37;
    /** More than {@link #CHUNK}, so the properties phase clears too (S19 review P3-2). */
    static final int PROPERTIES = 9;

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportValidator validator;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    @Test
    void aChunkedImportWritesAndPostsExactlyWhatAnUnchunkedOneDoes() throws Exception {
        List<String> chunked = importAndPost("QXCHK", CHUNK);
        List<String> whole = importAndPost("QXWHL", ContractImportPersistService.NEVER_CLEAR);

        assertThat(chunked).as("the fingerprint covers every kind of row")
                .anyMatch(r -> r.startsWith("L|")).anyMatch(r -> r.startsWith("LL|"))
                .anyMatch(r -> r.startsWith("C|")).anyMatch(r -> r.startsWith("U|"))
                .anyMatch(r -> r.startsWith("BL|")).anyMatch(r -> r.startsWith("BE|"))
                .anyMatch(r -> r.startsWith("B|")).anyMatch(r -> r.startsWith("J|"))
                .anyMatch(r -> r.startsWith("P|")).anyMatch(r -> r.startsWith("PM|"))
                .anyMatch(r -> r.startsWith("A|"));
        assertThat(chunked.stream().filter(r -> r.startsWith("L|"))).hasSize(CONTRACTS);
        assertThat(chunked.stream().filter(r -> r.startsWith("P|"))).hasSize(PROPERTIES);
        assertThat(PROPERTIES).as("a clear lands inside the properties phase").isGreaterThan(CHUNK);
        System.out.println("S19-01 equivalence rows by kind: " + chunked.stream().collect(
                java.util.stream.Collectors.groupingBy(r -> r.substring(0, r.indexOf('|')),
                        java.util.TreeMap::new, java.util.stream.Collectors.counting())));
        List<String> onlyChunked = new ArrayList<>(chunked);
        whole.forEach(onlyChunked::remove);
        List<String> onlyWhole = new ArrayList<>(whole);
        chunked.forEach(onlyWhole::remove);
        assertThat(onlyChunked).as("rows only the chunked import has (it lacks %s)", onlyWhole).isEmpty();
        assertThat(onlyWhole).as("rows only the unchunked import has").isEmpty();
        assertThat(chunked).containsExactlyElementsOf(whole);
    }

    private List<String> importAndPost(String tag, int clearEvery) throws Exception {
        UUID tenantId = fixture.newCutOverTenant("EQ-" + tag);
        fixture.authenticateAsTenantAdmin();
        UUID batchId;
        try (Workbook wb = workbook(tag)) {
            assertThat(validator.validate(wb).errors()).as("the generated workbook validates").isEmpty();
            ContractImportPersistService.ContractImportSummary s =
                    contractPersist.persist(wb, fixture.newJob(), List.of(), clearEvery);
            assertThat(s.contractsCreated()).isEqualTo(CONTRACTS);
            batchId = s.batchId();
        }
        assertThat(postService.post(batchId).leasesFailed()).as("leases that failed to post").isZero();
        List<String> rows = fingerprint(tenantId);
        // The tag only keeps the two organisations' names apart; it is not a difference.
        // (A tag that is not a substring of any real value: "ONE" would also rewrite NONE.)
        return rows.stream()
                .map(r -> r.replace(tag, "TAG").replace(tag.toLowerCase(java.util.Locale.ROOT), "tag"))
                .sorted().toList();
    }

    private List<String> fingerprint(UUID tenantId) {
        // Strips ids, timestamps, actors and per-tenant numbering; keeps everything else, version included.
        String n = "(select coalesce(jsonb_object_agg(key, value order by key), '{}'::jsonb)::text"
                + " from jsonb_each(to_jsonb(%s)) where key !~ '(_id|_at|_by|^id)$'"
                + " and key not in ('entry_number','contract_number','receipt_number','posted_by_name',"
                + "'payee_mismatch_confirmed_by_name'))";
        List<String> out = new ArrayList<>();
        String[] queries = {
                "select 'P|'||p.name_en||'|'||" + n.formatted("p") + " from properties p where p.tenant_id=?",
                // Every role of every property, and the account it lands on — the sheet's
                // names and the template's generated leaves alike.
                "select 'PM|'||p.name_en||'|'||m.role||'|'||a.name||'|'||" + n.formatted("m")
                        + " from property_account_mappings m join properties p on p.id=m.property_id"
                        + " join accounts a on a.id=m.account_id where p.tenant_id=?",
                "select 'A|'||a.name||'|'||coalesce(pa.name,'-')||'|'||coalesce(p.name_en,'-')||'|'||" + n.formatted("a")
                        + " from accounts a left join accounts pa on pa.id=a.parent_id"
                        + " left join properties p on p.id=a.property_id where a.tenant_id=?",
                "select 'U|'||p.name_en||'|'||coalesce(b.name_en,'-')||'|'||u.unit_number||'|'||" + n.formatted("u")
                        + " from units u join properties p on p.id=u.property_id left join buildings b on b.id=u.building_id"
                        + " where u.tenant_id=?",
                "select 'L|'||l.external_contract_ref||'|'||u.unit_number||'|'||r.email||'|'||" + n.formatted("l")
                        + " from leases l join units u on u.id=l.unit_id join renters r on r.id=l.renter_id"
                        + " where l.tenant_id=?",
                "select 'LL|'||l.external_contract_ref||'|'||coalesce(a.name,'-')||'|'||" + n.formatted("ll")
                        + " from lease_lines ll join leases l on l.id=ll.lease_id"
                        + " left join accounts a on a.id=ll.credit_account_id where l.tenant_id=?",
                "select 'C|'||l.external_contract_ref||'|'||coalesce(a.name,'-')||'|'||" + n.formatted("c")
                        + " from cheques c join leases l on l.id=c.lease_id"
                        + " left join accounts a on a.id=c.debit_account_id where l.tenant_id=?",
                "select 'B|'||" + n.formatted("ib") + " from import_batches ib where ib.tenant_id=?",
                "select 'BL|'||l.external_contract_ref from import_batch_leases bl"
                        + " join import_batches ib on ib.id=bl.batch_id join leases l on l.id=bl.lease_id"
                        + " where ib.tenant_id=?",
                "select 'BE|'||be.entity_type||'|'||coalesce(p.name_en, b.name_en, u.unit_number, r.email, '?')"
                        + " from import_batch_entities be join import_batches ib on ib.id=be.batch_id"
                        + " left join properties p on p.id=be.entity_id left join buildings b on b.id=be.entity_id"
                        + " left join units u on u.id=be.entity_id left join renters r on r.id=be.entity_id"
                        + " where ib.tenant_id=?",
                "select 'J|'||coalesce(l.external_contract_ref,'-')||'|'||coalesce(c.seq_no::text,'-')||'|'||a.name"
                        + "||'|'||coalesce(ca.name,'-')||'|'||" + n.formatted("e") + "||'|'||" + n.formatted("jl")
                        + " from journal_lines jl join journal_entries e on e.id=jl.journal_entry_id"
                        + " join accounts a on a.id=jl.account_id left join accounts ca on ca.id=jl.contra_account_id"
                        + " left join leases l on l.id=jl.lease_id left join cheques c on c.id=jl.cheque_id"
                        + " where e.tenant_id=?",
        };
        for (String q : queries) {
            out.addAll(jdbc.queryForList(q, String.class, tenantId));
        }
        return out;
    }

    /**
     * {@value #CONTRACTS} contracts over {@value #PROPERTIES} properties (the first and the
     * last naming their accounts, the rest left to the template), with spare
     * units, a VAT-bearing commercial contract every fourth row, a parking line every third,
     * a discount every fifth, and cheques in every importable state (registered, deposited,
     * cleared, bounced, and transfers that cleared).
     */
    private static Workbook workbook(String tag) {
        Workbook wb = new XSSFWorkbook();
        Sheet p = wb.createSheet("Properties");
        row(p, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber",
                "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount", "BankAccount",
                "PdcReceivableAccount", "SecurityDepositAccount");
        String[] properties = new String[PROPERTIES];
        for (int k = 0; k < PROPERTIES; k++) {
            properties[k] = (k == 0 ? "Equiv Tower " : "Equiv Court " + (char) ('A' + k) + " ") + tag;
            // The first and the last name their accounts: the last one is written after
            // a clear, so its mappings go through account references from before it.
            boolean named = k == 0 || k == PROPERTIES - 1;
            if (named) {
                row(p, k + 1, properties[k], "", "DUBAI", "Plot " + (k + 1), "MIXED", "",
                        "Rental Income ST1", "Rent Receivable - ST1", "Advance Rent - ST1", "Sample Bank - ST1",
                        "PDC Receivable ST1", "Security Deposit ST1");
            } else {
                row(p, k + 1, properties[k], "", "SHARJAH", "Plot " + (k + 1), "RESIDENTIAL", "",
                        "", "", "", "", "", "");
            }
        }

        Sheet u = wb.createSheet("Units");
        row(u, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        Sheet r = wb.createSheet("Renters");
        row(r, 0, "Name", "NameAr", "Email", "Phone");
        Sheet c = wb.createSheet("Contracts");
        row(c, 0, "ContractNumber", "EjariNumber", "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
                "ContractDate", "StartDate", "EndDate", "GracePeriodDays", "LineNo", "ChargeTypeCode",
                "CreditAccount", "GrossAmount", "DiscountAmount", "VatApplicable", "Narration", "RenterName");
        Sheet q = wb.createSheet("Cheques");
        row(q, 0, "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate", "PayeeBank",
                "DebitAccount", "Amount", "Narration", "Mode", "Status", "DepositedDate", "ClearedDate",
                "BouncedDate");

        int ur = 1, cr = 1, qr = 1;
        int units = CONTRACTS + 6;   // some stay vacant
        for (int i = 1; i <= units; i++) {
            String property = properties[i % PROPERTIES];
            String building = i % 2 == 0 ? (i % 4 == 0 ? "North" : "South") : "";
            String unit = String.valueOf(200 + i);
            row(u, ur++, property, building, unit, i % 4 == 0 ? "OFFICE" : "STUDIO", String.valueOf(400 + i),
                    String.valueOf(30000 + i * 100));
            if (i > CONTRACTS) continue;

            String email = "eq." + tag.toLowerCase() + "." + i + "@example.invalid";
            String number = "EQ-" + tag + "-" + i;
            boolean vat = i % 4 == 0;
            boolean parking = i % 3 == 0;
            BigDecimal rent = BigDecimal.valueOf(36000 + i * 600L);
            BigDecimal discount = i % 5 == 0 ? BigDecimal.valueOf(600) : BigDecimal.ZERO;
            BigDecimal deposit = BigDecimal.valueOf(2000);
            BigDecimal park = BigDecimal.valueOf(1200);
            row(r, i, "Equiv Renter " + i, "", email, "+97150000" + String.format("%04d", i));

            row(c, cr++, number, "EJ-" + i, property, building, unit, email, "2026-01-20", "2026-02-01",
                    "2027-01-31", i % 6 == 0 ? "" : "5", "1", "RENT", "", money(rent), money(discount),
                    String.valueOf(vat), "Annual rent", "");
            row(c, cr++, number, "", "", "", "", "", "", "", "", "", "2", "SECURITY_DEPOSIT", "",
                    money(deposit), "0", "false", "Security deposit", "");
            if (parking) {
                row(c, cr++, number, "", "", "", "", "", "", "", "", "", "3", "PARKING_FEE", "",
                        money(park), "0", String.valueOf(vat), "Parking bay", "");
            }

            BigDecimal factor = vat ? new BigDecimal("1.05") : BigDecimal.ONE;
            BigDecimal rentPart = rent.subtract(discount).multiply(factor)
                    .add(parking ? park.multiply(factor) : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal each = rentPart.divide(BigDecimal.valueOf(3), 2, RoundingMode.DOWN);
            BigDecimal last = rentPart.subtract(each.multiply(BigDecimal.valueOf(2)));
            boolean transfer = i % 7 == 0;
            String mode = transfer ? "TRANSFER" : "PDC";

            row(q, qr++, number, "1", "2026-01-20", "6" + String.format("%05d", i * 10), "2026-01-20", "ENBD", "",
                    money(deposit), "Deposit", "PDC", "CLEARED", "2026-01-21", "2026-01-23", "");
            String[] s2 = i % 9 == 0
                    ? new String[]{"BOUNCED", "2026-02-01", "", "2026-02-04"}
                    : transfer ? new String[]{"CLEARED", "", "2026-02-01", ""}
                    : new String[]{"CLEARED", "2026-02-01", "2026-02-03", ""};
            row(q, qr++, number, "2", "2026-01-20", transfer ? "" : "6" + String.format("%05d", i * 10 + 1),
                    "2026-02-01", "ENBD", "", money(each), "Rent 1/3", mode, s2[0], s2[1], s2[2], s2[3]);
            String[] s3 = transfer ? new String[]{"CLEARED", "", "2026-06-01", ""}
                    : new String[]{"DEPOSITED", "2026-06-01", "", ""};
            row(q, qr++, number, "3", "2026-01-20", transfer ? "" : "6" + String.format("%05d", i * 10 + 2),
                    "2026-06-01", "ENBD", "", money(each), "Rent 2/3", mode, s3[0], s3[1], s3[2], s3[3]);
            row(q, qr++, number, "4", "2026-01-20", transfer ? "" : "6" + String.format("%05d", i * 10 + 3),
                    "2026-11-01", "ENBD", "", money(last), "Rent 3/3", mode, "REGISTERED", "", "", "");
        }
        return wb;
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void row(Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
