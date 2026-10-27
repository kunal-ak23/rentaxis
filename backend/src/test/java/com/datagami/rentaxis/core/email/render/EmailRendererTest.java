package com.datagami.rentaxis.core.email.render;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.dispatch.TenantBranding;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EmailRendererTest extends AbstractPostgresIT {

    // Boots against a container of its own. Without this the test used whatever
    // Postgres happened to be listening on localhost:5432 — on a developer
    // machine that is often an unrelated project's database, and on CI it is
    // nothing at all, so the context failed to load with a ConnectException.
    // A @SpringBootTest that needs a database has to bring one.

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

    /** Miftah rebrand (2026-09-28): the chrome, subject and no-organisation fallback name Miftah / مفتاح. */
    @Test
    void anInviteWithNoOrganisationNamesMiftahInEnglishAndArabic() {
        java.util.function.Function<Locale, EmailRenderResult> render = locale -> renderer.render(new EmailTemplateContext(
                EmailEventType.USER_INVITED, locale, UUID.randomUUID(), "Sara", "sara@example.com", "https://app.test",
                null, null,
                Map.of("setPasswordUrl", "https://app.test/set-password?token=xyz",
                        "__subjectArgs", com.datagami.rentaxis.core.email.dispatch.PayloadVarsExtractor
                                .extract(EmailEventType.USER_INVITED, null, "https://app.test", locale.getLanguage())
                                .get("__subjectArgs")),
                null));
        EmailRenderResult en = render.apply(Locale.ENGLISH);
        EmailRenderResult ar = render.apply(new Locale("ar"));
        assertThat(en.subject()).contains("Miftah").doesNotContain("RentAxis");
        assertThat(en.html()).contains("<b>Miftah</b>").contains("Miftah Property Management System")
                .doesNotContain("RentAxis");
        assertThat(ar.subject()).contains("مفتاح").doesNotContain("RentAxis");
        assertThat(ar.html()).contains("<b>مفتاح</b>").doesNotContain("RentAxis");
    }

    /**
     * 2026-10-27: an invite from "Miftah Sandbox" arrived as "You're invited to Miftah on
     * Miftah". The subject read a companyName the invite payload never carries and fell back
     * to the product name, while the body named the organisation from its branding.
     */
    private EmailRenderResult invite(Locale locale, TenantBranding branding, String role) {
        Map<String, Object> vars = new java.util.HashMap<>(
                com.datagami.rentaxis.core.email.dispatch.PayloadVarsExtractor.extract(
                        EmailEventType.USER_INVITED,
                        new com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload(
                                UUID.randomUUID(), "Mohamed", "/auth/set-password?token=t", "t", role),
                        "https://app.test", locale.getLanguage()));
        return renderer.render(new EmailTemplateContext(
                EmailEventType.USER_INVITED, locale, UUID.randomUUID(), "Mohamed", "promoter@example.test",
                "https://app.test", branding, null, vars, null));
    }

    @Test
    void anInviteSubjectNamesTheOrganisationLikeItsBody() {
        EmailRenderResult en = invite(Locale.ENGLISH, new TenantBranding("Miftah Sandbox", null), "TENANT_ADMIN");
        assertThat(en.subject()).isEqualTo("You're invited to Miftah Sandbox on Miftah");
        assertThat(en.html()).contains("<b>Miftah Sandbox</b>");

        EmailRenderResult ar = invite(new Locale("ar"), new TenantBranding("Miftah Sandbox", null), "TENANT_ADMIN");
        assertThat(ar.subject()).contains("Miftah Sandbox");
    }

    @Test
    void anInviteWithABlankOrganisationNameStillNamesMiftah() {
        EmailRenderResult en = invite(Locale.ENGLISH, new TenantBranding("  ", null), "TENANT_ADMIN");
        assertThat(en.subject()).isEqualTo("You're invited to Miftah on Miftah");
        assertThat(en.html()).contains("<b>Miftah</b>");
    }

    @Test
    void aPromoterInviteSaysWhoInvitedThemAndThatAdsAreApproved() {
        EmailRenderResult en = invite(Locale.ENGLISH, new TenantBranding("Miftah Sandbox", null), "PROMOTER");
        assertThat(en.subject()).isEqualTo("Miftah Sandbox invited you to advertise on Miftah");
        assertThat(en.html())
                .contains("Promote your business on Miftah")
                .contains("<b>Miftah Sandbox</b> has invited you to promote your business to its tenants on Miftah")
                .contains("goes live once Miftah Sandbox approves it")
                .doesNotContain("Welcome to Miftah");

        EmailRenderResult ar = invite(new Locale("ar"), new TenantBranding("Miftah Sandbox", null), "PROMOTER");
        assertThat(ar.subject()).isEqualTo("Miftah Sandbox يدعوك للإعلان على مفتاح");
        assertThat(ar.html()).contains("روّج لنشاطك التجاري على مفتاح").contains("<b>Miftah Sandbox</b>");
    }

    @Test
    void anOrganisationNameIsEscapedInTheInviteBody() {
        EmailRenderResult en = invite(Locale.ENGLISH, new TenantBranding("A&B <Props>", null), "PROMOTER");
        assertThat(en.html()).contains("<b>A&amp;B &lt;Props&gt;</b>").doesNotContain("<Props>");
    }

    /** Break-it R2 re-review N2: a termination that withdrew a renewal awaiting signature says so, EN and AR. */
    @Test
    void leaseTerminatedNamesAWithdrawnRenewalInEnglishAndArabic() {
        java.util.function.BiFunction<Locale, String, String> html = (locale, term) -> {
            Map<String, Object> vars = new java.util.HashMap<>(Map.of(
                    "unitLabel", "A-203", "propertyName", "Tower", "ctaUrl", "https://app.test/en/dashboard/leases/x",
                    "__subjectArgs", new Object[]{"A-203", "Tower"}));
            if (term != null) vars.put("withdrawnRenewalTerm", term);
            return renderer.render(new EmailTemplateContext(EmailEventType.LEASE_TERMINATED, locale, UUID.randomUUID(),
                    "Ahmed", "ahmed@example.com", "https://app.test", new TenantBranding("Acme PM", null),
                    "https://app.test/api/v1/email/unsubscribe?token=abc", vars, null)).html();
        };
        assertThat(html.apply(Locale.ENGLISH, "02/10/2027 – 01/10/2028"))
                .contains("The renewal for <b>02/10/2027 – 01/10/2028</b> that was awaiting your signature has been withdrawn");
        assertThat(html.apply(new Locale("ar"), "02/10/2027 – 01/10/2028"))
                .contains("تم سحب تجديد العقد للفترة <b>02/10/2027 – 01/10/2028</b>");
        assertThat(html.apply(Locale.ENGLISH, null)).doesNotContain("withdrawn");
    }
}
