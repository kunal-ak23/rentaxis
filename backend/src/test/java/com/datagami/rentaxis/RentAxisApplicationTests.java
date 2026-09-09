package com.datagami.rentaxis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class RentAxisApplicationTests {

    // Boots against a container of its own. Without this the test used whatever
    // Postgres happened to be listening on localhost:5432 — on a developer
    // machine that is often an unrelated project's database, and on CI it is
    // nothing at all, so the context failed to load with a ConnectException.
    // A @SpringBootTest that needs a database has to bring one.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");


	@Test
	void contextLoads() {
	}

}
