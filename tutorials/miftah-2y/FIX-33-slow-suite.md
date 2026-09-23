# #33 — the backend suite takes ~6 hours; root cause and the fix

## Root cause (measured)

The suite has **117 integration-test classes, each with its own
`@Container static PostgreSQLContainer`**, and no container reuse configured.
Every class therefore:

1. starts a fresh Postgres container (~2–5 s),
2. runs all ~90 Liquibase changesets against that empty database, and
3. because its `@ServiceConnection` container has different connection details
   from every other class, gets a **distinct Spring `ApplicationContext` that
   cannot be cached** — so the full application context is built 117 times.

At roughly three minutes per class that is the ~6-hour run. All 117 declarations
are plain `new PostgreSQLContainer<>("postgres:16"|"postgres:16-alpine")` with no
init scripts or custom settings, so a single shared container can serve them all.

## The fix (built and proven in this branch)

`testsupport/SharedPostgres` holds one container for the whole JVM, started once
in a static initializer. `testsupport/AbstractPostgresIT` exposes it through a
single `@ServiceConnection`. An IT extends `AbstractPostgresIT` and declares no
container of its own, so:

- the container starts **once**,
- the migrations run **once**, and
- because every subclass sees identical connection details, Spring caches **one**
  application context across the whole suite.

### Proof

Three converted classes — `UnitOrderingIT`, `PropertyAccountServiceIT`,
`UnitListingAdminFilterIT` — run together:

- `Creating container for image` (excluding Ryuk): **1** (was 3, one per class).
- Distinct Hikari pools: **1** (`HikariPool-1`) — a per-class context rebuild
  would create `HikariPool-1/-2/-3`. One pool ⇒ one cached context.
- All tests pass; 3 classes in 48 s (~16 s/class) vs ~3 min/class in the full run.

## Rolling out the remaining 114 (mechanical, one PR, verify with a full CI run)

For each remaining `*IT` that declares its own container, apply exactly what the
three sample classes show:

1. add `import com.datagami.rentaxis.testsupport.AbstractPostgresIT;`
2. delete the four now-unused imports: `PostgreSQLContainer`, `Container`,
   `Testcontainers`, and `ServiceConnection` (only if unused elsewhere in the file);
3. delete the `@Testcontainers` class annotation;
4. delete the `@Container @ServiceConnection static PostgreSQLContainer<?> … ;` field;
5. make the class `extends AbstractPostgresIT` (or, if it already extends a base,
   have that base extend `AbstractPostgresIT` instead of holding its own container).

Watch for the long tail: a class that already extends another base, one that uses
`@DataJpaTest` rather than `@SpringBootTest`, or one that adds `@MockBean` (which
legitimately forks the context cache and is fine). This is why the 114-file sweep
is left as its own reviewable change verified by a full CI run, rather than applied
blind here — the only way to confirm the whole suite is to run the whole suite.
