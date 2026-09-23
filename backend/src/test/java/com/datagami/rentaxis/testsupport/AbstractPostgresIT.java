package com.datagami.rentaxis.testsupport;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for an integration test that needs Postgres.
 *
 * <p>Extend this instead of declaring a per-class {@code @Container}. It exposes
 * the one JVM-wide container from {@link SharedPostgres} through a
 * {@code @ServiceConnection} field, so Spring Boot wires the datasource to it and
 * — because the connection details are the same for every subclass — caches a
 * single application context across the whole suite rather than rebuilding one per
 * class. See {@link SharedPostgres} for the cost this removes.</p>
 *
 * <p>A subclass keeps its own {@code @SpringBootTest} (with whatever slice or
 * properties it needs) and adds nothing else about the database. It must not
 * re-declare a container.</p>
 */
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = SharedPostgres.INSTANCE;
}
