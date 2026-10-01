package com.datagami.rentaxis.core.service.cheque;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>NOT PRODUCTION-READY.</b> Reachable only when
 * {@code rentaxis.cheques.pdf-upload.enabled} is true (default false). Known to
 * run out of memory on crafted PDFs this class does not yet bound: inline images
 * (BI), soft masks, images inside tiling patterns and annotation appearances,
 * and huge Flate content streams. Hardening is a follow-up (PR #388).</p>
 *
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
        // Temp-file stream cache: decoded streams go to disk, not the heap.
        try (PDDocument doc = Loader.loadPDF(pdf, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw new PdfRefusedException(ChequeUploadRefusedException.PDF_UNREADABLE, "The PDF has no pages");
            }
            if (pages > maxPages) {
                throw new PdfRefusedException(ChequeUploadRefusedException.TOO_MANY_PAGES,
                        "The PDF has " + pages + " pages; at most " + maxPages + " are read");
            }
            // Every image a page draws is checked by its declared size before any
            // page is rendered: MAX_SIDE_PX bounds the output, not the images decoded
            // to produce it (a 390 KB PDF can hold a 20000×6666 Flate image).
            for (int i = 0; i < pages; i++) {
                checkImages(doc.getPage(i).getResources(), 0);
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            // Images larger than their rendered size are decoded subsampled.
            renderer.setSubsamplingAllowed(true);
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
        } catch (PdfRefusedException e) {
            throw e;
        } catch (IOException e) {
            // Corrupt, not a PDF, or password-protected (InvalidPasswordException is an IOException).
            throw new PdfRefusedException(ChequeUploadRefusedException.PDF_UNREADABLE, "The PDF could not be read");
        }
    }

    /** Declared pixels above which an image inside a PDF is refused (see ChequeImageCropper). */
    static final long MAX_IMAGE_PIXELS = ChequeImageCropper.MAX_DECLARED_PIXELS;

    private static void checkImages(PDResources resources, int depth) throws IOException, PdfRefusedException {
        if (resources == null || depth > 4) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject x = resources.getXObject(name);
            if (x instanceof PDImageXObject img) {
                if ((long) img.getWidth() * img.getHeight() > MAX_IMAGE_PIXELS) {
                    throw new PdfRefusedException(ChequeUploadRefusedException.IMAGE_TOO_LARGE,
                            "An image in the PDF is " + img.getWidth() + "x" + img.getHeight() + " pixels");
                }
            } else if (x instanceof PDFormXObject form) {
                checkImages(form.getResources(), depth + 1);
            }
        }
    }
}
