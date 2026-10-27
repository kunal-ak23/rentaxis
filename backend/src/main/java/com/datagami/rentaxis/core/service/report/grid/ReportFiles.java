package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.core.util.AttachmentNames;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** The HTTP response of a report file. */
public final class ReportFiles {

    private ReportFiles() {
    }

    /**
     * A 200 with the file as an attachment: the format's media type, the body's length, a plain
     * ASCII {@code filename="…"} for clients that read only that (non-ASCII characters become
     * {@code _}) and an RFC 5987 {@code filename*=UTF-8''…} so an Arabic file name survives.
     *
     * <p>Not Spring's {@code ContentDisposition.filename(name, UTF_8)}: with a charset it writes
     * {@code filename} as an RFC 2047 {@code =?UTF-8?Q?…?=} word even for an ASCII name, which a
     * client without {@code filename*} support (e.g. {@code curl -J}) saves verbatim.</p>
     */
    public static ResponseEntity<byte[]> response(byte[] body, ExportFormat f, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(f.mediaType());
        headers.setContentLength(body.length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, AttachmentNames.attachment(filename));
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
