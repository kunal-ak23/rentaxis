package com.datagami.rentaxis.testsupport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a rendered PDF draws its words, for layout tests: openhtmltopdf does not
 * mirror a table's columns for {@code direction: rtl}, so an Arabic table's column
 * order shows only in the x positions of what it prints.
 *
 * <p>A word is a blank-free run of glyphs as drawn. Arabic comes back as shaped
 * presentation forms in visual order, so tests find words by their Latin text
 * (dates, numbers, document numbers) and treat Arabic ones only as Arabic.</p>
 */
public final class PdfWords {

    /** A word drawn on the page: its glyphs, its left edge and its baseline. */
    public record Word(String text, float x, float y) {
        /** Any Arabic letter, presentation forms included. */
        public boolean arabic() {
            return text.codePoints().anyMatch(c -> (c >= 0x0600 && c <= 0x06FF) || (c >= 0xFB50 && c <= 0xFDFF)
                    || (c >= 0xFE70 && c <= 0xFEFF));
        }
    }

    private PdfWords() {
    }

    /** Every word on page {@code page} (1-based), top to bottom. */
    public static List<Word> on(byte[] pdf, int page) throws IOException {
        List<Word> words = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    StringBuilder word = new StringBuilder();
                    float left = Float.MAX_VALUE, y = 0;
                    for (TextPosition p : positions) {
                        String u = p.getUnicode();
                        if (u == null || u.isBlank()) {
                            if (!word.isEmpty()) words.add(new Word(word.toString(), left, y));
                            word.setLength(0);
                            left = Float.MAX_VALUE;
                            continue;
                        }
                        word.append(u);
                        left = Math.min(left, p.getXDirAdj());
                        y = p.getYDirAdj();
                    }
                    if (!word.isEmpty()) words.add(new Word(word.toString(), left, y));
                }
            };
            s.setSortByPosition(true);
            s.setStartPage(page);
            s.setEndPage(page);
            s.getText(doc);
        }
        return words;
    }

    /** The words that are exactly {@code text}, top to bottom. */
    public static List<Word> all(List<Word> words, String text) {
        return words.stream().filter(w -> w.text().equals(text)).toList();
    }

    /** The first word that is exactly {@code text}. */
    public static Word first(List<Word> words, String text) {
        return all(words, text).stream().findFirst()
                .orElseThrow(() -> new AssertionError("no word \"" + text + "\" in " + words));
    }

    /** The first word that contains {@code fragment}: an amount drawn against its Arabic Dr/Cr, say. */
    public static Word containing(List<Word> words, String fragment) {
        return words.stream().filter(w -> w.text().contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("no word containing \"" + fragment + "\" in " + words));
    }

    /** The left edge of the first word that is exactly {@code text}. */
    public static float x(List<Word> words, String text) {
        return first(words, text).x();
    }

    /** The words printed on the same line as {@code word}. */
    public static List<Word> lineOf(List<Word> words, Word word) {
        return words.stream().filter(w -> Math.abs(w.y() - word.y()) < 2f).toList();
    }

    /** The words on the line of the first word that is exactly {@code text}. */
    public static List<Word> lineOf(List<Word> words, String text) {
        return lineOf(words, first(words, text));
    }

    /** The words on the nearest line above {@code word}'s: a table row's header, say. */
    public static List<Word> lineAbove(List<Word> words, Word word) {
        float y = (float) words.stream().mapToDouble(Word::y).filter(v -> v < word.y() - 2f).max()
                .orElseThrow(() -> new AssertionError("nothing above " + word));
        return words.stream().filter(w -> Math.abs(w.y() - y) < 2f).toList();
    }

    public static Word leftmost(List<Word> line) {
        return line.stream().min(Comparator.comparingDouble(Word::x)).orElseThrow();
    }

    public static Word rightmost(List<Word> line) {
        return line.stream().max(Comparator.comparingDouble(Word::x)).orElseThrow();
    }
}
