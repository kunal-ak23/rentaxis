package com.datagami.rentaxis.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity used as a request body must not let a client set its id.
 *
 * <p>Six controllers bind the JPA entity directly as the request DTO, so the
 * writable surface is whatever the entity happens to expose rather than what
 * the operation intends. The sharp edge is the id: several create methods are
 * a bare {@code repository.save(entity)}, and Hibernate treats a save with an
 * id present as an update — so POSTing a body carrying an existing id turns
 * "create" into "silently overwrite a different row in my own tenant".</p>
 *
 * <p>This is written as an invariant rather than an allowlist of the six known
 * controllers. An allowlist would let a seventh appear unprotected as long as
 * nobody remembered to add it; this way, binding a new entity as a request body
 * forces its id to be READ_ONLY first. Converting these endpoints to real
 * request DTOs — the documented project convention — would satisfy it by
 * removing the entity binding altogether, which is the better end state.</p>
 *
 * <p>Reads source rather than reflecting over a Spring context: the property is
 * about what the code declares, and this way it needs neither Docker nor a
 * booted application.</p>
 */
class RequestBodyEntityExposureTest {

    private static final Path API = Paths.get("src/main/java/com/datagami/rentaxis/api");
    private static final Path ENTITIES = Paths.get("src/main/java/com/datagami/rentaxis/domain/entity");

    /**
     * Entities whose id is protected by an explicit service-layer rejection
     * instead of READ_ONLY, because for these silently ignoring a supplied id
     * is worse than refusing it: a client echoing back a fetched transaction
     * would quietly create a duplicate ledger entry instead of getting an
     * error. The exemption is not taken on trust — a test below asserts the
     * rejection actually exists.
     */
    private static final Set<String> REJECTS_SUPPLIED_ID = Set.of("FinancialTransaction");

    /** `@RequestBody Foo foo`, with optional annotations such as @Valid in between. */
    private static final Pattern REQUEST_BODY = Pattern.compile(
            "@RequestBody\\s+(?:@\\w+(?:\\([^)]*\\))?\\s+)*([A-Z]\\w*)\\s+\\w+");

    private Set<String> entitiesBoundAsRequestBodies() throws IOException {
        Set<String> bound = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(API)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = REQUEST_BODY.matcher(Files.readString(file));
                while (m.find()) {
                    String type = m.group(1);
                    if (Files.exists(ENTITIES.resolve(type + ".java"))) {
                        bound.add(type);
                    }
                }
            }
        }
        return bound;
    }

    @Test
    void everyEntityBoundAsARequestBodyHasAReadOnlyId() throws IOException {
        Set<String> bound = entitiesBoundAsRequestBodies();

        // If this drops to zero because the endpoints moved to real DTOs, the
        // assertion below is vacuous — say so rather than passing silently.
        assertThat(bound)
                .as("entities bound as request bodies; if empty, this test can be deleted")
                .isNotEmpty();

        List<String> unprotected = new ArrayList<>();
        for (String entity : bound) {
            if (REJECTS_SUPPLIED_ID.contains(entity)) continue;

            String source = Files.readString(ENTITIES.resolve(entity + ".java"));
            int idAt = source.indexOf("@Id");
            if (idAt < 0) continue;

            // The annotation has to sit on the id field specifically, so look
            // only at the declaration immediately preceding @Id.
            String beforeId = source.substring(Math.max(0, idAt - 400), idAt);
            boolean readOnly = beforeId.contains("JsonProperty")
                    && beforeId.contains("READ_ONLY");
            if (!readOnly) unprotected.add(entity);
        }

        assertThat(unprotected)
                .as("these entities are bound as request bodies with a client-writable id")
                .isEmpty();
    }

    /**
     * The exemption above has to be earned. If the rejection is removed,
     * FinancialTransaction is left with a client-writable id and nothing
     * catching it — so this fails rather than the exemption quietly becoming a
     * hole.
     */
    @Test
    void theServiceLevelExemptionIsRealAndNotJustAnAllowlistEntry() throws IOException {
        Path service = Paths.get(
                "src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java");
        String source = Files.readString(service);

        assertThat(source)
                .as("FinancialTransactionService must refuse a client-supplied transaction id")
                .contains("txn.getId() != null");
        assertThat(source)
                .as("and it must throw rather than silently ignore it")
                .contains("A transaction id cannot be supplied when posting to the ledger");
    }

    @Test
    void accountCannotBeMarkedSystemByAClient() throws IOException {
        // isSystem gates "system accounts cannot be modified/deleted". Accepting
        // it from a request body let a caller create a row that updateAccount
        // and deleteAccount both refuse to touch.
        String source = Files.readString(ENTITIES.resolve("Account.java"));

        // Two properties have to be blocked, and they are blocked in two
        // different places — see the entity for why. Checking one location
        // would pass while the other stayed writable.
        int field = source.indexOf("private boolean isSystem");
        assertThat(field).as("Account.isSystem should exist").isGreaterThan(0);
        assertThat(source.substring(Math.max(0, field - 200), field))
                .as("the field-named property must be ignored")
                .contains("JsonIgnore");

        int getter = source.indexOf("public boolean isSystem()");
        assertThat(getter).as("Account should declare an explicit isSystem getter").isGreaterThan(0);
        assertThat(source.substring(Math.max(0, getter - 300), getter))
                .as("the accessor-named property must serialize but not bind")
                .contains("READ_ONLY");
    }
}
