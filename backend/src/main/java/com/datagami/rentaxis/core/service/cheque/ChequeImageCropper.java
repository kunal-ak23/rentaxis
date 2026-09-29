package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.BoundingBox;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * Cuts each cheque out of a photo that holds several, with plain {@code javax.imageio}
 * and {@code java.awt} — no native code, no extra dependency.
 *
 * <p><b>Orientation.</b> Phone cameras store pixels sideways and record the turn in
 * the JPEG's EXIF {@code Orientation} tag; ImageIO ignores the tag. {@link #decode}
 * reads the tag itself (a few dozen bytes of TIFF, see {@link #exifOrientation}) and
 * turns the pixels upright, and the model is shown that upright image, so the boxes
 * it returns and the pixels they are applied to are the same picture.</p>
 *
 * <p><b>Never guess.</b> {@link #plan} refuses a box that is missing, not finite,
 * tiny, mostly outside the image, or heavily overlapping another cheque's box. Such
 * a cheque is kept, flagged, and given the whole image instead of a crop that could
 * be half of two cheques.</p>
 */
public final class ChequeImageCropper {

    /** Padding added on every side of a box, as a fraction of the image's own width / height. */
    static final double PADDING = 0.03;
    /** A box narrower or shorter than this fraction of the image is not a cheque. */
    static final double MIN_SIDE = 0.03;
    /** A box of less than this fraction of the image's area is not a cheque. */
    static final double MIN_AREA = 0.005;
    /** At least this much of a box must lie inside the image. */
    static final double MIN_INSIDE = 0.9;
    /**
     * Two boxes whose overlap covers more than this fraction of the smaller one
     * cannot be separated cleanly.
     */
    static final double MAX_OVERLAP = 0.3;

    /** Longest side of a thumbnail returned to the browser. */
    static final int THUMB_MAX = 480;
    /** Longest side of the full-page preview returned to the browser. */
    static final int PREVIEW_MAX = 1200;

    /** Why a box was not trusted; null in a {@link Crop} that is fine. */
    public enum Refusal { NO_BOX, INVALID, TINY, OUTSIDE, OVERLAP }

    /** Pixel rectangle to cut, or the reason not to cut. */
    public record Crop(int x, int y, int width, int height, Refusal refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    private ChequeImageCropper() {
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * The image, upright, or null when ImageIO cannot read it (HEIC/HEIF, a corrupt
     * file). Null is not an error: the caller then keeps the original bytes and
     * flags any cheque it cannot separate.
     */
    public static BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (img == null) {
            return null;
        }
        return orient(img, exifOrientation(bytes));
    }

    /**
     * The EXIF Orientation (1–8) of a JPEG, or 1 when there is none or it cannot be
     * read. Walks the JPEG markers to APP1 "Exif", then IFD0 of its TIFF block.
     */
    static int exifOrientation(byte[] b) {
        try {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int p = 2;
            while (p + 4 <= b.length) {
                if ((b[p] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = b[p + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return 1; // start of scan / end of image: no EXIF before the pixels
                }
                int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (len < 2) {
                    return 1;
                }
                int seg = p + 4;
                if (marker == 0xE1 && seg + 6 <= b.length
                        && b[seg] == 'E' && b[seg + 1] == 'x' && b[seg + 2] == 'i' && b[seg + 3] == 'f'
                        && b[seg + 4] == 0 && b[seg + 5] == 0) {
                    return readTiffOrientation(b, seg + 6, Math.min(b.length, p + 2 + len));
                }
                p += 2 + len;
            }
        } catch (RuntimeException e) {
            return 1;
        }
        return 1;
    }

    private static int readTiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean le;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            le = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            le = false;
        } else {
            return 1;
        }
        int ifd = tiff + (int) u32(b, tiff + 4, le);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = u16(b, ifd, le);
        for (int i = 0; i < entries; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > end) {
                return 1;
            }
            if (u16(b, e, le) == 0x0112) {
                int v = u16(b, e + 8, le);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean le) {
        return le ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)
                  : ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static long u32(byte[] b, int i, boolean le) {
        return le ? (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24)
                  : ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL);
    }

    /** Applies an EXIF orientation so the returned image is upright. */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return src; }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Planning and cropping
    // ------------------------------------------------------------------

    /** One {@link Crop} per box, in the same order. */
    public static List<Crop> plan(List<BoundingBox> boxes, int imageWidth, int imageHeight) {
        int n = boxes.size();
        Refusal[] refusals = new Refusal[n];
        double[][] clamped = new double[n][];
        for (int i = 0; i < n; i++) {
            BoundingBox b = boxes.get(i);
            if (b == null) {
                refusals[i] = Refusal.NO_BOX;
                continue;
            }
            if (!Double.isFinite(b.x()) || !Double.isFinite(b.y())
                    || !Double.isFinite(b.width()) || !Double.isFinite(b.height())
                    || b.width() <= 0 || b.height() <= 0) {
                refusals[i] = Refusal.INVALID;
                continue;
            }
            double x0 = Math.max(0, b.x());
            double y0 = Math.max(0, b.y());
            double x1 = Math.min(1, b.x() + b.width());
            double y1 = Math.min(1, b.y() + b.height());
            double inside = x1 > x0 && y1 > y0 ? (x1 - x0) * (y1 - y0) : 0;
            if (inside < MIN_INSIDE * b.width() * b.height()) {
                refusals[i] = Refusal.OUTSIDE;
                continue;
            }
            if (x1 - x0 < MIN_SIDE || y1 - y0 < MIN_SIDE || inside < MIN_AREA) {
                refusals[i] = Refusal.TINY;
                continue;
            }
            clamped[i] = new double[]{x0, y0, x1, y1};
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (clamped[i] == null || clamped[j] == null) {
                    continue;
                }
                double[] a = clamped[i];
                double[] c = clamped[j];
                double ix = Math.min(a[2], c[2]) - Math.max(a[0], c[0]);
                double iy = Math.min(a[3], c[3]) - Math.max(a[1], c[1]);
                if (ix <= 0 || iy <= 0) {
                    continue;
                }
                double inter = ix * iy;
                double smaller = Math.min((a[2] - a[0]) * (a[3] - a[1]), (c[2] - c[0]) * (c[3] - c[1]));
                if (inter > MAX_OVERLAP * smaller) {
                    refusals[i] = Refusal.OVERLAP;
                    refusals[j] = Refusal.OVERLAP;
                }
            }
        }
        List<Crop> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (refusals[i] != null) {
                out.add(new Crop(0, 0, imageWidth, imageHeight, refusals[i]));
                continue;
            }
            double[] r = clamped[i];
            // Box to whole pixels first (rounded, so 100/1200 of 1200 is pixel 100,
            // not 99.9999), then the padding in whole pixels, then the clamp.
            long padX = Math.round(PADDING * imageWidth);
            long padY = Math.round(PADDING * imageHeight);
            int px0 = clampPx(Math.round(r[0] * imageWidth) - padX, imageWidth);
            int py0 = clampPx(Math.round(r[1] * imageHeight) - padY, imageHeight);
            int px1 = clampPx(Math.round(r[2] * imageWidth) + padX, imageWidth);
            int py1 = clampPx(Math.round(r[3] * imageHeight) + padY, imageHeight);
            if (px1 - px0 < 1 || py1 - py0 < 1) {
                out.add(new Crop(0, 0, imageWidth, imageHeight, Refusal.TINY));
                continue;
            }
            out.add(new Crop(px0, py0, px1 - px0, py1 - py0, null));
        }
        return out;
    }

    private static int clampPx(long v, int max) {
        return (int) Math.max(0, Math.min(max, v));
    }

    /** The pixels a {@link Crop} names, as an independent RGB image. */
    public static BufferedImage cut(BufferedImage image, Crop crop) {
        BufferedImage out = new BufferedImage(crop.width(), crop.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, crop.width(), crop.height());
            g.drawImage(image, -crop.x(), -crop.y(), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /** JPEG bytes (RGB; any alpha is flattened onto white). */
    public static byte[] toJpeg(BufferedImage image, float quality) {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** The image scaled so its longest side is at most {@code maxSide} (never enlarged). */
    public static BufferedImage scaleDown(BufferedImage image, int maxSide) {
        int w = image.getWidth();
        int h = image.getHeight();
        double s = Math.min(1.0, (double) maxSide / Math.max(w, h));
        if (s >= 1.0) {
            return image;
        }
        int nw = Math.max(1, (int) Math.round(w * s));
        int nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** A small JPEG data URL for the review screen. */
    public static String dataUrl(BufferedImage image, int maxSide) {
        return "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(toJpeg(scaleDown(image, maxSide), 0.75f));
    }
}
