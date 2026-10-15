package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.core.service.ImportFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Recognises the months a period lock closed while their recognition was still
 * PLANNED (PR #400 review P2-1; bug 46's state), for the organisation in context:
 * one catch-up per lease and account pair, dated the first day after the lock
 * ({@link RecognitionService#catchUpLockedLease}).
 *
 * <p>Each lease in its own transaction, like the month-end run: one lease whose
 * accounts cannot be resolved costs that lease its catch-up and nothing else.
 * Holds no transaction across the loop.</p>
 */
@Service
public class LockedMonthsCatchUp {

    private static final Logger log = LoggerFactory.getLogger(LockedMonthsCatchUp.class);

    private final RecognitionService recognition;

    public LockedMonthsCatchUp(RecognitionService recognition) {
        this.recognition = recognition;
    }

    /** What the organisation-wide catch-up did, or would do. */
    public record Result(boolean preview, int leases, int months, BigDecimal amount, LocalDate postedOn,
                         List<String> monthsNamed, List<String> errors) { }

    public Result run(LocalDate today, boolean preview) {
        List<UUID> leaseIds = recognition.leasesWithLockedPlanned();
        int leases = 0;
        int months = 0;
        BigDecimal amount = BigDecimal.ZERO;
        LocalDate postedOn = null;
        TreeSet<String> named = new TreeSet<>(RecognitionService.MONTH_ORDER);
        List<String> errors = new ArrayList<>();
        for (UUID leaseId : leaseIds) {
            try {
                RecognitionService.LockedCatchUp one = recognition.catchUpLockedLease(leaseId, today, preview);
                leases += one.leases();
                months += one.months();
                amount = amount.add(one.amount());
                if (one.postedOn() != null) postedOn = one.postedOn();
                named.addAll(one.monthsNamed());
            } catch (RuntimeException e) {
                log.warn("Locked-months catch-up for lease {} could not be posted: {}", leaseId, e.getMessage());
                errors.add(ImportFailures.safe(e, ImportFailures.Kind.POST, log, "Locked-months catch-up, lease " + leaseId)
                        .message());
            }
        }
        return new Result(preview, leases, months, amount.setScale(2, RoundingMode.HALF_UP), postedOn,
                List.copyOf(named), errors);
    }
}
