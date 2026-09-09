package com.datagami.rentaxis.core.email.render;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.dispatch.TenantBranding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EmailRendererTest {

    // Boots against a container of its own. Without this the test used whatever
    // Postgres happened to be listening on localhost:5432 — on a developer
    // machine that is often an unrelated project's database, and on CI it is
    // nothing at all, so the context failed to load with a ConnectException.
    // A @SpringBootTest that needs a database has to bring one.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");


    @Autowired EmailRenderer renderer;

    @Test
    void rendersUserInvitedInEnglishWithTenantBranding() {
        EmailTemplateContext ctx = new EmailTemplateContext(
                EmailEventType.USER_INVITED,
                Locale.ENGLISH,
                UUID.randomUUID(),
                "Sara",
                "sara@example.com",
                "https://app.test",
                new TenantBranding("Acme PM", "https://example/logo.png"),
                "https://app.test/api/v1/email/unsubscribe?token=abc",
                Map.of("setPasswordUrl", "https://app.test/set-password?token=xyz",
                       "__subjectArgs", new Object[]{"Acme PM"}),
                null
        );

        EmailRenderResult result = renderer.render(ctx);

        assertThat(result.subject()).isNotBlank();
        assertThat(result.html()).contains("Sara").contains("Acme PM");
    }

    @Test
    void rendersInArabicAndUsesRtlLayout() {
        EmailTemplateContext ctx = new EmailTemplateContext(
                EmailEventType.USER_INVITED,
                new Locale("ar"),
                UUID.randomUUID(),
                "سارة",
                "sara@example.com",
                "https://app.test",
                new TenantBranding("شركة الأمل", "https://example/logo.png"),
                "https://app.test/api/v1/email/unsubscribe?token=abc",
                Map.of("setPasswordUrl", "https://app.test/set-password?token=xyz",
                       "__subjectArgs", new Object[]{"شركة الأمل"}),
                null
        );

        EmailRenderResult result = renderer.render(ctx);

        assertThat(result.html()).contains("dir=\"rtl\"");
    }
}
