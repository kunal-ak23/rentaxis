package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.BoundingBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Generated pictures of "cheques": solid coloured rectangles on white. */
final class ChequeTestImages {

    static final int W = 1200;
    static final int H = 900;

    /** Three cheques: red top-left, green bottom-left, blue right. Pixel rectangles {x, y, w, h}. */
    static final int[][] RECTS = {
            {100, 100, 400, 180},
            {100, 560, 400, 180},
            {700, 330, 400, 180},
    };
    static final Color[] COLOURS = {Color.RED, Color.GREEN, Color.BLUE};

    private ChequeTestImages() {
    }

    static BufferedImage threeCheques() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < RECTS.length; i++) {
            g.setColor(COLOURS[i]);
            g.fillRect(RECTS[i][0], RECTS[i][1], RECTS[i][2], RECTS[i][3]);
        }
        g.dispose();
        return img;
    }

    /** The model's view of {@link #threeCheques}: each rectangle, normalised. */
    static List<BoundingBox> threeBoxes() {
        return java.util.Arrays.stream(RECTS)
                .map(r -> new BoundingBox((double) r[0] / W, (double) r[1] / H, (double) r[2] / W, (double) r[3] / H))
                .toList();
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pixels within a tolerance of {@code c} (JPEG is lossy). */
    static int count(BufferedImage img, Color c) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (near(new Color(img.getRGB(x, y)), c)) {
                    n++;
                }
            }
        }
        return n;
    }

    static boolean near(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) < 60
                && Math.abs(a.getGreen() - b.getGreen()) < 60
                && Math.abs(a.getBlue() - b.getBlue()) < 60;
    }

    /**
     * A JPEG of {@code img} carrying an EXIF APP1 with the given Orientation, placed
     * straight after SOI — exactly where a phone camera puts it.
     */
    static byte[] jpegWithOrientation(BufferedImage img, int orientation) {
        byte[] jpeg = ChequeImageCropper.toJpeg(img, 0.95f);
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,           // APP1, length 34
                'E', 'x', 'i', 'f', 0, 0,                       // Exif header
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,   // big-endian TIFF, IFD0 at 8
                0x00, 0x01,                                     // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // Orientation, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,           // value
                0x00, 0x00, 0x00, 0x00                          // no next IFD
        };
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    /** A PDF with one page per image, each image filling its page. */
    static byte[] pdfOf(BufferedImage... pages) {
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage img : pages) {
                PDPage page = new PDPage(new PDRectangle(img.getWidth() * 0.5f, img.getHeight() * 0.5f));
                doc.addPage(page);
                PDImageXObject x = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(x, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One cheque (a red rectangle) on a page. */
    static BufferedImage oneCheque(Color colour) {
        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 400);
        g.setColor(colour);
        g.fillRect(100, 100, 600, 200);
        g.dispose();
        return img;
    }
}
