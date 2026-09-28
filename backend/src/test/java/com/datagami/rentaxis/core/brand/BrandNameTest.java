package com.datagami.rentaxis.core.brand;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.dispatch.PayloadVarsExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product is Miftah (مفتاح) on everything a person reads: email subjects and
 * bodies, the email chrome, the receipt and contract PDFs (Kunal, 2026-09-28).
 * Code identifiers — the package, env vars, the database — keep the old name and
 * are not checked here.
 */
class BrandNameTest {

    private static final Pattern OLD = Pattern.compile("RentAxis|رينت\\s*أكسس", Pattern.CASE_INSENSITIVE);

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Properties props(String path) throws IOException {
        Properties p = new Properties();
        try (var in = new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    @Test
    void emailMessagesNameMiftahInEnglishAndArabic() throws IOException {
        Properties en = props("messages/email_en.properties");
        Properties ar = props("messages/email_ar.properties");
        for (Properties p : new Properties[]{en, ar}) {
            p.forEach((k, v) -> assertThat(OLD.matcher(String.valueOf(v)).find()).as("%s", k).isFalse());
        }
        assertThat(en.getProperty("email.user_welcomed.subject")).isEqualTo("Welcome to Miftah");
        assertThat(ar.getProperty("email.user_welcomed.subject")).contains("مفتاح");
        assertThat(en.getProperty("email.password_reset_requested.subject")).contains("Miftah");
        assertThat(ar.getProperty("email.password_reset_requested.subject")).contains("مفتاح");
        assertThat(en.getProperty("email.brand")).isEqualTo("Miftah");
        assertThat(ar.getProperty("email.brand")).isEqualTo("مفتاح");
    }

    @Test
    void emailLayoutsAndPdfTemplatesCarryTheNewName() throws IOException {
        for (String t : new String[]{
                "templates/email/layout/master.html",
                "templates/email/layout/master-rtl.html",
                "templates/email/events/user_invited.html",
                "templates/email-template.html",
                "templates/receipt-template.html",
                "templates/contract-template.html"}) {
            assertThat(OLD.matcher(resource(t)).find()).as(t).isFalse();
        }
        assertThat(resource("templates/email/layout/master.html")).contains("Miftah");
        assertThat(resource("templates/email/layout/master-rtl.html")).contains("مفتاح");
        assertThat(resource("templates/receipt-template.html")).contains("Miftah Property Management System");
    }

    @Test
    void anInviteWithNoCompanyFallsBackToTheBrandInTheRecipientsLanguage() {
        Object[] en = (Object[]) PayloadVarsExtractor.extract(EmailEventType.USER_INVITED, null, "https://app.test", "en")
                .get("__subjectArgs");
        Object[] ar = (Object[]) PayloadVarsExtractor.extract(EmailEventType.USER_INVITED, null, "https://app.test", "ar")
                .get("__subjectArgs");
        assertThat(en).containsExactly("Miftah");
        assertThat(ar).containsExactly("مفتاح");
        Map<String, Object> vars = PayloadVarsExtractor.extract(EmailEventType.USER_INVITED, null, "https://app.test", "en");
        assertThat(vars).doesNotContainValue("RentAxis");
    }
}
