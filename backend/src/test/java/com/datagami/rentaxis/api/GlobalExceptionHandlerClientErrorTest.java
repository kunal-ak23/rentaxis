package com.datagami.rentaxis.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.executable.ExecutableValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Pins the four framework-raised *caller-error* exceptions to 400 with the
 * house error body, and — the sharper half — pins that none of them echoes the
 * raw exception text back to the caller.
 *
 * <p>Both halves matter for the same reason. These are thrown before any
 * application code runs, so before this handler existed they fell through to
 * {@link GlobalExceptionHandler#handleRuntime}, which answers 500 and copies
 * {@code ex.getMessage()} into the body. Their messages are not fit for that.
 * The bodies actually returned before the fix, verbatim:
 *
 * <pre>
 * JSON parse error: Cannot deserialize value of type `int` from String "not-a-number"
 * Method parameter 'id': Failed to convert value of type 'java.lang.String' to
 *     required type 'java.util.UUID'; Invalid UUID string: definitely-not-a-uuid
 * internalSearchProbe.cursor: Cursor is required
 * </pre>
 *
 * <p>That is the rejected payload fragment, the required Java type, and the
 * controller's own parameter path. Several endpoints reaching this advice are
 * unauthenticated (see {@code PublicRateLimitFilter}: {@code /public/**} and
 * the Firebase token exchange), so that text is readable by anyone.
 *
 * <p>Standalone MockMvc rather than a direct call on the handler: invoking
 * {@code handleTypeMismatch(ex)} by hand would prove the body is safe but not
 * that the exception ever reaches it. The routing IS the fix for three of these
 * four — they are RuntimeExceptions, and only a more specific
 * {@code @ExceptionHandler} keeps the catch-all from claiming them first.
 */
class GlobalExceptionHandlerClientErrorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @RestController
    static class ProbeController {

        record InternalPayload(String name, int count) {
        }

        @PostMapping("/probe/body")
        String body(@RequestBody InternalPayload payload) {
            return "ok";
        }

        @GetMapping("/probe/uuid/{id}")
        String uuid(@PathVariable UUID id) {
            return "ok";
        }

        @GetMapping("/probe/param")
        String param(@RequestParam String token) {
            return "ok";
        }

        @GetMapping("/probe/constraint")
        String constraint() {
            throw new ConstraintViolationException(realMethodValidationViolations());
        }

        @GetMapping("/probe/boom")
        String boom() {
            // Shaped like the exceptions that actually reach the catch-all in
            // this codebase: Hibernate naming a table, a column and a
            // constraint. Nothing in this string may reach the caller.
            throw new IllegalStateException(
                    "could not execute statement [ERROR: duplicate key value violates unique "
                            + "constraint \"uq_promo_impression_per_day\"  Detail: Key "
                            + "(ad_id, renter_user_id, day)=(...) already exists.]");
        }
    }

    /**
     * A real violation set from the executable validator, not a hand-written
     * message — the point of the test is the *path* Hibernate Validator puts
     * in front of the message ("internalSearchProbe.cursor"), and only a genuine
     * method-validation run produces it.
     */
    static class ProbeTarget {
        public String internalSearchProbe(@NotBlank(message = "Cursor is required") String cursor) {
            return "ok";
        }
    }

    private static Set<ConstraintViolation<?>> realMethodValidationViolations() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ExecutableValidator executables = factory.getValidator().forExecutables();
            Method method = ProbeTarget.class.getMethod("internalSearchProbe", String.class);
            return Set.copyOf(executables.validateParameters(
                    new ProbeTarget(), method, new Object[]{"   "}));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unparseableJsonBodyIs400AndDoesNotEchoJacksonDetail() throws Exception {
        MvcResult result = mvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"count\":\"not-a-number\"}"))
                .andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(HttpMessageNotReadableException.class);
        String message = assertHouseBadRequestBody(result);
        assertRawExceptionTextWithheld(result);
        // The rejected fragment is caller payload quoted straight back out.
        assertThat(message).doesNotContain("not-a-number");
        assertThat(message).doesNotContain("JSON parse error");
    }

    @Test
    void nonUuidPathVariableIs400AndDoesNotEchoTheRequiredJavaType() throws Exception {
        MvcResult result = mvc.perform(get("/probe/uuid/{id}", "definitely-not-a-uuid")).andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(MethodArgumentTypeMismatchException.class);
        String message = assertHouseBadRequestBody(result);
        assertRawExceptionTextWithheld(result);
        assertThat(message).doesNotContain("java.util.UUID");
        assertThat(message).doesNotContain("definitely-not-a-uuid");
    }

    @Test
    void missingRequiredRequestParameterIs400WithTheHouseBody() throws Exception {
        MvcResult result = mvc.perform(get("/probe/param")).andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(MissingServletRequestParameterException.class);
        assertHouseBadRequestBody(result);
        assertRawExceptionTextWithheld(result);
    }

    @Test
    void constraintViolationIs400AndDoesNotEchoTheViolationPath() throws Exception {
        MvcResult result = mvc.perform(get("/probe/constraint")).andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(ConstraintViolationException.class);
        String message = assertHouseBadRequestBody(result);
        assertRawExceptionTextWithheld(result);
        // The author-written message is for the caller and survives...
        assertThat(message).contains("Cursor is required");
        // ...the violation path, which names our method, does not.
        assertThat(message).doesNotContain("internalSearchProbe");
    }

    /**
     * Asserts 400 and the exact body shape the other handlers on this advice
     * emit — {@code error}/{@code message}/{@code status}, nothing more — so a
     * client branching on the error contract sees no new shape. Returns the
     * message for the caller's leak assertions.
     */
    private String assertHouseBadRequestBody(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> body = MAPPER.readValue(
                result.getResponse().getContentAsString(), new TypeReference<>() {
                });
        assertThat(body).containsOnlyKeys("error", "message", "status");
        assertThat(body.get("error")).isEqualTo(true);
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("message")).isInstanceOf(String.class);
        return (String) body.get("message");
    }

    /** The exception's own text must not appear in what the caller receives. */
    private void assertRawExceptionTextWithheld(MvcResult result) throws Exception {
        Exception resolved = result.getResolvedException();
        assertThat(resolved).isNotNull();
        assertThat(resolved.getMessage()).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(resolved.getMessage());
    }
    @Test
    void unhandledExceptionIs500AndDisclosesNothingAboutTheCause() throws Exception {
        // The catch-all used to copy ex.getMessage() into the body. Anything
        // unhandled reaching this advice is assumed to be something an
        // anonymous caller must not read: Hibernate carries table, column and
        // constraint names, NPEs name our own fields, and the payment/storage
        // SDKs carry endpoint URLs and request ids. This advice is reachable
        // unauthenticated via the public paths in PublicRateLimitFilter.
        // Matches the house style in this file: assert on the response object
        // rather than pulling in a MockMvcResultMatchers static import for one
        // call site.
        var response = mvc.perform(get("/probe/boom")).andReturn().getResponse();
        String raw = response.getContentAsString();

        assertThat(response.getStatus()).isEqualTo(500);
        Map<String, Object> body = MAPPER.readValue(raw, new TypeReference<>() {
        });
        assertThat(body).containsOnlyKeys("error", "message", "status");
        assertThat(body.get("error")).isEqualTo(true);
        assertThat(body.get("status")).isEqualTo(500);
        assertThat(body.get("message")).isEqualTo("An internal error occurred");

        // Not merely "the message differs" — none of the leaked nouns survive.
        assertThat(raw)
                .doesNotContain("uq_promo_impression_per_day")
                .doesNotContain("duplicate key")
                .doesNotContain("renter_user_id")
                .doesNotContain("could not execute statement");
    }

}
