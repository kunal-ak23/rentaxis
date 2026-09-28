package com.datagami.rentaxis.core.util;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared text sanitiser every PDF generator runs its HTML through before
 * rendering (break-it R3 portal3 F11).
 *
 * <p>The PDFs embed two fonts, Noto Sans and Noto Sans Arabic. A character
 * neither covers — an emoji in a renter's name — used to print as '#', and bidi
 * controls (U+200F, U+202E…) as stray glyphs over the neighbouring text. Both
 * are removed here: invisible controls always ({@link UnicodeText}), other code
 * points when no embedded font has a glyph for them. Markup is ASCII, so the
 * HTML's structure is untouched; letters the fonts carry, Arabic included, stay
 * exactly as they were.</p>
 */
public final class PdfText {

    private static final String[] FONTS = {"fonts/NotoSans.ttf", "fonts/NotoSansArabic.ttf"};

    private static volatile List<CmapLookup> cmaps;

    private PdfText() {
    }

    /** {@code html} with what the PDF fonts cannot show removed; null stays null. */
    public static String clean(String html) {
        if (html == null) {
            return null;
        }
        List<CmapLookup> lookups = cmaps();
        StringBuilder out = new StringBuilder(html.length());
        html.codePoints().forEach(cp -> {
            if (keep(cp, lookups)) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    static boolean keep(int cp, List<CmapLookup> lookups) {
        if (UnicodeText.isInvisibleControl(cp)) {
            return false;
        }
        if (cp < 0x80 || Character.isWhitespace(cp) || Character.isISOControl(cp)) {
            return true; // markup, ASCII text, line breaks: always kept
        }
        if (lookups.isEmpty()) {
            // Fonts unreadable: fall back to dropping what they never carry.
            return !Character.isSupplementaryCodePoint(cp) && Character.getType(cp) != Character.OTHER_SYMBOL;
        }
        for (CmapLookup l : lookups) {
            if (l.getGlyphId(cp) > 0) {
                return true;
            }
        }
        return false;
    }

    private static List<CmapLookup> cmaps() {
        List<CmapLookup> loaded = cmaps;
        if (loaded == null) {
            synchronized (PdfText.class) {
                loaded = cmaps;
                if (loaded == null) {
                    loaded = load();
                    cmaps = loaded;
                }
            }
        }
        return loaded;
    }

    private static List<CmapLookup> load() {
        List<CmapLookup> out = new ArrayList<>();
        for (String path : FONTS) {
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                // Parsed once and kept open for the lookups (the font is small, read into memory).
                TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(in));
                CmapLookup lookup = font.getUnicodeCmapLookup(false);
                if (lookup != null) {
                    out.add(lookup);
                }
            } catch (Exception e) {
                // Leave this font out; keep() degrades to the symbol heuristic if none load.
            }
        }
        return List.copyOf(out);
    }
}
