package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S18-03: a display name keeps exactly one extension, whatever characters it contains. */
class AttachmentNamesTest {

    private static String name(String display) {
        return AttachmentNames.downloadName(display, ".pdf", "attachment");
    }

    @Test
    void questionMarkInTheNameDoesNotDoubleTheExtension() {
        assertThat(name("quote*2026?.pdf")).isEqualTo("quote*2026?.pdf");
    }

    @Test
    void hashInTheNameDoesNotDoubleTheExtension() {
        assertThat(name("invoice #12.pdf")).isEqualTo("invoice #12.pdf");
        assertThat(name("a#b?c.PDF")).isEqualTo("a#b?c.PDF");
    }

    @Test
    void asteriskAndMultipleDotsKeepOneExtension() {
        assertThat(name("a*b.pdf")).isEqualTo("a*b.pdf");
        assertThat(name("report.final.v2.pdf")).isEqualTo("report.final.v2.pdf");
        assertThat(name("report.final.v2")).isEqualTo("report.final.v2.pdf");
    }

    @Test
    void noExtensionGetsTheStoredOne() {
        assertThat(name("aadhar")).isEqualTo("aadhar.pdf");
        assertThat(name("what?")).isEqualTo("what?.pdf");
        assertThat(name("c#")).isEqualTo("c#.pdf");
    }

    @Test
    void arabicNameKeepsOneExtension() {
        assertThat(name("عقد الإيجار.pdf")).isEqualTo("عقد الإيجار.pdf");
        assertThat(name("عقد؟.pdf")).isEqualTo("عقد؟.pdf");
        assertThat(name("عقد الإيجار")).isEqualTo("عقد الإيجار.pdf");
    }

    @Test
    void windowsPathDoesNotConfuseExtensionDetection() {
        assertThat(name("C:\\Users\\tess\\Desktop\\report.final.v2.pdf"))
                .isEqualTo("C:\\Users\\tess\\Desktop\\report.final.v2.pdf");
        assertThat(name("C:\\dir.v2\\report")).isEqualTo("C:\\dir.v2\\report.pdf");
    }

    @Test
    void storedUrlsStillIgnoreQueryAndFragment() {
        assertThat(AttachmentNames.extensionOf("https://x.blob/c/a.pdf?sv=1&sig=a.b")).isEqualTo(".pdf");
        assertThat(AttachmentNames.extensionOf("a.PNG#frag")).isEqualTo(".png");
    }

    @Test
    void contentDispositionStaysSafe() {
        String h = AttachmentNames.contentDisposition("attachment", name("quote*2026?.pdf"));
        assertThat(h).isEqualTo("attachment; filename=\"quote_2026_.pdf\"; filename*=UTF-8''quote%2A2026%3F.pdf");
        String evil = AttachmentNames.contentDisposition("attachment", "a\"\r\nSet-Cookie: x=1;b.pdf");
        assertThat(evil).doesNotContain("\r").doesNotContain("\n");
        assertThat(evil.substring(evil.indexOf("filename=\"") + 10, evil.indexOf("\";"))).doesNotContain("\"");
        assertThat(AttachmentNames.contentDisposition("attachment", "عقد.pdf"))
                .contains("filename=\"___.pdf\"").contains("filename*=UTF-8''%D8%B9%D9%82%D8%AF.pdf");
    }
}
