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

    // ------------------------------------------------------------------
    // Decompression bombs: small files declaring huge images.
    // ------------------------------------------------------------------

    /** A PNG whose header declares w×h RGB, with a token IDAT: tiny on disk. */
    static byte[] pngDeclaring(int w, int h) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(bos, "IHDR", ihdr.array());
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(new byte[1024]);
        d.finish();
        byte[] buf = new byte[2048];
        int n = d.deflate(buf);
        chunk(bos, "IDAT", java.util.Arrays.copyOf(buf, n));
        chunk(bos, "IEND", new byte[0]);
        return bos.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream bos, String type, byte[] data) {
        java.nio.ByteBuffer len = java.nio.ByteBuffer.allocate(4).putInt(data.length);
        bos.writeBytes(len.array());
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        bos.writeBytes(t);
        bos.writeBytes(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        bos.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** A real small JPEG (with EXIF orientation 6) whose SOF0 header is rewritten to declare w×h. */
    static byte[] jpegDeclaring(int w, int h) {
        byte[] jpeg = jpegWithOrientation(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), 6);
        for (int i = 2; i + 8 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && ((jpeg[i + 1] & 0xFF) == 0xC0 || (jpeg[i + 1] & 0xFF) == 0xC2)) {
                jpeg[i + 5] = (byte) (h >> 8);
                jpeg[i + 6] = (byte) h;
                jpeg[i + 7] = (byte) (w >> 8);
                jpeg[i + 8] = (byte) w;
                return jpeg;
            }
        }
        throw new IllegalStateException("no SOF marker");
    }

    /** A one-page PDF drawing a Flate image XObject that declares w×h but holds almost no data. */
    static byte[] pdfWithImageDeclaring(int w, int h) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            java.util.zip.Deflater d = new java.util.zip.Deflater();
            d.setInput(new byte[4096]);
            d.finish();
            byte[] buf = new byte[8192];
            int n = d.deflate(buf);
            var stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc,
                    new java.io.ByteArrayInputStream(java.util.Arrays.copyOf(buf, n)),
                    org.apache.pdfbox.cos.COSName.FLATE_DECODE);
            PDImageXObject img = new PDImageXObject(stream, null);
            img.setWidth(w);
            img.setHeight(h);
            img.setBitsPerComponent(8);
            img.setColorSpace(org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0, 0, 500, 166);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A valid 16-bit-per-channel RGBA PNG of w×h (all zero), streamed so the test never holds its pixels. */
    static byte[] png16BitRgba(int w, int h) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 16).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(bos, "IHDR", ihdr.array());
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (var z = new java.util.zip.DeflaterOutputStream(idat, new java.util.zip.Deflater(9))) {
            byte[] row = new byte[1 + w * 8]; // filter byte 0 + 8 bytes a pixel
            for (int y = 0; y < h; y++) {
                z.write(row);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        chunk(bos, "IDAT", idat.toByteArray());
        chunk(bos, "IEND", new byte[0]);
        return bos.toByteArray();
    }
}
