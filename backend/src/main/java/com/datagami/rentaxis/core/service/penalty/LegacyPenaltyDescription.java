package com.datagami.rentaxis.core.service.penalty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F14-31 leftover: system proposals written before {@code description_code}
 * existed (changeset 121) carry only their English sentence. The two sentences
 * the rule engine wrote are fixed shapes, so the code and arguments are read
 * back out of them on the way to the screen, which then renders them in the
 * reader's language like any new row. Nothing is rewritten in the database, and
 * a sentence that does not match (a hand-written reason) stays as it is.
 */
public final class LegacyPenaltyDescription {

    public record Coded(String code, Map<String, String> args) { }

    /** "Cheque 140106 returned (SIGNATURE_MISMATCH), bounce #2 on this lease". */
    private static final Pattern RETURNED =
            Pattern.compile("^Cheque (.+) returned(?: \\(([A-Z_]+)\\))?, bounce #(\\d+) on this lease$");

    /** "Cheque 140101 cleared 3 days after its grace period (due 2026-06-05, cleared 2026-06-08)". */
    private static final Pattern CLEARED_LATE = Pattern.compile(
            "^Cheque (.+) cleared (\\d+) days? after its grace period \\(due (\\S+), cleared (\\S+)\\)$");

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private LegacyPenaltyDescription() { }

    public static Optional<Coded> parse(String description) {
        if (description == null) return Optional.empty();
        Matcher m = RETURNED.matcher(description.strip());
        if (m.matches()) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("cheque", m.group(1));
            args.put("failureReason", m.group(2) != null ? m.group(2) : "BOUNCE");
            args.put("bounces", m.group(3));
            return Optional.of(new Coded("chequeReturned", args));
        }
        m = CLEARED_LATE.matcher(description.strip());
        if (m.matches()) {
            String due = dmy(m.group(3));
            String cleared = dmy(m.group(4));
            if (due == null || cleared == null) return Optional.empty();
            Map<String, String> args = new LinkedHashMap<>();
            args.put("cheque", m.group(1));
            args.put("days", m.group(2));
            args.put("due", due);
            args.put("cleared", cleared);
            return Optional.of(new Coded("clearedLate", args));
        }
        return Optional.empty();
    }

    /** The old rows wrote ISO dates; the codes' arguments are dd/MM/yyyy. */
    private static String dmy(String date) {
        try {
            return LocalDate.parse(date).format(DMY);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(date, DMY).format(DMY);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
