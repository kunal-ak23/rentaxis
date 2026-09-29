package com.datagami.rentaxis.core.service.cheque;

/**
 * A cheque upload refused before anything was stored. {@code code} is stable and
 * machine-readable — the web translates it (EN/AR); the message is for logs and
 * API clients that do not.
 */
public class ChequeUploadRefusedException extends RuntimeException {

    public static final String FILE_REQUIRED = "cheque_upload_file_required";
    public static final String FILE_TOO_LARGE = "cheque_upload_file_too_large";
    public static final String UNSUPPORTED_TYPE = "cheque_upload_unsupported_type";
    public static final String TOO_MANY_PAGES = "cheque_upload_too_many_pages";
    public static final String TOO_MANY_CHEQUES = "cheque_upload_too_many_cheques";
    public static final String PDF_UNREADABLE = "cheque_upload_pdf_unreadable";
    /** The image (or an image inside a PDF) declares more pixels than the server will decode. */
    public static final String IMAGE_TOO_LARGE = "cheque_upload_image_too_large";
    /** PDF input is switched off ({@code rentaxis.cheques.pdf-upload.enabled}). */
    public static final String PDF_NOT_SUPPORTED = "cheque_upload_pdf_not_supported";
    /** Too many uploads are being decoded at once; retry shortly. */
    public static final String BUSY = "cheque_upload_busy";

    private final String code;

    public ChequeUploadRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
