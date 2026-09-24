package com.datagami.rentaxis.core.service.recognition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F14-27: what the last recognition pass did for each organisation — the day it
 * ran for, how many entries it posted, and what it could not post. The job
 * writes it; the catch-up and the recognition status read it.
 */
@Component
public class RecognitionRunLog {

    /** The last pass for one organisation. {@code errors} is one line per refused entry. */
    public record LastRun(LocalDate runFor, Instant finishedAt, int posted, int failed, List<String> errors) { }

    private final NamedParameterJdbcTemplate jdbc;

    public RecognitionRunLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID tenantId, LocalDate runFor, int posted, List<String> errors) {
        List<String> errs = errors == null ? List.of() : errors;
        jdbc.update("""
                insert into recognition_runs (tenant_id, run_for, finished_at, posted, failed, errors)
                values (:t, :d, now(), :p, :f, :e)
                on conflict (tenant_id) do update set run_for = excluded.run_for, finished_at = excluded.finished_at,
                    posted = excluded.posted, failed = excluded.failed, errors = excluded.errors""",
                new MapSqlParameterSource("t", tenantId).addValue("d", runFor).addValue("p", posted)
                        .addValue("f", errs.size()).addValue("e", errs.isEmpty() ? null : String.join("\n", errs)));
    }

    /** The earliest "last run" across organisations that have one: a pass that missed any of them is behind. */
    /**
     * F14-63: whether any organisation has no pass recorded for {@code today} — its
     * last one is for an earlier day, or it has none at all (the first night after a
     * deploy that created {@code recognition_runs}, or an organisation added since).
     */
    public boolean anyBehind(LocalDate today) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from landlord_org o
                               left join recognition_runs r on r.tenant_id = o.id
                               where r.run_for is null or r.run_for < :d)""",
                new MapSqlParameterSource("d", today), Boolean.class));
    }

    public LocalDate oldestLastRunFor() {
        return jdbc.getJdbcTemplate().queryForObject("select min(run_for) from recognition_runs", LocalDate.class);
    }

    public Optional<LastRun> last(UUID tenantId) {
        return jdbc.query("select run_for, finished_at, posted, failed, errors from recognition_runs where tenant_id = :t",
                new MapSqlParameterSource("t", tenantId), (rs, i) -> {
                    String e = rs.getString("errors");
                    return new LastRun(rs.getObject("run_for", LocalDate.class),
                            rs.getTimestamp("finished_at").toInstant(), rs.getInt("posted"), rs.getInt("failed"),
                            e == null ? List.of() : List.of(e.split("\n")));
                }).stream().findFirst();
    }
}
