package com.datagami.rentaxis.core.service.cheque;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders each page of a scanned-cheques PDF to an image, with Apache PDFBox —
 * already on the classpath through openhtmltopdf-pdfbox, now declared directly.
 *
 * <p>Bounded: the page count is checked before anything is rendered, and each
 * page is rendered at {@link #DPI} but never with a side longer than
 * {@link #MAX_SIDE_PX}, so a PDF declaring a billboard-sized page cannot take
 * the heap with it.</p>
 */
public final class ChequePdfRenderer {

    static final float DPI = 200f;
    static final int MAX_SIDE_PX = 2400;

    private ChequePdfRenderer() {
    }

    /** Refused before rendering. {@code code} is the API's refusal code. */
    public static final class PdfRefusedException extends Exception {
        private final String code;

        PdfRefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Every page as JPEG bytes, in page order. Encoded one page at a time, so only
     * one page's pixels are ever on the heap.
     */
    public static List<byte[]> renderToJpeg(byte[] pdf, int maxPages) throws PdfRefusedException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw new PdfRefusedException(ChequeUploadRefusedException.PDF_UNREADABLE, "The PDF has no pages");
            }
            if (pages > maxPages) {
                throw new PdfRefusedException(ChequeUploadRefusedException.TOO_MANY_PAGES,
                        "The PDF has " + pages + " pages; at most " + maxPages + " are read");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> out = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                PDRectangle box = doc.getPage(i).getCropBox();
                float longestPt = Math.max(box.getWidth(), box.getHeight());
                float scale = DPI / 72f;
                if (longestPt * scale > MAX_SIDE_PX) {
                    scale = MAX_SIDE_PX / Math.max(1f, longestPt);
                }
                BufferedImage page = renderer.renderImage(i, scale, ImageType.RGB);
                out.add(ChequeImageCropper.toJpeg(page, 0.9f));
            }
            return out;
        } catch (IOException e) {
            // Corrupt, not a PDF, or password-protected (InvalidPasswordException is an IOException).
            throw new PdfRefusedException(ChequeUploadRefusedException.PDF_UNREADABLE, "The PDF could not be read");
        }
    }
}
