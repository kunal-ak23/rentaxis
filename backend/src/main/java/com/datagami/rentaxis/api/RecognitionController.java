package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionRunResultDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Month-end close for per-day rent recognition (spec §8.4), plus the lease page's
 * <b>Recognition schedule</b> tab (§11).
 *
 * <p><b>Nothing here is {@code @Transactional}, deliberately.</b>
 * {@link RecognitionService#runTo} posts each entry in a transaction of that
 * entry's own and holds none across the loop; a transaction opened out here would
 * put that back, and with it two pooled connections held for the whole duration of
 * a run over thousands of rows.</p>
 *
 * <p><b>Two base paths, one controller.</b> The close lives under
 * {@code /api/v1/finance/recognition} with the rest of finance; the schedule is a
 * sub-resource of a lease and belongs at {@code /api/v1/leases/&#123;id&#125;/recognition},
 * where the lease page looks for its tabs. They are one feature and one role
 * story, so they are one file, with the shared prefix on the class and the rest on
 * each method.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class RecognitionController {

    /** Spec §8.4's refusal, worded once. */
    static final String FUTURE_PERIOD = "Cannot recognise income for periods that have not ended";

    private static final String FINANCE_ROLES = "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')";

    private final RecognitionService recognition;
    private final LeaseService leaseService;

    /** From the bean, never {@code LocalDate.now()} — "today" has to be fixable in a test. */
    private final Clock clock;

    public RecognitionController(RecognitionService recognition, LeaseService leaseService, Clock clock) {
        this.recognition = recognition;
        this.leaseService = leaseService;
        this.clock = clock;
    }

    /**
     * Everything still waiting to be recognised as of {@code to}, oldest period
     * first — the list the close screen shows beside the button.
     *
     * <p>Guarded by the same future-date rule as the run, because the two are read
     * together: a list offering rows the run would then refuse describes a close
     * that cannot happen.</p>
     *
     * @param to defaults to today
     */
    @GetMapping("/finance/recognition/pending")
    @PreAuthorize(FINANCE_ROLES)
    public ResponseEntity<List<RecognitionEntryDTO>> pending(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(recognition.pending(notInTheFuture(to)));
    }

    /**
     * Post every planned row whose period ended on or before {@code to}.
     *
     * <p>{@code preview=true} answers what the run would do and writes nothing —
     * no journals, no status changes — and says so: the response's {@code posted}
     * is 0 and {@code wouldPost} carries the count.</p>
     *
     * @param to      defaults to today; a date in the future is a 400
     * @param preview default false
     */
    @PostMapping("/finance/recognition/run")
    @PreAuthorize(FINANCE_ROLES)
    public ResponseEntity<RecognitionRunResultDTO> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean preview) {
        return ResponseEntity.ok(RecognitionRunResultDTO.from(recognition.runTo(notInTheFuture(to), preview)));
    }

    /**
     * The lease's whole schedule, every status, oldest period first.
     *
     * <p>PROPERTY_MANAGER reads this and the finance endpoints above, deliberately,
     * are closed to them: the schedule is part of the contract they manage, while
     * running a close is an act on the organisation's books. The role gate is not
     * the whole answer either — a manager assigned to one building must not read
     * another building's lease by id, so {@code LeaseService.requireReadableLease}
     * applies {@code LeaseAccessPolicy} before a single row is read.</p>
     */
    @GetMapping("/leases/{id}/recognition")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<RecognitionEntryDTO>> schedule(@PathVariable UUID id) {
        leaseService.requireReadableLease(id);
        return ResponseEntity.ok(recognition.scheduleFor(id));
    }

    /**
     * {@code to}, defaulted to today and refused if it is later than today.
     *
     * <p>Income is recognised for periods that have <em>ended</em>. Letting a close
     * run to a future date would post a {@code CIL} dated ahead of itself for a
     * month the tenancy has not yet been lived through, and the next real run would
     * find nothing left to do — the error would surface a month later as a gap, not
     * as a refusal. {@link RecognitionService#runTo} does not clamp; this is the
     * boundary that says no.</p>
     */
    private LocalDate notInTheFuture(LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        if (to == null) {
            return today;
        }
        if (to.isAfter(today)) {
            throw new BusinessRuleViolationException(FUTURE_PERIOD);
        }
        return to;
    }
}
