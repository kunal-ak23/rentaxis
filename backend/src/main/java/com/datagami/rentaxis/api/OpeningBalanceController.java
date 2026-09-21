package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.cutover.ManualOpeningBalanceDTO;
import com.datagami.rentaxis.api.dto.cutover.OpeningBalanceGridDTO;
import com.datagami.rentaxis.api.dto.cutover.ReconciliationRowDTO;
import com.datagami.rentaxis.api.dto.cutover.SnapshotUploadResultDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cutover.OpeningBalanceService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Opening balances and the reconciliation report (spec §10.3, §11).
 *
 * <p><b>Role gate.</b> One class-level {@code @PreAuthorize}: SUPER_ADMIN,
 * TENANT_ADMIN and ACCOUNTANT (spec §11 puts "OB" in that list explicitly). Opening
 * the books writes a journal covering every account the organisation has; a
 * property manager has no business near it.</p>
 *
 * <p><b>The tenant guard.</b> {@code ApiSecurityFilter} authorises a SUPER_ADMIN
 * unconditionally but only populates {@code TenantContextHolder} once they have
 * picked an organisation, so a platform admin with none selected would otherwise
 * reach a service that assumes an ambient tenant — and on a list endpoint would read
 * across organisations, because the Hibernate tenant filter is left off when the
 * context is empty. Same guard and same wording as {@code RecognitionController},
 * {@code VoucherController} and {@code ImportBatchController}.</p>
 *
 * <p><b>Upload size.</b> The CSV goes through the ordinary multipart limit
 * (10 MB, {@code spring.servlet.multipart.max-file-size}); the global handler turns
 * {@code MaxUploadSizeExceededException} into a 400 rather than a raw 500. A trial
 * balance that big is not a trial balance.</p>
 *
 * <p>Thin by design: every rule lives in {@link OpeningBalanceService}.</p>
 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
public class OpeningBalanceController {

    /** Same wording as {@code RecognitionController.NO_TENANT} — one sentence, said consistently. */
    static final String NO_TENANT = "Select an organisation first";

    private final OpeningBalanceService service;

    /**
     * {@code date} is optional and defaults to the cut-over date: the opening journal
     * is dated the day before the books open, and its reversal belongs on the same
     * day. Letting a caller date it elsewhere would leave the opening entry and its
     * mirror in different periods.
     */
    public record ReverseObDTO(LocalDate date, String reason) {}

    /** Why the books are being opened again; shown on the reversal's narration. */
    public record RepostObDTO(String reason) {}

    /** Just enough of the journal for a toast and a link to the GL. */
    public record PostedJournalDTO(UUID id, String entryNumber, LocalDate entryDate) {
        static PostedJournalDTO of(JournalEntry e) {
            return new PostedJournalDTO(e.getId(), e.getEntryNumber(), e.getEntryDate());
        }
    }

    @GetMapping("/opening-balances")
    public ResponseEntity<OpeningBalanceGridDTO> grid() {
        requireTenantSelected();
        return ResponseEntity.ok(OpeningBalanceGridDTO.of(service.grid()));
    }

    @PostMapping(path = "/opening-balances/snapshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SnapshotUploadResultDTO> uploadSnapshot(@RequestParam("file") MultipartFile file)
            throws IOException {
        requireTenantSelected();
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleViolationException("Choose a trial-balance CSV to upload");
        }
        return ResponseEntity.ok(SnapshotUploadResultDTO.of(service.uploadSnapshot(file.getInputStream())));
    }

    @PutMapping("/opening-balances/{accountId}")
    public ResponseEntity<Void> setRow(@PathVariable UUID accountId, @RequestBody ManualOpeningBalanceDTO body) {
        requireTenantSelected();
        service.setRow(accountId, body.debit(), body.credit());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/opening-balances/post")
    public ResponseEntity<PostedJournalDTO> post() {
        requireTenantSelected();
        return ResponseEntity.ok(PostedJournalDTO.of(service.post()));
    }

    /**
     * Replace the opening balances with a corrected set: the live journal is reversed
     * and a new one posted in one transaction. Separate from {@link #post()} on
     * purpose — a double-click must not quietly replace opening balances somebody is
     * reconciling against, so replacing them is something the accountant asks for.
     */
    @PostMapping("/opening-balances/repost")
    public ResponseEntity<PostedJournalDTO> repost(@RequestBody(required = false) RepostObDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(PostedJournalDTO.of(service.repost(body == null ? null : body.reason())));
    }

    @PostMapping("/opening-balances/reverse")
    public ResponseEntity<PostedJournalDTO> reverse(@RequestBody(required = false) ReverseObDTO body) {
        requireTenantSelected();
        return ResponseEntity.ok(PostedJournalDTO.of(service.reverse(
                body == null ? null : body.date(), body == null ? null : body.reason())));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<List<ReconciliationRowDTO>> reconcile() {
        requireTenantSelected();
        return ResponseEntity.ok(service.reconcile().stream().map(ReconciliationRowDTO::of).toList());
    }

    /** Opening balances belong to an organisation; see the class Javadoc. */
    private void requireTenantSelected() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new BusinessRuleViolationException(NO_TENANT);
        }
    }
}
