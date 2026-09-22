package com.datagami.rentaxis.api.dto.recognition;

import com.datagami.rentaxis.core.service.recognition.RecognitionService.RecognitionRunResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The answer to <b>Run recognition to date</b> (spec §8.4), preview or not.
 *
 * <p>Three outcomes, three places, because the month-end screen has to say three
 * different sentences about them:</p>
 *
 * <ul>
 *   <li>{@code entries} — posted (or, on a preview, what would post). The
 *       accountant's "done".</li>
 *   <li>{@code skippedLockedEntries} — untouched because their period is closed.
 *       Nothing is wrong; reopen the month, or don't. {@code booksLockedThrough}
 *       is the date to name in the message.</li>
 *   <li>{@code errors} — the ledger refused these, one message each. Somebody has
 *       to look.</li>
 * </ul>
 *
 * <p>{@code posted} is <b>0 on a preview</b> and {@code wouldPost} is the count to
 * show beside the button; on a real run the two are equal. A preview that reported
 * itself as "3 posted" is how a close gets signed off twice.</p>
 */
public record RecognitionRunResultDTO(
        boolean preview,
        int posted,
        int wouldPost,
        BigDecimal amount,
        List<RecognitionEntryDTO> entries,
        int skippedLocked,
        List<RecognitionEntryDTO> skippedLockedEntries,
        LocalDate booksLockedThrough,
        int failed,
        List<String> errors) {

    /**
     * {@code failed} is derived rather than carried by the service: the service's
     * record is the truth and a second stored count could disagree with its own
     * list. The API carries it because a JSON client should not have to count an
     * array to render a heading.
     */
    public static RecognitionRunResultDTO from(RecognitionRunResult r) {
        return new RecognitionRunResultDTO(r.preview(), r.posted(), r.wouldPost(), r.amount(), r.entries(),
                r.skippedLocked(), r.skippedLockedEntries(), r.booksLockedThrough(),
                r.errors().size(), r.errors());
    }
}
