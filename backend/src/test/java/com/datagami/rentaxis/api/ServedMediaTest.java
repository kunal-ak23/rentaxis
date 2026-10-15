package com.datagami.rentaxis.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The served type comes from the bytes, from an allow-list, never from a name or a claim. */
class ServedMediaTest {

    private static byte[] bytes(int... b) {
        byte[] out = new byte[Math.max(b.length, 12)];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    @Test
    void rasterImagesAreRecognised() {
        assertThat(ServedMedia.sniff(bytes(0x89, 'P', 'N', 'G'), false)).contains("image/png");
        assertThat(ServedMedia.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0), false)).contains("image/jpeg");
        assertThat(ServedMedia.sniff(bytes('G', 'I', 'F', '8'), false)).contains("image/gif");
        assertThat(ServedMedia.sniff(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'), false))
                .contains("image/webp");
    }

    @Test
    void aPdfOnlyWhereTheCallerAllowsIt() {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
        assertThat(ServedMedia.sniff(pdf, true)).contains("application/pdf");
        assertThat(ServedMedia.sniff(pdf, false)).isEmpty();
    }

    @Test
    void markupAndAnythingElseIsRefused() {
        assertThat(ServedMedia.sniff("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8), true)).isEmpty();
        assertThat(ServedMedia.sniff("<html><script>".getBytes(StandardCharsets.UTF_8), true)).isEmpty();
        assertThat(ServedMedia.sniff(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'), true)).isEmpty();
        assertThat(ServedMedia.sniff(new byte[]{1, 2}, true)).isEmpty();
        assertThat(ServedMedia.sniff(null, true)).isEmpty();
    }
}
