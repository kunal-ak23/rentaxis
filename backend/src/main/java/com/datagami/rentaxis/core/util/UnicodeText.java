package com.datagami.rentaxis.core.util;

/**
 * Invisible-character hygiene for user-typed text (break-it R3 portal3 F6/F11).
 *
 * <p>Zero-width characters and bidi controls (U+200B–U+200F, U+202A–U+202E,
 * U+2066–U+2069, U+2060–U+2064, U+FEFF, U+061C, U+180E) and variation/tag
 * selectors are never meaningful in a person's name: they make a name that looks
 * empty, or reorder what staff read (U+202E flips the text after it). They are
 * removed; ordinary letters, Arabic included, are untouched.</p>
 */
public final class UnicodeText {

    private UnicodeText() {
    }

    /** A zero-width, bidi-control or selector code point: invisible, never wanted. */
    public static boolean isInvisibleControl(int cp) {
        return (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x2064)
                || (cp >= 0x2066 && cp <= 0x2069)
                || cp == 0xFEFF || cp == 0x061C || cp == 0x180E || cp == 0x00AD
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0000 && cp <= 0xE007F)
                || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    /** Any Unicode space (NBSP, narrow NBSP, figure space, ideographic space…) or ASCII whitespace. */
    public static boolean isAnySpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    /** {@code s} without its invisible controls; null stays null. */
    public static String stripInvisible(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().filter(cp -> !isInvisibleControl(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    /**
     * A person's name as stored: invisible controls removed, every run of Unicode
     * spaces collapsed to one ASCII space, and trimmed. Empty when nothing visible
     * is left — the caller refuses that. Null stays null.
     */
    public static String normalizeName(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int cp : stripInvisible(s).codePoints().toArray()) {
            if (isAnySpace(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
