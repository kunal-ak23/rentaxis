package com.datagami.rentaxis.domain;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger's invariants are enforced by the database, not by the service
 * layer, so they are asserted against a real Postgres: a balance check that
 * fires once at commit (an entry's lines are inserted one at a time), a
 * one-side-per-line check constraint, and immutability triggers that make
 * "reverse, never edit" impossible to bypass — including from psql.
 */
@SpringBootTest
class LedgerSchemaIT extends AbstractPostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name, slug) VALUES (?, ?, ?)", id, "T-" + id, "t-" + id);
        return id;
    }

    private UUID account(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, tenant_id, code, name, account_type, is_group, is_active, is_system, display_order) VALUES (?,?,?,?,?,false,true,false,0)",
                id, tenant, code, "Acct " + code, "ASSET");
        return id;
    }

    private UUID entry(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entries (id, tenant_id, entry_number, doc_type, entry_date, status, created_at) VALUES (?,?,?,?,CURRENT_DATE,'POSTED',now())",
                id, tenant, "JV-26/" + id.toString().substring(0, 6), "JV");
        return id;
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        UUID t = tenant(); UUID a = account(t, "1"); UUID b = account(t, "2"); UUID e = entry(t);
        assertThatThrownBy(() -> jdbc.execute(
                "BEGIN; " +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',1,'" + a + "',100,0);" +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',2,'" + b + "',0,90);" +
                "COMMIT;"))
                .hasMessageContaining("journal entry is not balanced");
    }

    @Test
    void balancedEntryCommits() {
        UUID t = tenant(); UUID a = account(t, "3"); UUID b = account(t, "4"); UUID e = entry(t);
        jdbc.execute("BEGIN; " +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',1,'" + a + "',100,0);" +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',2,'" + b + "',0,100);" +
                "COMMIT;");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM journal_lines WHERE journal_entry_id = ?", Integer.class, e);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void lineWithBothSidesIsRejected() {
        UUID t = tenant(); UUID a = account(t, "5"); UUID e = entry(t);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES (?,?,?,1,?,10,10)",
                UUID.randomUUID(), t, e, a))
                .hasMessageContaining("ck_journal_lines_one_side");
    }

    @Test
    void postedLinesCannotBeUpdatedOrDeleted() {
        UUID t = tenant(); UUID a = account(t, "6"); UUID b = account(t, "7"); UUID e = entry(t);
        UUID l1 = UUID.randomUUID();
        jdbc.execute("BEGIN; " +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + l1 + "','" + t + "','" + e + "',1,'" + a + "',50,0);" +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',2,'" + b + "',0,50);" +
                "COMMIT;");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_lines SET debit = 60 WHERE id = ?", l1))
                .hasMessageContaining("journal lines are immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_lines WHERE id = ?", l1))
                .hasMessageContaining("journal lines are immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET entry_date = entry_date - 1 WHERE id = ?", e))
                .hasMessageContaining("journal entries are immutable");
        // status / reversed_by_id are the only mutable columns
        jdbc.update("UPDATE journal_entries SET status = 'REVERSED', reversed_by_id = ? WHERE id = ?", e, e);
    }

    @Test
    void accountsHaveParentIdAndPropertyId() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'accounts' AND column_name IN ('parent_id','property_id','alias')",
                Integer.class);
        assertThat(n).isEqualTo(3);
        Integer old = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'accounts' AND column_name IN ('parent_code','hierarchy_level')",
                Integer.class);
        assertThat(old).isZero();
    }

    @Test
    void journalLinesCarryAContraAccount() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'journal_lines' AND column_name = 'contra_account_id'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
