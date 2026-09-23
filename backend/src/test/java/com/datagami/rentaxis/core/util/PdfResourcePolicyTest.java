package com.datagami.rentaxis.core.util;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF renderer must load nothing but inline {@code data:} URIs. Each
 * "blocked" case is paired with a control that renders the same HTML without the
 * policy and shows the resource <em>is</em> fetched then — so a pass means the
 * policy stopped it, not that the renderer never tried.
 */
class PdfResourcePolicyTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private byte[] png;

    @BeforeEach
    void startServer() throws Exception {
        png = tinyPng();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void onlyDataUrisAreAllowed() {
        assertThat(PdfResourcePolicy.isAllowed("data:image/png;base64,AAAA")).isTrue();
        assertThat(PdfResourcePolicy.isAllowed(" DATA:image/png;base64,AAAA")).isTrue();
        assertThat(PdfResourcePolicy.isAllowed("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(PdfResourcePolicy.isAllowed("https://example.com/logo.png")).isFalse();
        assertThat(PdfResourcePolicy.isAllowed("file:///etc/passwd")).isFalse();
        assertThat(PdfResourcePolicy.isAllowed("jar:file:/app.jar!/x")).isFalse();
        assertThat(PdfResourcePolicy.isAllowed("//evil.example/x")).isFalse();
        assertThat(PdfResourcePolicy.isAllowed("logo.png")).isFalse();
        assertThat(PdfResourcePolicy.isAllowed(null)).isFalse();

        assertThat(PdfResourcePolicy.URI_RESOLVER.resolveURI(null, "file:///etc/passwd")).isNull();
        assertThat(PdfResourcePolicy.URI_RESOLVER.resolveURI("file:///", "etc/passwd")).isNull();
        assertThat(PdfResourcePolicy.URI_RESOLVER.resolveURI(null, base() + "/x.png")).isNull();
    }

    @Test
    void httpImagesStylesheetsAndCssUrlsAreNeverFetched() throws Exception {
        String html = page("<img src=\"" + base() + "/img.png\"/>"
                        + "<div style=\"background-image:url('" + base() + "/bg.png'); height:10px\"></div>",
                "<link rel=\"stylesheet\" href=\"" + base() + "/style.css\"/>");

        // Control: the unguarded renderer does make the request.
        render(html, false);
        assertThat(hits.get()).as("control: renderer fetches without the policy").isPositive();

        hits.set(0);
        byte[] pdf = render(html, true);
        assertThat(hits.get()).as("requests made with the policy applied").isZero();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void fileUrisAreNeverRead() throws Exception {
        Path file = Files.createTempFile("pdf-policy-", ".png");
        Files.write(file, png);
        String html = page("<img src=\"" + file.toUri() + "\" style=\"width:20px;height:20px\"/>", "");

        // Control: without the policy the local file is read into the PDF.
        assertThat(containsImage(render(html, false))).as("control: file image embedded").isTrue();
        assertThat(containsImage(render(html, true))).as("file image embedded with policy").isFalse();
    }

    @Test
    void dataUriImagesStillRender() throws Exception {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        String html = page("<img src=\"" + dataUri + "\" style=\"width:20px;height:20px\"/>", "");
        assertThat(containsImage(render(html, true))).isTrue();
    }

    private static String page(String body, String head) {
        return "<html><head>" + head + "</head><body><p>x</p>" + body + "</body></html>";
    }

    private static byte[] render(String html, boolean withPolicy) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            if (withPolicy) {
                PdfResourcePolicy.apply(builder);
            }
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        }
    }

    private static boolean containsImage(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            for (var page : doc.getPages()) {
                var resources = page.getResources();
                for (var name : resources.getXObjectNames()) {
                    if (resources.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
