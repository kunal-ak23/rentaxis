package com.datagami.rentaxis;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RentAxisApplicationTests extends AbstractPostgresIT {

    // Boots against a container of its own. Without this the test used whatever
    // Postgres happened to be listening on localhost:5432 — on a developer
    // machine that is often an unrelated project's database, and on CI it is
    // nothing at all, so the context failed to load with a ConnectException.
    // A @SpringBootTest that needs a database has to bring one.

	@Test
	void contextLoads() {
	}

}
