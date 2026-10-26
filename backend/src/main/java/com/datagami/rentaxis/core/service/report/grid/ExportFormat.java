package com.datagami.rentaxis.core.service.report.grid;

import org.springframework.http.MediaType;

/** A report file format: the {@code .{format}} of {@code /api/v1/finance/reports/<key>.{format}}. */
public enum ExportFormat {

    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv; charset=UTF-8");

    private final String ext;
    private final MediaType mediaType;

    ExportFormat(String ext, String mediaType) {
        this.ext = ext;
        this.mediaType = MediaType.parseMediaType(mediaType);
    }

    /** The file extension, lower case, without the dot. */
    public String ext() {
        return ext;
    }

    /** The response's {@code Content-Type}. */
    public MediaType mediaType() {
        return mediaType;
    }

    /**
     * The format with this exact (lower-case) extension; anything else is an
     * {@link IllegalArgumentException} (a 400). The message does not echo what was asked for.
     */
    public static ExportFormat of(String ext) {
        for (ExportFormat f : values()) {
            if (f.ext.equals(ext)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown report format: use xlsx, pdf or csv");
    }
}
