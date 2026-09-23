package com.datagami.rentaxis.testsupport;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The raw {@code sql} changes of one Liquibase changeset file, in order.
 *
 * <p>For testing a data backfill against rows a test controls: Liquibase runs
 * the changeset once, on an empty database, at context start, so the only way
 * to exercise its SQL on real data is to run the same text again inside a
 * transaction the test rolls back.
 */
public final class ChangesetSql {

    private ChangesetSql() { }

    @SuppressWarnings("unchecked")
    public static List<String> of(String fileName) {
        String path = "db/changelog/changesets/" + fileName;
        try (InputStream in = ChangesetSql.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("No changeset " + path);
            Map<String, Object> root = new Yaml().load(in);
            List<String> out = new ArrayList<>();
            for (Object entry : (List<Object>) root.get("databaseChangeLog")) {
                Map<String, Object> changeSet = (Map<String, Object>) ((Map<String, Object>) entry).get("changeSet");
                if (changeSet == null) continue;
                for (Object change : (List<Object>) changeSet.get("changes")) {
                    Map<String, Object> sql = (Map<String, Object>) ((Map<String, Object>) change).get("sql");
                    if (sql != null) out.add((String) sql.get("sql"));
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
