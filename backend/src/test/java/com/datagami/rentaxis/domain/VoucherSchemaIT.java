package com.datagami.rentaxis.domain;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class VoucherSchemaIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;

    private UUID tenant() {
        // landlord_org.slug is NOT NULL (changeset 37a, added after this brief was written) —
        // the brief's two-column insert fails with "null value in column slug"; adapted to match
        // LedgerSchemaIT's tenant() helper, which already carries a slug for the same reason.
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name, slug) VALUES (?, ?, ?)", id, "T-" + id, "t-" + id);
        return id;
    }

    private UUID account(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, tenant_id, code, name, account_type, is_group, is_active, is_system, display_order) "
                + "VALUES (?,?,?,?,?,false,true,false,0)", id, tenant, code, "Acct " + code, "EXPENSE");
        return id;
    }

    private UUID voucher(UUID tenant, String docType, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO vouchers (id, tenant_id, doc_type, doc_date, status, created_at) "
                + "VALUES (?,?,?,CURRENT_DATE,?,now())", id, tenant, docType, status);
        return id;
    }

    @Test
    void voucherAndLinesRoundTrip() {
        UUID t = tenant();
        UUID v = voucher(t, "PISR", "DRAFT");
        UUID a = account(t, "VS-1");
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,1000.00,5.00,50.00)", UUID.randomUUID(), t, v, a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_lines WHERE voucher_id = ?", Integer.class, v))
                .isEqualTo(1);
    }

    @Test
    void deletingAVoucherDeletesItsLinesAndAttachments() {
        UUID t = tenant();
        UUID v = voucher(t, "BPV", "DRAFT");
        UUID a = account(t, "VS-2");
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,500.00,0,0)", UUID.randomUUID(), t, v, a);
        jdbc.update("INSERT INTO voucher_attachments (id, tenant_id, voucher_id, name, file_url, uploaded_at) "
                + "VALUES (?,?,?,'Invoice.pdf','/x',now())", UUID.randomUUID(), t, v);
        jdbc.update("DELETE FROM vouchers WHERE id = ?", v);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_lines WHERE voucher_id = ?", Integer.class, v)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_attachments WHERE voucher_id = ?", Integer.class, v)).isZero();
    }

    @Test
    void aLineAmountMustBePositive() {
        UUID t = tenant();
        UUID v = voucher(t, "PISR", "DRAFT");
        UUID a = account(t, "VS-3");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                        + "VALUES (?,?,?,1,?,0,0,0)", UUID.randomUUID(), t, v, a))
                .hasMessageContaining("ck_voucher_lines_amount_positive");
    }

    /**
     * The unique index is on (voucher_id, line_no), not on line_no alone. A YAML
     * quoting slip that truncated a flow-mapping columnNames list to its first
     * column would leave this constraint enforcing "line_no unique across the
     * whole table" instead — so this asserts BOTH halves: a second voucher can
     * reuse line_no 1 (proves voucher_id is really part of the key), and a
     * second line_no 1 on the SAME voucher is rejected (proves line_no is too).
     */
    @Test
    void lineNumbersAreUniqueWithinAVoucherButNotAcrossVouchers() {
        UUID t = tenant();
        UUID v = voucher(t, "BPV", "DRAFT");
        UUID otherVoucher = voucher(t, "BPV", "DRAFT");
        UUID a = account(t, "VS-4");
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,10.00,0,0)", UUID.randomUUID(), t, v, a);

        // Same line_no on a DIFFERENT voucher must succeed — proves voucher_id is part of the key.
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,1,?,15.00,0,0)", UUID.randomUUID(), t, otherVoucher, a);

        // A second line_no 2 on the FIRST voucher must also succeed — the constraint must not
        // have collapsed to "one line per voucher" either.
        jdbc.update("INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                + "VALUES (?,?,?,2,?,20.00,0,0)", UUID.randomUUID(), t, v, a);

        // The real duplicate: same voucher_id AND same line_no must fail.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO voucher_lines (id, tenant_id, voucher_id, line_no, account_id, amount, vat_rate, vat_amount) "
                        + "VALUES (?,?,?,1,?,20.00,0,0)", UUID.randomUUID(), t, v, a))
                .hasMessageContaining("ux_voucher_lines_voucher_line_no");
    }
}
