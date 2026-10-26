package com.datagami.rentaxis.core.service.report.grid;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** The HTTP response of a report file. */
public final class ReportFiles {

    private ReportFiles() {
    }

    /**
     * A 200 with the file as an attachment: the format's media type, the body's length and an
     * RFC 5987 {@code filename*=UTF-8''…} so an Arabic file name survives.
     */
    public static ResponseEntity<byte[]> response(byte[] body, ExportFormat f, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(f.mediaType());
        headers.setContentLength(body.length);
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
