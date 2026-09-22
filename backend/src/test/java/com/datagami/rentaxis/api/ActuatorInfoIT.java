package com.datagami.rentaxis.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /actuator/info} is the release marker: it carries the build
 * version and the git commit the jar was built from ({@code -PgitSha} in
 * Docker, the repository otherwise), and it is reachable without a session so
 * a deploy can be confirmed with one curl.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ActuatorInfoIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Test
    @SuppressWarnings("unchecked")
    void infoIsPublicAndNamesTheBuild() {
        ResponseEntity<Map> res = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/actuator/info").retrieve().toEntity(Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> build = (Map<String, Object>) res.getBody().get("build");
        assertThat(build).as("build info block").isNotNull();
        assertThat(build.get("version")).isEqualTo("0.2.0-SNAPSHOT");
        // build-info.properties writes build.git.commit; the endpoint nests dotted keys.
        Object git = build.get("git");
        Object commit = git instanceof Map<?, ?> g ? g.get("commit") : build.get("git.commit");
        assertThat(String.valueOf(commit)).as("git commit stamp").isNotBlank().isNotEqualTo("null").matches("[0-9a-f]{7,40}|dev");
    }
}
