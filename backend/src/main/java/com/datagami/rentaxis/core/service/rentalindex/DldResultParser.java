package com.datagami.rentaxis.core.service.rentalindex;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads a DLD "Rental Index Result" PDF. Each value is found by its own shape, not by its row: DLD
 * re-arranged the result between layouts, so row-position parsing breaks. The figures are then checked
 * against each other, so a misread or altered file is refused instead of slipping through.
 */
public final class DldResultParser {

    private static final String NOT_DLD = "rentalIndex.notDldResult";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private static final Pattern TITLE = Pattern.compile("^Rental Index Resul?t?\\s*$", Pattern.MULTILINE);
    private static final Pattern ISSUER = Pattern.compile("Dubai Land Department");
    private static final Pattern PCT = Pattern.compile("(?<![\\d.])(\\d{1,2})%");
    private static final Pattern RANGE = Pattern.compile("From (\\d+(?:\\.\\d+)?) to (\\d+(?:\\.\\d+)?) AED per year");
    private static final Pattern PREMISE = Pattern.compile("(?<!\\d)(\\d{9})(?!\\d)");
    private static final Pattern END = Pattern.compile("(?<![\\d/])(\\d{2}/\\d{2}/\\d{4})(?![\\d/])");
    private static final Pattern ANNUAL = Pattern.compile("([\\d,]+(?:\\.\\d+)?) AED Annually");
    private static final Pattern RENT = Pattern.compile("([\\d,]+(?:\\.\\d{2})?) AED(?! Annually| per year)(?!\\w)");
    private static final Pattern CATEGORY = Pattern.compile("(?i)(studio|\\d+ ?bed ?rooms?(?: ?\\+ ?hall)?)");
    private static final Pattern BUILDING =
            Pattern.compile("^(?:\\d{9} (.+)|.*AED Annually (.+) \\d{9})\\s*$", Pattern.MULTILINE);
    private static final Pattern AREA_TYPE =
            Pattern.compile("^Residential (.+?) (Apartment|Villa|Hotel Apartment)\\s*$", Pattern.MULTILINE);
    private static final Pattern ISSUED =
            Pattern.compile("^(\\d{1,2} \\w+ \\d{4}, \\d{1,2}:\\d{2} [AP]M)\\s*$", Pattern.MULTILINE);

    private DldResultParser() {}

    public static DldResult parse(byte[] pdf) {
        return parseText(extractText(pdf));
    }

    private static String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException | RuntimeException e) {
            throw notDld("file");
        }
    }

    static DldResult parseText(String t) {
        need(TITLE, t, "title");
        need(ISSUER, t, "issuer");
        Matcher pct = need(PCT, t, "percentage");
        Matcher range = need(RANGE, t, "index range");
        Matcher premise = need(PREMISE, t, "DEWA premise number");
        Matcher end = need(END, t, "contract end date");
        Matcher rent = need(RENT, t, "current rent");

        // Two "... AED Annually" amounts: the increase and the rent after it (the larger).
        List<BigDecimal> annual = new ArrayList<>();
        Matcher ann = ANNUAL.matcher(t);
        while (ann.find()) annual.add(num(ann.group(1)));
        if (annual.size() != 2) throw notDld("annual amounts");
        annual.sort(null);

        LocalDate contractEnd;
        try {
            contractEnd = LocalDate.parse(end.group(1), DMY);
        } catch (DateTimeParseException e) {
            throw notDld("contract end date");
        }

        Matcher cat = CATEGORY.matcher(t);
        Matcher bld = BUILDING.matcher(t);
        Matcher at = AREA_TYPE.matcher(t);
        Matcher iss = ISSUED.matcher(t);
        boolean hasAt = at.find();

        BigDecimal currentRent = num(rent.group(1));
        int percent = Integer.parseInt(pct.group(1));
        BigDecimal increase = annual.get(0);
        BigDecimal after = annual.get(1);

        BigDecimal expected = currentRent.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        if (currentRent.add(increase).compareTo(after) != 0
                || expected.compareTo(increase.setScale(0, RoundingMode.HALF_UP)) != 0) {
            throw new BusinessRuleViolationException(
                    "The figures on this result do not add up; it was misread or altered.",
                    "rentalIndex.figuresDontAddUp", Map.of());
        }

        return new DldResult(percent, increase, after, premise.group(1), contractEnd, currentRent,
                num(range.group(1)), num(range.group(2)),
                cat.find() ? cat.group(1) : "",
                bld.find() ? (bld.group(1) != null ? bld.group(1) : bld.group(2)).trim() : "",
                hasAt ? at.group(1) : null, hasAt ? at.group(2) : null,
                iss.find() ? iss.group(1) : "");
    }

    private static Matcher need(Pattern p, String text, String what) {
        Matcher m = p.matcher(text);
        if (!m.find()) throw notDld(what);
        return m;
    }

    private static BigDecimal num(String s) {
        return new BigDecimal(s.replace(",", ""));
    }

    private static BusinessRuleViolationException notDld(String what) {
        return new BusinessRuleViolationException(
                "This is not a DLD rental index result (" + what + " not found).", NOT_DLD, Map.of());
    }
}
