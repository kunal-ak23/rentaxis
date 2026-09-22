package com.datagami.rentaxis.api.dto.cutover;

/**
 * One thing the opening-balance grid has to say before the accountant presses Post,
 * and how badly they need to hear it (ruling R26).
 *
 * <p>The list used to be bare strings, which made two very different statements look
 * identical on screen: "no account is mapped to OPENING_BALANCE_DIFFERENCE" is a
 * <b>fault that stops the post</b> — {@code postFresh} re-asserts it and refuses —
 * while "PACT's own difference figure is not carried over" or "SECURITY_DEPOSIT is
 * mapped to a group account" are things the accountant has to <b>know</b> and can
 * post past. Rendering a blocker and an advisory the same way trains people to
 * ignore both.</p>
 *
 * <p><b>{@code severity} is nested here rather than borrowed from
 * {@code ImportErrorDTO.Severity}.</b> That enum belongs to a row of a workbook the
 * validator rejected and is documented in those terms ("an ERROR stops the import");
 * a grid problem is not a row of anything, and locking the two contracts together so
 * that a third value added for one appears on the other buys nothing. The wire
 * values — {@code "ERROR"}, {@code "WARNING"} — are the same either way.</p>
 */
public record GridProblemDTO(String message, Severity severity) {

    /**
     * ERROR is a fault that will refuse the post; WARNING is a fact reported beside a
     * grid that can still be posted.
     */
    public enum Severity { ERROR, WARNING }

    public static GridProblemDTO error(String message) {
        return new GridProblemDTO(message, Severity.ERROR);
    }

    public static GridProblemDTO warning(String message) {
        return new GridProblemDTO(message, Severity.WARNING);
    }
}
