package com.datagami.rentaxis.core.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Makes the executed copy of a contract: the SIGNED PDF, byte for byte, with the
 * organisation's stamp drawn into the stamp area of every page and a small
 * "Executed copy · date" mark (Kunal, 2026-09-28: the originally signed PDF stays
 * untouched; the executed copy is that document plus the stamp).
 *
 * <p>Nothing is re-rendered. The signed file is opened, one extra content stream
 * is appended to each page and the result is written as an <b>incremental
 * update</b>: the signed bytes are the untouched prefix of the copy, and every
 * existing glyph, table and signature line stays exactly where it was.</p>
 *
 * <p><b>Where the stamp goes.</b> The contract footer (contract-template.html,
 * {@code .page-footer}) repeats on every page: three equal cells — "Tenant
 * Signature", the stamp area left empty for the wet stamp, "Landlord Signature" —
 * above the "Print Date &amp; Time" line. The labels are fixed English text in
 * the template, so their glyph positions are the anchor whatever language the
 * names are in: the stamp is placed at the right of the middle cell (beside the
 * landlord signature), its foot on the label baseline, at most 110 x 34 pt; the
 * mark is right-aligned on the print line. A page without the anchors (not one of
 * our contracts, or a changed template) means no copy — never a guessed position.</p>
 */
public final class ExecutedCopyStamper {

    static final String TENANT_LABEL = "Tenant Signature";
    static final String LANDLORD_LABEL = "Landlord Signature";
    static final String PRINT_LABEL = "Print Date";
    static final float MAX_W = 110f;
    static final float MAX_H = 34f;
    static final float MARK_SIZE = 6.5f;

    private ExecutedCopyStamper() {
    }

    /** The anchors found on one page, in PDF user space (origin bottom-left). */
    record Anchors(float tenantCenter, float landlordCenter, float labelBaseline, float printBaseline) {
        float columnWidth() {
            return (landlordCenter - tenantCenter) / 2f;
        }

        float stampAreaRight() {
            return landlordCenter - columnWidth() / 2f;
        }

        float contentRight() {
            return landlordCenter + columnWidth() / 2f;
        }
    }

    /**
     * @return the executed copy, or empty when the signed PDF cannot be read or a
     *         page has no stamp area to put the stamp in
     */
    public static Optional<byte[]> stamp(byte[] signedPdf, byte[] stampImage, String mark) {
        if (signedPdf == null || signedPdf.length == 0 || stampImage == null || stampImage.length == 0) {
            return Optional.empty();
        }
        try (PDDocument doc = Loader.loadPDF(signedPdf)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                return Optional.empty();
            }
            List<Anchors> anchors = new ArrayList<>();
            for (int i = 1; i <= pages; i++) {
                Optional<Anchors> a = anchorsOn(doc, i);
                if (a.isEmpty()) {
                    return Optional.empty();
                }
                anchors.add(a.get());
            }
            PDImageXObject image = PDImageXObject.createFromByteArray(doc, stampImage, "stamp");
            float scale = Math.min(MAX_W / image.getWidth(), MAX_H / image.getHeight());
            float w = image.getWidth() * scale;
            float h = image.getHeight() * scale;
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float markWidth = font.getStringWidth(mark) / 1000f * MARK_SIZE;

            for (int i = 0; i < pages; i++) {
                PDPage page = doc.getPage(i);
                Anchors a = anchors.get(i);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.drawImage(image, a.stampAreaRight() - 4f - w, a.labelBaseline() - 1f, w, h);
                    cs.beginText();
                    cs.setFont(font, MARK_SIZE);
                    cs.setNonStrokingColor(0.35f, 0.35f, 0.35f);
                    cs.newLineAtOffset(a.contentRight() - markWidth, a.printBaseline());
                    cs.showText(mark);
                    cs.endText();
                }
                markForUpdate(page);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.saveIncremental(out);
            return Optional.of(out.toByteArray());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The objects an incremental update must rewrite: the page, its resources and their image table. */
    private static void markForUpdate(PDPage page) {
        page.getCOSObject().setNeedToBeUpdated(true);
        PDResources resources = page.getResources();
        if (resources != null) {
            COSDictionary res = resources.getCOSObject();
            res.setNeedToBeUpdated(true);
            if (res.getDictionaryObject(COSName.XOBJECT) instanceof COSDictionary x) {
                x.setNeedToBeUpdated(true);
            }
            if (res.getDictionaryObject(COSName.FONT) instanceof COSDictionary f) {
                f.setNeedToBeUpdated(true);
            }
        }
    }

    static Optional<Anchors> anchorsOn(PDDocument doc, int pageNo) throws IOException {
        float pageHeight = doc.getPage(pageNo - 1).getMediaBox().getHeight();
        List<List<TextPosition>> lines = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                lines.add(new ArrayList<>(positions));
            }
        };
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.setSortByPosition(true);
        stripper.getText(doc);

        Float tenantCenter = null, landlordCenter = null, labelBaseline = null, printBaseline = null;
        for (List<TextPosition> line : lines) {
            StringBuilder sb = new StringBuilder();
            for (TextPosition p : line) sb.append(p.getUnicode());
            String text = sb.toString();
            for (String label : new String[]{TENANT_LABEL, LANDLORD_LABEL, PRINT_LABEL}) {
                int at = text.indexOf(label);
                if (at < 0) continue;
                List<TextPosition> glyphs = glyphsFor(line, at, label.length());
                if (glyphs.isEmpty()) continue;
                TextPosition first = glyphs.get(0);
                TextPosition last = glyphs.get(glyphs.size() - 1);
                float center = (first.getXDirAdj() + last.getXDirAdj() + last.getWidthDirAdj()) / 2f;
                float baseline = pageHeight - first.getYDirAdj();
                switch (label) {
                    case TENANT_LABEL -> tenantCenter = center;
                    case LANDLORD_LABEL -> { landlordCenter = center; labelBaseline = baseline; }
                    default -> printBaseline = baseline;
                }
            }
        }
        if (tenantCenter == null || landlordCenter == null || printBaseline == null
                || landlordCenter <= tenantCenter) {
            return Optional.empty();
        }
        return Optional.of(new Anchors(tenantCenter, landlordCenter, labelBaseline, printBaseline));
    }

    /** The glyphs making up characters [at, at+len) of the line (one glyph may carry several chars). */
    private static List<TextPosition> glyphsFor(List<TextPosition> line, int at, int len) {
        List<TextPosition> out = new ArrayList<>();
        int index = 0;
        for (TextPosition p : line) {
            int n = p.getUnicode() == null ? 0 : p.getUnicode().length();
            if (index + n > at && index < at + len) out.add(p);
            index += n;
        }
        return out;
    }
}
