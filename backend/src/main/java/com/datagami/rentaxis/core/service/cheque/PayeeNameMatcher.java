package com.datagami.rentaxis.core.service.cheque;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/**
 * Does a payee name read off a cheque match one of the organisation's valid payee
 * names? (Owner ruling 2026-09-29: valid = matches ANY listed name.)
 *
 * <p>Both sides are normalised and then compared for equality. Normalising means:</p>
 * <ol>
 *   <li>Unicode NFKC, so full-width letters, ligatures and Arabic presentation
 *       forms become the ordinary letters they display as;</li>
 *   <li>lower case (locale-independent);</li>
 *   <li>everything that is not a letter or a digit is dropped: spaces, tabs, line
 *       breaks and all punctuation ("L.L.C." = "LLC", "Palm-Ridge" = "Palm Ridge"
 *       = "PalmRidge"), plus Arabic diacritics (tashkeel, which are combining
 *       marks) and the tatweel stretching stroke (ـ), which are handwriting, not
 *       spelling.</li>
 * </ol>
 *
 * <p>Nothing else: no transliteration between Arabic and Latin script, no fuzzy
 * or partial matching, no dropping of words like "LLC". An Arabic payee matches
 * an Arabic valid name and a Latin payee a Latin one; the organisation lists each
 * spelling it accepts. A listed name that normalises to nothing never matches.</p>
 */
public final class PayeeNameMatcher {

    private PayeeNameMatcher() {}

    /** The comparison key for a name; see the class note. Null for null. */
    public static String normalise(String name) {
        if (name == null) return null;
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(n.length());
        n.codePoints()
                .filter(cp -> Character.isLetterOrDigit(cp) && cp != 0x0640) // 0x0640: Arabic tatweel
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    /** True when {@code payee} normalises to the same non-empty key as any of {@code validNames}. */
    public static boolean matchesAny(String payee, Collection<String> validNames) {
        String key = normalise(payee);
        if (key == null || key.isEmpty() || validNames == null) return false;
        for (String valid : validNames) {
            if (key.equals(normalise(valid))) return true;
        }
        return false;
    }
}
