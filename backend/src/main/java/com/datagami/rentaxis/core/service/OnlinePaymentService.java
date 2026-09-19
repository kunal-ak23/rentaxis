package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterChequeDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.OnlinePaymentPayload;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.TenantGatewayConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Paying a register row through the gateway, and the renter's view of what is
 * left to pay (spec §9.3).
 *
 * <p><b>There is one path to money and it goes through the register.</b> A
 * gateway session is started against a cheque, an authorisation moves that row to
 * {@code ONLINE_PENDING} and posts nothing, and a capture clears the row with the
 * same {@code CRT} a counter receipt writes. Nothing here builds a journal: every
 * ledger consequence is {@link ChequeService}'s, so a payment taken online and a
 * payment taken at the desk are indistinguishable in the books.</p>
 *
 * <p><b>A capture is announced twice.</b> The browser returns from Razorpay into
 * {@link #verifyPayment} and the webhook arrives independently, in either order,
 * sometimes at the same moment, and the webhook is retried on top of that. Both
 * roads run through {@link #capture}, which takes the {@code online_payments} row
 * lock first and clears the cheque <em>before</em> marking the payment captured —
 * so the payment can never claim money the register has not booked, and the
 * second report finds a CLEARED ONLINE row and posts nothing.</p>
 *
 * <p><b>Every public method is {@code @Transactional}</b>: {@code TenantAspect}
 * only enables the Hibernate tenant filter inside a transaction, so a read outside
 * one would cross tenants.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlinePaymentService {

    /**
     * What the renter is shown. DRAFT rows are a proposal on an unposted lease;
     * REPLACED, CANCELLED and RETURNED rows are history the landlord keeps and the
     * renter has already settled or been handed back.
     */
    private static final Set<ChequeStatus> VISIBLE = EnumSet.of(
            ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED, ChequeStatus.ONLINE_PENDING,
            ChequeStatus.CLEARED, ChequeStatus.BOUNCED);

    /** A row the landlord is still owed — what an outstanding penalty is measured over. */
    private static final Set<ChequeStatus> UNCOLLECTED = EnumSet.of(
            ChequeStatus.REGISTERED, ChequeStatus.DEPOSITED, ChequeStatus.ONLINE_PENDING,
            ChequeStatus.BOUNCED);

    private final ChequeRepository chequeRepository;
    private final OnlinePaymentRepository onlinePaymentRepository;
    private final TenantGatewayConfigRepository tenantGatewayConfigRepository;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final PaymentGatewayFactory paymentGatewayFactory;
    private final EncryptionService encryptionService;
    private final ChequeService chequeService;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final RenterRepository renterRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    // ------------------------------------------------------------------
    // the renter's list
    // ------------------------------------------------------------------

    /**
     * Everything this renter owes or has paid, off the register (spec §11,
     * "Renter portal": amounts due from the register, approved penalties).
     *
     * <p>Approved penalties need no separate query and no separate shape: an
     * approval creates an ordinary {@code CASH} collection row on the lease
     * carrying its {@code penaltyAssessmentId}, so the fine is simply another line
     * on this list — payable, chaseable and receiptable like any other. Which also
     * means {@code penaltyOutstanding} is computed from these same rows rather than
     * from the assessments, so the banner and the lines cannot disagree.</p>
     */
    @Transactional(readOnly = true)
    public List<RenterChequeDTO> getMyPayments(UUID userId) {
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));

        List<Cheque> rows = chequeRepository
                .findByRenter_IdAndStatusInOrderByChequeDateAsc(renter.getId(), VISIBLE);

        LocalDate today = LocalDate.now();
        Map<UUID, BigDecimal> penaltyByLease = penaltyOutstandingByLease(rows);
        Map<UUID, Boolean> onlineByProperty = new HashMap<>();

        List<RenterChequeDTO> result = new ArrayList<>(rows.size());
        for (Cheque c : rows) {
            Lease lease = c.getLease();
            if (lease == null) {
                continue;
            }
            int grace = lease.getGracePeriodDays();
            boolean due = ChequeDueRules.due(c, today);
            boolean overdue = ChequeDueRules.overdue(c, grace, today);
            Property property = c.getProperty();
            Unit unit = c.getUnit();
            boolean onlineEnabled = property == null || onlineByProperty.computeIfAbsent(
                    property.getId(), this::onlinePaymentEnabled);

            result.add(new RenterChequeDTO(
                    c.getId(),
                    lease.getId(),
                    c.getSeqNo(),
                    c.getChequeDate(),
                    c.getAmount(),
                    c.getStatus(),
                    c.getMode(),
                    c.getChequeNumber(),
                    c.getPayeeBank(),
                    c.getNarration(),
                    property != null ? property.getNameEn() : null,
                    unit != null ? unit.getUnitNumber() : null,
                    renter.getNameEn(),
                    due,
                    overdue,
                    // Gated on overdue, as ChequeMapper gates it: the rule answers
                    // "days since grace ended" unconditionally, which reads as 400
                    // days late next to a row that cleared a year ago.
                    overdue ? ChequeDueRules.daysOverdue(c, grace, today) : 0,
                    grace,
                    penaltyByLease.getOrDefault(lease.getId(), BigDecimal.ZERO),
                    payable(c, due),
                    onlineEnabled,
                    c.getPenaltyAssessmentId(),
                    c.getFailureReason(),
                    c.getClearedAt(),
                    c.getStatusChangedAt() != null ? c.getStatusChangedAt().toString() : null));
        }

        // findByRenter_Id... orders by cheque date; id breaks the tie so two
        // instalments dated the same day do not swap places between refreshes.
        result.sort(Comparator.comparing(RenterChequeDTO::dueDate)
                .thenComparing(RenterChequeDTO::id));
        return result;
    }

    /**
     * What the "Pay now" button charges.
     *
     * <p>A {@code BOUNCED} row is payable although its PDC receivable is long
     * reversed: {@link #createOrder} supersedes it with an ONLINE replacement that
     * carries its own {@code PDR}, which is the balance the capture then clears.
     * A {@code DEPOSITED} row is not — the paper is at the bank, and collecting it
     * twice is exactly what the register exists to prevent.</p>
     */
    private static BigDecimal payable(Cheque c, boolean due) {
        boolean collectable = c.getStatus() == ChequeStatus.REGISTERED || c.getStatus() == ChequeStatus.BOUNCED;
        return due && collectable ? c.getAmount() : BigDecimal.ZERO;
    }

    private Map<UUID, BigDecimal> penaltyOutstandingByLease(List<Cheque> rows) {
        Map<UUID, BigDecimal> byLease = new HashMap<>();
        for (Cheque c : rows) {
            if (c.getPenaltyAssessmentId() == null || !UNCOLLECTED.contains(c.getStatus())) {
                continue;
            }
            Lease lease = c.getLease();
            if (lease == null) {
                continue;
            }
            byLease.merge(lease.getId(), c.getAmount(), BigDecimal::add);
        }
        return byLease;
    }

    /**
     * Whether the property takes online payments — an opt-out, not an opt-in.
     *
     * <p>A property with no {@code RentCollectionSettings} row has never been
     * configured either way, and reading that as "disabled" would hide the pay
     * button on every property an admin has not visited.</p>
     */
    private boolean onlinePaymentEnabled(UUID propertyId) {
        return rentCollectionSettingsRepository.findByPropertyId(propertyId)
                .map(RentCollectionSettings::getOnlinePaymentEnabled)
                .orElse(Boolean.TRUE) != Boolean.FALSE;
    }

    // ------------------------------------------------------------------
    // starting a payment
    // ------------------------------------------------------------------

    /**
     * The renter is paying this instalment now (spec §9.3).
     *
     * <p><b>A bounced row is replaced, not paid.</b> Its {@code CBR} already
     * credited PDC receivable back to nothing, so a capture against it would drive
     * that balance negative while the rent receivable it was meant to settle stayed
     * debited. {@link ChequeService#replaceForOnlinePayment} supersedes it with one
     * ONLINE row of the same amount carrying its own {@code PDR}, and everything
     * from here on — the order, the {@code OnlinePayment}, the capture — is about
     * the replacement.</p>
     *
     * <p><b>The gateway is called last, inside the transaction.</b> If Razorpay
     * refuses the order the whole thing rolls back: the bounced cheque is still
     * BOUNCED, no replacement row exists, no row is stranded in
     * {@code ONLINE_PENDING}. A gateway order nobody can pay is cheaper than a
     * register that disagrees with the ledger.</p>
     */
    @Transactional
    public CreateOrderResponseDTO createOrder(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId)
                .orElseThrow(() -> new NotFoundException("Cheque not found"));
        requireGatewayAccess(cheque);

        LocalDate today = LocalDate.now();
        if (!ChequeDueRules.due(cheque, today)) {
            throw new BusinessRuleViolationException(
                    "This instalment is not due yet; it can be paid from " + cheque.getChequeDate() + ".");
        }
        if (cheque.getStatus() != ChequeStatus.REGISTERED && cheque.getStatus() != ChequeStatus.BOUNCED) {
            throw new BusinessRuleViolationException(
                    "This instalment cannot be paid online (current: " + cheque.getStatus() + ")");
        }
        if (cheque.getProperty() != null && !onlinePaymentEnabled(cheque.getProperty().getId())) {
            throw new BusinessRuleViolationException("Online payment is switched off for this property");
        }

        TenantGatewayConfig config = activeConfig();

        UUID targetId = cheque.getStatus() == ChequeStatus.BOUNCED
                ? chequeService.replaceForOnlinePayment(chequeId, today).id()
                : chequeId;
        ChequeDTO pending = chequeService.registerOnlinePending(targetId);

        String apiKey = encryptionService.decrypt(config.getApiKeyEncrypted());
        String apiSecret = encryptionService.decrypt(config.getApiSecretEncrypted());
        String currency = currencyOf(config);
        PaymentGatewayProvider provider = paymentGatewayFactory.getProvider(config.getGateway().getCode());
        CreateOrderResponseDTO response = provider.createOrder(
                pending.amount(), currency, "CHQ-" + targetId.toString().substring(0, 8), apiKey, apiSecret);

        Cheque target = chequeRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("Cheque not found"));
        OnlinePayment onlinePayment = new OnlinePayment();
        onlinePayment.setCheque(target);
        onlinePayment.setGateway(config.getGateway());
        onlinePayment.setGatewayOrderId(response.getOrderId());
        onlinePayment.setAmount(pending.amount());
        onlinePayment.setCurrency(currency);
        onlinePayment.setStatus(OnlinePaymentStatus.CREATED);
        // Zero, not the old add-on: an approved penalty is its own register row
        // with its own amount, and folding it into the rent order would collect a
        // fine nobody could point at a journal.
        onlinePayment.setPenaltyAmount(BigDecimal.ZERO);
        onlinePayment.setCreatedAt(Instant.now());
        onlinePayment.setUpdatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);

        Renter renter = target.getRenter();
        if (renter != null) {
            response.setRenterName(renter.getNameEn());
            response.setRenterEmail(renter.getEmail());
        }
        response.setSdkJsUrl(config.getGateway().getSdkJsUrl());
        return response;
    }

    // ------------------------------------------------------------------
    // the capture, from either direction
    // ------------------------------------------------------------------

    /**
     * The browser came back from the gateway with a signed result.
     *
     * <p>The signature is the whole authorisation of this call: a renter could
     * otherwise post any order id and have it cleared. A failed check releases the
     * row rather than leaving it pending, because the renter is sitting in front of
     * a screen that has to offer "try again".</p>
     */
    @Transactional
    public VerifyPaymentResponseDTO verifyPayment(VerifyPaymentRequestDTO request) {
        OnlinePayment onlinePayment = onlinePaymentRepository
                .findByGatewayOrderIdForUpdate(request.getGatewayOrderId())
                .orElseThrow(() -> new NotFoundException(
                        "Online payment not found for order: " + request.getGatewayOrderId()));

        TenantGatewayConfig config = activeConfig();
        String apiSecret = encryptionService.decrypt(config.getApiSecretEncrypted());
        PaymentGatewayProvider provider = paymentGatewayFactory.getProvider(config.getGateway().getCode());
        boolean valid = provider.verifyPaymentSignature(
                request.getGatewayOrderId(),
                request.getGatewayPaymentId(),
                request.getGatewaySignature(),
                apiSecret);

        VerifyPaymentResponseDTO response = new VerifyPaymentResponseDTO();
        if (valid) {
            onlinePayment.setGatewaySignature(request.getGatewaySignature());
            capture(onlinePayment, request.getGatewayPaymentId(), LocalDate.now());
            response.setSuccess(true);
            response.setMessage("Payment verified and recorded successfully");
            response.setPaymentId(onlinePayment.getGatewayPaymentId());
        } else {
            release(onlinePayment, "Signature verification failed");
            response.setSuccess(false);
            response.setMessage("Payment verification failed");
            notifyRenter(onlinePayment, "PAYMENT_FAILED", "Online Payment Failed",
                    "Your online payment could not be verified. Please try again.");
        }
        return response;
    }

    /**
     * The gateway's own report of the same capture, from
     * {@code WebhookService.processRazorpayWebhook}.
     *
     * <p><b>The caller has already verified the webhook signature</b>, and must
     * have: this path carries no {@code Authentication} at all, so
     * {@code ChequeService}'s gateway guard waves it through as an internal caller.
     * The signature check is the only thing standing in front of the register here,
     * which is why it happens before the handler reaches this method and not
     * inside it.</p>
     *
     * <p>Runs {@code REQUIRES_NEW}, deliberately not joining the webhook handler's
     * transaction. A clearing failure — a lock conflict, a row in an impossible
     * status — would otherwise mark the shared transaction rollback-only, which
     * discards the {@code WebhookLog} row the handler writes in its {@code finally}
     * (losing the record of exactly the anomaly worth recording) and turns a clean
     * catch-and-continue into an {@code UnexpectedRollbackException} at commit.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void captureFromWebhook(UUID onlinePaymentId, String gatewayPaymentId) {
        capture(lockPayment(onlinePaymentId), gatewayPaymentId, LocalDate.now());
    }

    /** The gateway reporting that the session failed; same {@code REQUIRES_NEW} reasoning. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failFromWebhook(UUID onlinePaymentId, String reason) {
        release(lockPayment(onlinePaymentId), reason == null ? "Gateway reported the payment failed" : reason);
    }

    /**
     * The renter closed the checkout without paying: the row goes back on the
     * register and nothing posts.
     */
    @Transactional
    public void cancelPendingOnlinePayment(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId)
                .orElseThrow(() -> new NotFoundException("Cheque not found"));
        requireGatewayAccess(cheque);

        if (cheque.getStatus() == ChequeStatus.ONLINE_PENDING) {
            chequeService.revertOnlinePending(chequeId);
        }
        // Idempotent on a row that is already back: a renter who closes the modal
        // twice, or closes it after the webhook already cleared the row, gets a
        // no-op rather than a refusal.
        for (OnlinePayment op : onlinePaymentRepository.findByCheque_Id(chequeId)) {
            if (op.getStatus() == OnlinePaymentStatus.CREATED) {
                op.setStatus(OnlinePaymentStatus.FAILED);
                op.setFailureReason("User cancelled checkout");
                op.setUpdatedAt(Instant.now());
                onlinePaymentRepository.save(op);
            }
        }
    }

    // ------------------------------------------------------------------
    // the one place a capture is booked
    // ------------------------------------------------------------------

    /**
     * Money arrived: clear the register row, then record the payment as captured.
     *
     * <p><b>That order is the invariant.</b> {@code clearOnline} is what writes the
     * {@code CRT}; if it refuses or throws, this transaction rolls back and the
     * payment stays CREATED. Marking the payment captured first would leave a row
     * claiming money the ledger never booked — which is the state nobody can
     * reconcile out of afterwards.</p>
     *
     * <p><b>A capture can arrive after a cancellation.</b> The renter closed the
     * modal, {@link #cancelPendingOnlinePayment} put the row back to REGISTERED,
     * and Razorpay took the money anyway — a race the gateway makes no promise
     * about. {@code clearOnline} would refuse a REGISTERED row, which would leave
     * captured money unposted, so the row is re-pended first. Only for <em>this</em>
     * payment's own cheque: nothing else may be dragged into ONLINE_PENDING from
     * here.</p>
     *
     * <p>The notification and the email fire only on the transition, so a retried
     * webhook does not send the renter a second receipt for one payment.</p>
     */
    private void capture(OnlinePayment onlinePayment, String gatewayPaymentId, LocalDate capturedOn) {
        Cheque cheque = onlinePayment.getCheque();
        if (cheque == null) {
            throw new BusinessRuleViolationException(
                    "This online payment has no register row; it cannot be cleared.");
        }

        // The same capture, reported a second time: this payment already booked it
        // and the row it booked is cleared, so there is nothing left to do.
        //
        // It has to be decided HERE rather than left to clearOnline's own
        // idempotency, which only forgives an already-CLEARED row of mode ONLINE —
        // and most rows paid online are ordinary PDC instalments that keep their
        // mode. Deliberately conjunctive: a CLEARED row whose payment is not
        // captured means somebody banked the cheque while the renter was paying for
        // it, and that must reach clearOnline and be refused loudly rather than be
        // swallowed as a duplicate.
        if (onlinePayment.getStatus() == OnlinePaymentStatus.CAPTURED
                && cheque.getStatus() == ChequeStatus.CLEARED) {
            return;
        }

        if (cheque.getStatus() == ChequeStatus.REGISTERED
                && (cheque.getMode() == ChequeMode.ONLINE || cheque.getMode() == ChequeMode.PDC)) {
            chequeService.registerOnlinePending(cheque.getId());
        }
        chequeService.clearOnline(cheque.getId(), capturedOn, settlementAccountId());

        onlinePayment.setStatus(OnlinePaymentStatus.CAPTURED);
        if (gatewayPaymentId != null && !gatewayPaymentId.isBlank()) {
            onlinePayment.setGatewayPaymentId(gatewayPaymentId);
        }
        onlinePayment.setFailureReason(null);
        onlinePayment.setUpdatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);

        events.publishEvent(new EmailEvent(this,
                EmailEventType.ONLINE_PAYMENT_RECEIVED,
                onlinePayment.getTenantId(),
                OnlinePaymentPayload.ofCheque(onlinePayment, cheque, null),
                "ONLINE_PAYMENT_RECEIVED:" + onlinePayment.getId()));
        notifyRenter(onlinePayment, "PAYMENT_CLEARED", "Online Payment Successful",
                "Instalment #" + cheque.getSeqNo() + " of " + cheque.getAmount()
                        + " AED was paid online. Your receipt is available.");
    }

    /**
     * The session did not produce money: the row goes back to REGISTERED and the
     * payment is marked failed.
     *
     * <p>A captured payment is never released. A late "payment.failed" delivery for
     * a session that already captured would otherwise hand a cleared instalment
     * back to the renter as unpaid while the {@code CRT} stayed in the ledger.</p>
     */
    private void release(OnlinePayment onlinePayment, String reason) {
        if (onlinePayment.getStatus() == OnlinePaymentStatus.CAPTURED) {
            log.warn("Ignoring a failure report for online payment {}: it was already captured", onlinePayment.getId());
            return;
        }
        Cheque cheque = onlinePayment.getCheque();
        if (cheque != null && cheque.getStatus() == ChequeStatus.ONLINE_PENDING) {
            chequeService.revertOnlinePending(cheque.getId());
        }
        onlinePayment.setStatus(OnlinePaymentStatus.FAILED);
        onlinePayment.setFailureReason(reason);
        onlinePayment.setUpdatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private OnlinePayment lockPayment(UUID onlinePaymentId) {
        return onlinePaymentRepository.findByIdForUpdate(onlinePaymentId)
                .orElseThrow(() -> new NotFoundException("Online payment not found: " + onlinePaymentId));
    }

    private TenantGatewayConfig activeConfig() {
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrue();
        if (configs.isEmpty()) {
            throw new BusinessRuleViolationException("No active payment gateway configured");
        }
        return configs.get(0);
    }

    /**
     * Where the gateway's payout lands. Null when the organisation has not
     * nominated one, in which case the capture falls back to the property's BANK
     * role exactly as a counter receipt does.
     */
    private UUID settlementAccountId() {
        Account settlement = activeConfig().getSettlementAccount();
        return settlement != null ? settlement.getId() : null;
    }

    private static String currencyOf(TenantGatewayConfig config) {
        String supported = config.getGateway().getSupportedCurrencies();
        return supported != null && supported.contains("AED") ? "AED" : "INR";
    }

    /**
     * The same three-way rule {@code ChequeService} applies to its gateway methods
     * — a manager may act for any lease they manage, a renter only for their own,
     * an unauthenticated internal caller is the webhook — applied here so a refusal
     * happens before this service says anything about a row the caller may not see.
     *
     * <p>Duplicated rather than shared because the deciding method is private to
     * {@code ChequeService} and that file is not this task's to restructure; the
     * register re-checks it on every call below, so this is a fail-fast, not the
     * boundary.</p>
     */
    private Lease requireGatewayAccess(Cheque cheque) {
        Lease lease = cheque.getLease();
        if (lease == null) {
            throw new NotFoundException("Lease not found");
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && !leaseAccessPolicy.canManage(lease)) {
            leaseAccessPolicy.requireReadable(lease);
        }
        return lease;
    }

    private void notifyRenter(OnlinePayment onlinePayment, String type, String title, String message) {
        try {
            Cheque cheque = onlinePayment.getCheque();
            UUID renterUserId = cheque != null && cheque.getRenter() != null
                    ? cheque.getRenter().getUserId() : null;
            if (renterUserId != null) {
                notificationService.notifyInAppInNewTx(onlinePayment.getTenantId(), renterUserId,
                        type, title, message, "CHEQUE", cheque.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send {} notification for online payment {}: {}",
                    type, onlinePayment.getId(), e.getMessage());
        }
    }
}
