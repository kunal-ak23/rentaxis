package com.datagami.rentaxis.testsupport;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container for the whole test JVM.
 *
 * <p><b>Why this exists.</b> Every integration test used to declare its own
 * {@code @Container static PostgreSQLContainer} — 117 of them. Each one started a
 * fresh Postgres, ran all ~90 Liquibase changesets against it, and — because each
 * class's {@code @ServiceConnection} produced different connection details — gave
 * Spring a distinct context that could not be cached. So the suite paid, 117
 * times over, for a container start, a full migration and a full application
 * context load. That is the ~6-hour full run (roughly three minutes per class)
 * against a ~20-minute one for the same tests.</p>
 *
 * <p><b>What this does.</b> A single container, started once in a static
 * initializer and left running until the JVM exits (Ryuk removes it then). Every
 * test that connects through this one field sees identical connection details, so
 * the migrations run once and Spring caches the context across all of them.</p>
 *
 * <p><b>How to use it.</b> Extend {@link AbstractPostgresIT}, or reference this
 * field from a {@code @ServiceConnection} in a class that already has its own base
 * class. A test must <em>not</em> also declare its own {@code @Container} — that
 * is the very thing this replaces.</p>
 *
 * <p><b>The rule that comes with it.</b> Every class shares this one database, in
 * the order Gradle runs them, and nothing truncates between classes. A test may
 * assert only about rows it can name — its own tenant, its own ids, its own dedup
 * keys — never about a whole table ("the lease table is empty", "the sweep
 * expired exactly two"), and must not assume a shared lock row (ShedLock) is free.
 * A class that declared a private {@code @Container} alongside a
 * {@code @SpringBootTest} did not get a private database either: its context was
 * served from the cache, wired to this container, so its private container started
 * and was never used. There is no opting out; scope the assertion instead.</p>
 */
public final class SharedPostgres {

    /**
     * Package-private ImageName kept as a constant so the two historical tags
     * ({@code postgres:16} and {@code postgres:16-alpine}) collapse to one — two
     * images would otherwise mean two containers and two cached contexts.
     */
    static final String IMAGE = "postgres:16-alpine";

    /**
     * {@code max_connections} is raised well above Postgres's default 100.
     *
     * <p>This is the direct consequence of sharing one server. Spring caches a
     * context per distinct configuration — up to 32 of them by default — and every
     * cached context keeps its own Hikari pool open for the whole run. Against a
     * per-class container those pools were spread over a hundred servers and no
     * ceiling was ever near; against one, 32 contexts times a five-connection pool
     * is already 160, and the run died with "FATAL: sorry, too many clients
     * already" — a context-load failure, so whole classes errored out rather than
     * failing an assertion.</p>
     */
    @ServiceConnection
    public static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>(IMAGE).withCommand("postgres", "-c", "max_connections=400");

    static {
        INSTANCE.start();
    }

    private SharedPostgres() {
    }
}
