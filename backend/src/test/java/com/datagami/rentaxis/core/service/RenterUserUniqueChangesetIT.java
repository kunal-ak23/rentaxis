package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.testsupport.SharedPostgres;
import liquibase.command.CommandScope;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset {@code 162-renters-user-unique} against a real Liquibase run on a
 * database of its own (PR #389 review, prod safety):
 * <ul>
 *   <li>clean data: the index is created;</li>
 *   <li>existing duplicate links: the changeset is skipped (onFail: CONTINUE),
 *       the update still succeeds, and the rest of 162 (the payee columns) is
 *       applied;</li>
 *   <li>once the duplicates are gone, the next update creates the index.</li>
 * </ul>
 * A fresh database, so the shared test database's data and history are untouched.
 */
class RenterUserUniqueChangesetIT {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private static String url(String db) {
        String base = SharedPostgres.INSTANCE.getJdbcUrl();
        return base.substring(0, base.lastIndexOf('/') + 1) + db;
    }

    private static Connection connect(String db) throws Exception {
        return DriverManager.getConnection(url(db), SharedPostgres.INSTANCE.getUsername(),
                SharedPostgres.INSTANCE.getPassword());
    }

    private static void update(String db) throws Exception {
        try (Connection c = connect(db)) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new CommandScope("update")
                    .addArgumentValue("database", database)
                    .addArgumentValue("changelogFile", CHANGELOG)
                    .execute();
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean indexExists(Connection c) throws Exception {
        return count(c, "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_renters_user_id'") == 1;
    }

    private static boolean ran(Connection c, String id) throws Exception {
        return count(c, "SELECT count(*) FROM databasechangelog WHERE id = '" + id + "'") == 1;
    }

    @Test
    void cleanDataGetsTheIndex_duplicatesSkipItWithoutStoppingTheUpdate_andCleanedDataGetsItNext() throws Exception {
        String db = "lb162_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = connect(SharedPostgres.INSTANCE.getDatabaseName()); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + db);
        }
        try {
            // (a) clean data: everything runs, the index exists.
            update(db);
            try (Connection c = connect(db)) {
                assertThat(indexExists(c)).isTrue();
                assertThat(ran(c, "162-renters-user-unique")).isTrue();
                assertThat(ran(c, "162-cheque-payee-check")).isTrue();
            }

            // (b) a database as prod might be before 162-renters-user-unique: no
            // index, that changeset not run, and one user linked to two Tenants.
            UUID user = UUID.randomUUID();
            try (Connection c = connect(db); Statement s = c.createStatement()) {
                s.execute("DROP INDEX ux_renters_user_id");
                s.execute("DELETE FROM databasechangelog WHERE id = '162-renters-user-unique'");
                // Seeded rows only: foreign keys off for this session.
                s.execute("SET session_replication_role = replica");
                UUID tenant = UUID.randomUUID();
                for (int i = 0; i < 2; i++) {
                    s.execute("INSERT INTO renters (id, tenant_id, name_en, primary_language, user_id, created_at) VALUES ('"
                            + UUID.randomUUID() + "', '" + tenant + "', 'Dup " + i + "', 'EN', '" + user + "', now())");
                }
            }
            update(db); // does not throw: the precondition skips, the update carries on
            try (Connection c = connect(db)) {
                assertThat(indexExists(c)).isFalse();
                assertThat(ran(c, "162-renters-user-unique")).as("skipped, not marked ran").isFalse();
                assertThat(ran(c, "162-cheque-payee-check")).isTrue();
                assertThat(count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'cheques'"
                        + " AND column_name IN ('payee_name', 'payee_check', 'payee_mismatch_confirmed_at')")).isEqualTo(3);
            }

            // (c) duplicates resolved: the next update creates the index.
            try (Connection c = connect(db); Statement s = c.createStatement()) {
                s.execute("DELETE FROM renters WHERE user_id = '" + user + "'");
            }
            update(db);
            try (Connection c = connect(db)) {
                assertThat(indexExists(c)).isTrue();
                assertThat(ran(c, "162-renters-user-unique")).isTrue();
            }
        } finally {
            try (Connection admin = connect(SharedPostgres.INSTANCE.getDatabaseName()); Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
            }
        }
    }
}
