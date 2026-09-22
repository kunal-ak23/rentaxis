package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterChequeDTO;
import com.datagami.rentaxis.api.dto.UnappliedOnlinePaymentDTO;
import com.datagami.rentaxis.api.dto.UnappliedOnlinePaymentTotalsDTO;
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
import com.datagami.rentaxis.core.service.cheque.ChequeGatewayRules;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentGateway;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * second report of the same capture posts nothing.</p>
 *
 * <p><b>A capture the register cannot accept is recorded, not absorbed.</b> Two
 * orders against one instalment, a clerk banking the cheque mid-payment, an amount
 * that is not the row's: the money is real and the instalment cannot take it, so
 * the payment lands in {@code CAPTURED_UNAPPLIED} with the reason on it and an
 * {@code ERROR} line, the gateway gets a 200 so it stops retrying, and the renter
 * is told their payment arrived but was not applied. The alternative — a second
 * {@code CRT}, or a silent "already done" — is money nobody can account for.</p>
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

    /**
     * A row the renter can settle right now — the statuses {@link #createOrder}
     * accepts, and the ones {@link #payable} puts an amount against. DEPOSITED is
     * absent on purpose: the paper is at the bank and will clear there.
     */
    private static final Set<ChequeStatus> COLLECTABLE = EnumSet.of(
            ChequeStatus.REGISTERED, ChequeStatus.BOUNCED, ChequeStatus.ONLINE_PENDING);

    /**
     * The gateway took the money. A payment in one of these never goes backwards:
     * no later failure report, signature failure or redelivered capture may change
     * its status, amount or capture time ({@code updatedAt}).
     */
    private static final Set<OnlinePaymentStatus> MONEY_CAPTURED = EnumSet.of(
            OnlinePaymentStatus.CAPTURED, OnlinePaymentStatus.CAPTURED_UNAPPLIED,
            OnlinePaymentStatus.REFUNDED);

    private static final String UNAPPLIED_PREFIX = "Captured but not applied: ";

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
                    // The lease is already in hand — the grace period above came off
                    // it — so this is a field of an object this loop has loaded, not
                    // another query.
                    lease.getStatus(),
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
                    payableOnline(c, due, onlineEnabled),
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
     * What the renter still owes on this row today, by whatever means.
     *
     * <p>A {@code BOUNCED} row is payable although its PDC receivable is long
     * reversed: {@link #createOrder} supersedes it with an ONLINE replacement that
     * carries its own {@code PDR}, which is the balance the capture then clears.
     * An {@code ONLINE_PENDING} row is payable because a checkout the renter
     * abandoned is an unpaid instalment they may simply start again. A
     * {@code DEPOSITED} row is not — the paper is at the bank, and collecting it
     * twice is exactly what the register exists to prevent.</p>
     *
     * <p>This is the <em>amount</em>, not the door: a CASH rent row is payable and
     * is paid at the counter. {@link #payableOnline} is the door.</p>
     */
    private static BigDecimal payable(Cheque c, boolean due) {
        return due && COLLECTABLE.contains(c.getStatus()) ? c.getAmount() : BigDecimal.ZERO;
    }

    /**
     * Whether {@link #createOrder} would accept this row right now — the renter
     * portal's Pay-now flag.
     *
     * <p><b>Computed from the same rules the order path enforces</b>, which is the
     * whole point of it existing. The portal used to decide from the status and the
     * amount alone, so an approved penalty (a CASH collection row) and any
     * TRANSFER-mode instalment were advertised as payable and then refused by
     * {@code registerOnlinePending} with a raw Java sentence. The four conditions
     * below are, in order, {@code createOrder}'s four guards.</p>
     *
     * <p>{@code onlineEnabled} is passed in rather than looked up: the caller has
     * already memoised it per property, and asking again per row would be a query
     * per instalment on a screen that is one query today.</p>
     */
    private static boolean payableOnline(Cheque c, boolean due, boolean onlineEnabled) {
        return due
                && COLLECTABLE.contains(c.getStatus())
                && ChequeGatewayRules.payableThroughGateway(c)
                && onlineEnabled;
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
    // finance's refund worklist
    // ------------------------------------------------------------------

    /**
     * Captures the gateway took that the register could not accept
     * ({@code CAPTURED_UNAPPLIED}), newest capture first — each one a refund the
     * landlord owes the renter.
     *
     * <p>The order is fixed by the query; any sort on the request is ignored, so a
     * client cannot sort by a path the fetch-joined query does not know. Tenant
     * scope is the query's own {@code tenant_id} predicate and the Hibernate
     * filter, which {@code TenantAspect} enables inside this transaction. With no
     * tenant selected (a super admin who has not picked one) the list is empty.</p>
     */
    @Transactional(readOnly = true)
    public Page<UnappliedOnlinePaymentDTO> unapplied(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        if (tenantId == null) {
            return Page.empty(page);
        }
        // Mapped inside this transaction; the relations are fetch-joined anyway.
        return onlinePaymentRepository.findUnapplied(tenantId, page)
                .map(OnlinePaymentService::toUnappliedDto);
    }

    /** The same worklist as two numbers for a dashboard tile; the total is zero, never null. */
    @Transactional(readOnly = true)
    public UnappliedOnlinePaymentTotalsDTO unappliedTotals() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return new UnappliedOnlinePaymentTotalsDTO(0, BigDecimal.ZERO);
        }
        BigDecimal total = onlinePaymentRepository.sumUnapplied(tenantId);
        return new UnappliedOnlinePaymentTotalsDTO(
                onlinePaymentRepository.countUnapplied(tenantId),
                total != null ? total : BigDecimal.ZERO);
    }

    private static UnappliedOnlinePaymentDTO toUnappliedDto(OnlinePayment payment) {
        Cheque cheque = payment.getCheque();
        Lease lease = cheque.getLease();
        Property property = cheque.getProperty();
        Unit unit = cheque.getUnit();
        Renter renter = cheque.getRenter();
        return new UnappliedOnlinePaymentDTO(
                payment.getId(),
                payment.getCreatedAt(),
                // markUnapplied stamps updatedAt as it records the capture; there is
                // no column of its own for the capture time.
                payment.getUpdatedAt(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getGatewayOrderId(),
                payment.getGatewayPaymentId(),
                payment.getFailureReason(),
                cheque.getId(),
                cheque.getChequeNumber(),
                cheque.getStatus(),
                lease.getId(),
                LeaseService.displayContractNumber(property.getCode(), lease.getContractNumber()),
                renter.getNameEn(),
                property.getNameEn(),
                unit != null ? unit.getUnitNumber() : null);
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
        ChequeStatus status = cheque.getStatus();
        if (!COLLECTABLE.contains(status)) {
            throw new BusinessRuleViolationException(
                    "This instalment cannot be paid online (current: " + status + ")");
        }
        if (cheque.getProperty() != null && !onlinePaymentEnabled(cheque.getProperty().getId())) {
            throw new BusinessRuleViolationException("Online payment is switched off for this property");
        }

        TenantGatewayConfig config = activeConfig();

        UUID targetId;
        BigDecimal amount;
        if (status == ChequeStatus.ONLINE_PENDING) {
            // The renter closed the browser tab mid-checkout and came back. Razorpay
            // sends no payment.failed for an abandoned order and there is no expiry
            // sweep, so refusing here left them unable to pay their own instalment
            // through any door at all — the row could not be deposited, received,
            // cancelled or handed back either. The stale session is superseded and
            // the row stays pending for the new order.
            supersedeAbandonedCheckouts(cheque);
            targetId = chequeId;
            amount = cheque.getAmount();
        } else {
            targetId = status == ChequeStatus.BOUNCED
                    ? chequeService.replaceForOnlinePayment(chequeId, today).id()
                    : chequeId;
            amount = chequeService.registerOnlinePending(targetId).amount();
        }

        String apiKey = encryptionService.decrypt(config.getApiKeyEncrypted());
        String apiSecret = encryptionService.decrypt(config.getApiSecretEncrypted());
        String currency = currencyOf(config);
        PaymentGatewayProvider provider = paymentGatewayFactory.getProvider(config.getGateway().getCode());
        CreateOrderResponseDTO response = provider.createOrder(
                amount, currency, "CHQ-" + targetId.toString().substring(0, 8), apiKey, apiSecret);

        Cheque target = chequeRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("Cheque not found"));
        OnlinePayment onlinePayment = new OnlinePayment();
        onlinePayment.setCheque(target);
        onlinePayment.setGateway(config.getGateway());
        onlinePayment.setGatewayOrderId(response.getOrderId());
        onlinePayment.setAmount(amount);
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

        // BEFORE the signature is even checked, and emphatically before anything is
        // released. A failing signature releases the session, so without this a
        // renter could post someone else's order id with any old signature and hand
        // that renter's pending instalment back to the register mid-payment — a
        // denial of service on another tenancy, using nothing but an order id.
        if (onlinePayment.getCheque() != null) {
            requireGatewayAccess(onlinePayment.getCheque());
        }

        TenantGatewayConfig config = activeConfig(onlinePayment.getGateway());
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
            // No reported amount on this path: the client callback carries only
            // ids and a signature, and PaymentGatewayProvider exposes no order
            // fetch to ask Razorpay what it actually charged. So the only money
            // check available here is the recorded order amount against the row,
            // which capture() does for both paths; the webhook, which does carry
            // the captured amount, is what catches a gateway charging something
            // else. Noted in the task report.
            String unapplied = capture(onlinePayment, request.getGatewayPaymentId(), null, null, LocalDate.now());
            if (unapplied != null) {
                response.setSuccess(false);
                response.setMessage("Your payment was received but could not be applied to this instalment ("
                        + unapplied + "). Please contact the landlord for a refund.");
                response.setPaymentId(onlinePayment.getGatewayPaymentId());
                return response;
            }
            response.setSuccess(true);
            response.setMessage("Payment verified and recorded successfully");
            response.setPaymentId(onlinePayment.getGatewayPaymentId());
        } else {
            String ignored = release(onlinePayment, "Signature verification failed");
            response.setSuccess(false);
            response.setMessage("Payment verification failed");
            // A captured payment is left as it is, and the renter is not told it failed.
            if (ignored == null) {
                notifyRenter(onlinePayment, "PAYMENT_FAILED", "Online Payment Failed",
                        "Your online payment could not be verified. Please try again.");
            }
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
     *
     * @param reportedMinorUnits the gateway's own captured amount in minor units
     *        (fils/paise), from the webhook payload; null when the delivery did not
     *        carry one.
     * @return null when the capture was applied, else the reason it could not be —
     *         a reason is <em>not</em> an error: the money is real, the state is
     *         recorded as {@code CAPTURED_UNAPPLIED}, and this transaction must
     *         commit so the record survives and the gateway stops retrying.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String captureFromWebhook(UUID onlinePaymentId, String gatewayPaymentId,
                                     Long reportedMinorUnits, String reportedCurrency) {
        return capture(lockPayment(onlinePaymentId), gatewayPaymentId,
                reportedMinorUnits, reportedCurrency, LocalDate.now());
    }

    /**
     * The gateway reporting that the session failed; same {@code REQUIRES_NEW} reasoning.
     *
     * @return null when the session was released, else why the report was ignored
     *         (the payment had already captured), for the webhook audit row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String failFromWebhook(UUID onlinePaymentId, String reason) {
        return release(lockPayment(onlinePaymentId), reason == null ? "Gateway reported the payment failed" : reason);
    }

    /**
     * A capture that the register could not take because posting it <em>threw</em>:
     * record it as money owed back, in a transaction of its own.
     *
     * <p>{@link #captureFromWebhook} answers the anticipated refusals by returning a
     * reason and writing {@code CAPTURED_UNAPPLIED} itself. This is for the other
     * kind: the books locked through today, a BANK role with no account behind it,
     * a lease somebody reverted to DRAFT. Those roll the capture's own transaction
     * back, so nothing at all was recorded — the renter was charged and the payment
     * sat at CREATED, off finance's refund list, off the register, with the only
     * trace a {@code webhook_logs} row nobody has a screen for.</p>
     *
     * <p>Money the gateway took must always be findable: applied to an instalment,
     * or on the unapplied list as a refund. So the fact is written here, in a fresh
     * transaction (the capture's is already rolled back), with the reason on it.</p>
     *
     * <p><b>The terminal rule still holds.</b> A payment already in
     * {@code MONEY_CAPTURED} is left exactly as it is: a redelivery whose posting
     * throws must not restate an applied capture as a refund, or move an unapplied
     * one's capture time.</p>
     *
     * @return the reason as recorded, or the reason already on the row when this
     *         capture had in fact been recorded before.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordUnappliedCapture(UUID onlinePaymentId, String gatewayPaymentId, String reason) {
        OnlinePayment onlinePayment = lockPayment(onlinePaymentId);
        if (MONEY_CAPTURED.contains(onlinePayment.getStatus())) {
            log.warn("A capture that could not be posted was already recorded for online payment {}: {}",
                    onlinePayment.getId(), onlinePayment.getStatus());
            return onlinePayment.getStatus() == OnlinePaymentStatus.CAPTURED
                    ? null
                    : recordedUnappliedReason(onlinePayment);
        }
        markUnapplied(onlinePayment, onlinePayment.getCheque(), gatewayPaymentId, reason);
        return reason;
    }

    /**
     * Staff hand an abandoned gateway session back to the register (spec §7.2).
     *
     * <p><b>Why this exists.</b> {@code ONLINE_PENDING} used to be a dead end. A
     * renter who closes the browser <em>tab</em> — not the modal, which calls
     * {@link #cancelPendingOnlinePayment} — leaves the row there: Razorpay sends no
     * {@code payment.failed} for an abandoned order, there is no expiry sweep, and
     * every staff action on the register ({@code deposit}, {@code receive},
     * {@code cancel}, {@code returnToTenant}) refuses the status. The paper cheque
     * for that instalment could no longer be banked, the lease could not be amended,
     * and a termination could not hand the instrument back.</p>
     *
     * <p><b>Refused once money exists.</b> A payment in {@code MONEY_CAPTURED} means
     * the gateway took the renter's money — applied, unapplied or refunded — and
     * releasing the row would either invite a second collection or hide a refund
     * that is owed. That decision belongs to finance on the unapplied worklist, not
     * to a button that says "release".</p>
     *
     * <p>Manage-level, not the gateway's three-way rule: this is staff correcting
     * the register, so a renter may not reach it even for their own row. The
     * transition itself is {@code revertOnlinePending}'s, unchanged, so a row that
     * is not in a session is refused in the same words as everywhere else.</p>
     */
    @Transactional
    public ChequeDTO releaseOnlinePending(UUID chequeId) {
        Cheque cheque = chequeRepository.findById(chequeId)
                .orElseThrow(() -> new NotFoundException("Cheque not found"));
        Lease lease = cheque.getLease();
        if (lease == null) {
            throw new NotFoundException("Lease not found");
        }
        leaseAccessPolicy.requireManageable(lease);

        List<OnlinePayment> sessions = onlinePaymentRepository.findByCheque_Id(chequeId);
        requireNoMoneyTaken(sessions,
                "The gateway has already taken money for this instalment, so the row cannot be released. "
                        + "Check the unapplied-payments list: what is owed here is a refund, not a release.");

        ChequeDTO released = chequeService.revertOnlinePending(chequeId);
        failOpenCheckouts(sessions, "Released by staff: the renter's checkout was abandoned");
        return released;
    }

    /**
     * The renter is starting a fresh checkout over a session they never finished:
     * the old order is marked failed so only one is live on the row.
     *
     * <p>Not {@code release()}: that would revert the cheque to REGISTERED, and the
     * row is about to be pending again for the new order. FAILED is deliberately not
     * in {@code MONEY_CAPTURED}, so if the abandoned order turns out to have captured
     * after all, that capture is still applied — and if both capture, the second
     * lands in {@code CAPTURED_UNAPPLIED} as a refund, which is the existing
     * two-orders rule.</p>
     */
    private void supersedeAbandonedCheckouts(Cheque cheque) {
        List<OnlinePayment> sessions = onlinePaymentRepository.findByCheque_Id(cheque.getId());
        requireNoMoneyTaken(sessions,
                "A payment for this instalment has already been taken by the gateway. "
                        + "Please contact the landlord rather than paying it again.");
        failOpenCheckouts(sessions, "Superseded by a new checkout on the same instalment");
    }

    private static void requireNoMoneyTaken(List<OnlinePayment> sessions, String message) {
        for (OnlinePayment session : sessions) {
            if (MONEY_CAPTURED.contains(session.getStatus())) {
                throw new BusinessRuleViolationException(message);
            }
        }
    }

    private void failOpenCheckouts(List<OnlinePayment> sessions, String reason) {
        for (OnlinePayment session : sessions) {
            if (session.getStatus() == OnlinePaymentStatus.CREATED) {
                session.setStatus(OnlinePaymentStatus.FAILED);
                session.setFailureReason(reason);
                session.setUpdatedAt(Instant.now());
                onlinePaymentRepository.save(session);
            }
        }
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
     *
     * <p><b>A verified capture the register cannot accept is recorded, never
     * absorbed.</b> {@link #unappliable} names the cases; when it answers, this
     * writes {@code CAPTURED_UNAPPLIED} and returns normally so the state
     * <em>commits</em>. Throwing would roll the record back and leave the gateway
     * retrying a delivery nobody can act on; returning "already done" would lose
     * the fact that the landlord owes a refund.</p>
     *
     * @return null when the capture was applied, else the reason it was not.
     */
    private String capture(OnlinePayment onlinePayment, String gatewayPaymentId,
                           Long reportedMinorUnits, String reportedCurrency, LocalDate capturedOn) {
        Cheque cheque = onlinePayment.getCheque();
        if (cheque == null) {
            throw new BusinessRuleViolationException(
                    "This online payment has no register row; it cannot be cleared.");
        }

        // The same capture, reported a second time. Decided on the PAYMENT's status
        // alone, and before anything is written, so the first capture's outcome and
        // time (updatedAt) stand:
        //
        // - CAPTURED: this payment already booked its CRT. Nothing else is left to
        //   do, whatever the row has done since — a row bounced from CLEARED (a
        //   chargeback) must not turn an applied capture into a refund. It has to
        //   be decided HERE rather than left to clearOnline's own idempotency, which
        //   only forgives an already-CLEARED row of mode ONLINE. Any OTHER payment
        //   arriving at a CLEARED row is still CREATED and falls through to
        //   unappliable(): the two-orders case.
        // - CAPTURED_UNAPPLIED / REFUNDED: already recorded as money the register
        //   refused; answer with the recorded reason and re-record nothing.
        if (onlinePayment.getStatus() == OnlinePaymentStatus.CAPTURED) {
            return null;
        }
        if (MONEY_CAPTURED.contains(onlinePayment.getStatus())) {
            log.warn("Ignoring a repeated capture report for online payment {}: already {}",
                    onlinePayment.getId(), onlinePayment.getStatus());
            return recordedUnappliedReason(onlinePayment);
        }

        String reason = unappliable(onlinePayment, cheque, reportedMinorUnits, reportedCurrency);
        if (reason != null) {
            markUnapplied(onlinePayment, cheque, gatewayPaymentId, reason);
            return reason;
        }

        if (cheque.getStatus() == ChequeStatus.REGISTERED) {
            chequeService.registerOnlinePending(cheque.getId());
        }
        chequeService.clearOnline(cheque.getId(), capturedOn, settlementAccountId(onlinePayment));

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
        return null;
    }

    /**
     * Why this verified capture cannot be posted to this row — or null when it can.
     *
     * <p>Three families, all of them real and none of them the renter's fault:</p>
     *
     * <ul>
     *   <li><b>The row is already settled by something else.</b> Two orders can
     *       exist against one instalment: the renter abandons the first checkout,
     *       the row goes back to REGISTERED, they pay through a second order — and
     *       then the first order captures too. Both are genuine money; only one
     *       instalment exists. The second arrival is a refund, not a second
     *       {@code CRT}.</li>
     *   <li><b>A clerk moved the row.</b> DEPOSITED (the paper is at the bank and
     *       will clear there), CANCELLED, REPLACED or RETURNED. Clearing any of
     *       them online would post against an instrument the register has already
     *       accounted for another way.</li>
     *   <li><b>The money does not match.</b> The order was raised for the row's
     *       amount, so anything else — a part payment, a different currency, a
     *       gateway reporting a figure the order never asked for — is not this
     *       instalment being settled, and posting it would clear a 12,000 debt with
     *       9,000.</li>
     * </ul>
     *
     * <p>A CASH or TRANSFER <em>rent</em> row is refused too: those are recorded
     * when they arrive, by {@code receive()}, and a gateway has no business clearing
     * one. An approved penalty's collection row is CASH by construction and is
     * deliberately not refused — {@link ChequeGatewayRules} is the one place that
     * decides, shared with the order path and the renter's portal.</p>
     */
    private static String unappliable(OnlinePayment onlinePayment, Cheque cheque,
                                      Long reportedMinorUnits, String reportedCurrency) {
        ChequeStatus status = cheque.getStatus();
        if (status == ChequeStatus.CLEARED) {
            return "instalment " + cheque.getSeqNo() + " was already settled by another payment";
        }
        if (status != ChequeStatus.REGISTERED && status != ChequeStatus.ONLINE_PENDING) {
            return "instalment " + cheque.getSeqNo() + " is " + status + " and can no longer be paid online";
        }
        if (!ChequeGatewayRules.payableThroughGateway(cheque)) {
            return "instalment " + cheque.getSeqNo() + " is a " + cheque.getMode() + " receipt";
        }

        BigDecimal ordered = onlinePayment.getAmount();
        if (ordered == null || cheque.getAmount() == null
                || ordered.compareTo(cheque.getAmount()) != 0) {
            return "the order was for " + ordered + " but instalment " + cheque.getSeqNo()
                    + " is " + cheque.getAmount();
        }
        if (reportedCurrency != null && onlinePayment.getCurrency() != null
                && !reportedCurrency.equalsIgnoreCase(onlinePayment.getCurrency())) {
            return "the gateway captured " + reportedCurrency + " but the order was "
                    + onlinePayment.getCurrency();
        }
        if (reportedMinorUnits != null && reportedMinorUnits != minorUnits(ordered)) {
            // Razorpay speaks in the currency's smallest unit — RazorpayProvider
            // multiplies by 100 on the way out, so the comparison happens there too
            // rather than dividing a long back into a BigDecimal and arguing about
            // rounding.
            return "the gateway captured " + reportedMinorUnits + " minor units but the order was "
                    + minorUnits(ordered);
        }
        return null;
    }

    /**
     * The same conversion both providers put on the wire — shared rather than
     * copied, because this is the other side of the comparison the capture check
     * makes against what the gateway says it took.
     */
    private static long minorUnits(BigDecimal amount) {
        return PaymentGatewayProvider.minorUnits(amount);
    }

    /**
     * Money taken that the register refused: named, logged and kept.
     *
     * <p>No {@code CRT}, no success email, no "your payment went through"
     * notification — the renter has paid and the instalment is not settled, and
     * telling them otherwise is worse than telling them nothing. The {@code ERROR}
     * line is what a finance person greps for when a renter says they paid twice;
     * it carries ids and an amount and no credentials.</p>
     */
    private void markUnapplied(OnlinePayment onlinePayment, Cheque cheque,
                               String gatewayPaymentId, String reason) {
        if (gatewayPaymentId != null && !gatewayPaymentId.isBlank()) {
            onlinePayment.setGatewayPaymentId(gatewayPaymentId);
        }
        onlinePayment.setStatus(OnlinePaymentStatus.CAPTURED_UNAPPLIED);
        onlinePayment.setFailureReason(
                UNAPPLIED_PREFIX + reason
                        + (onlinePayment.getGatewayPaymentId() != null
                                ? " (gateway payment " + onlinePayment.getGatewayPaymentId() + ")" : ""));
        onlinePayment.setUpdatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);

        log.error("Captured online payment could not be applied: payment={} order={} gatewayPayment={} "
                        + "cheque={} amount={} {} — reason: {}. A refund is owed.",
                onlinePayment.getId(), onlinePayment.getGatewayOrderId(),
                onlinePayment.getGatewayPaymentId(), cheque != null ? cheque.getId() : null,
                onlinePayment.getAmount(), onlinePayment.getCurrency(), reason);
    }

    /**
     * The session did not produce money: the row goes back to REGISTERED and the
     * payment is marked failed.
     *
     * <p>A captured payment is never released and never touched — applied,
     * unapplied or refunded. A late "payment.failed" delivery would otherwise hand
     * a cleared instalment back to the renter as unpaid while the {@code CRT}
     * stayed in the ledger, or flip an unapplied capture to FAILED and drop a
     * refund that is still owed off finance's list.</p>
     *
     * @return null when released, else why the report was ignored.
     */
    private String release(OnlinePayment onlinePayment, String reason) {
        if (MONEY_CAPTURED.contains(onlinePayment.getStatus())) {
            log.warn("Ignoring a failure report for online payment {}: it was already {}",
                    onlinePayment.getId(), onlinePayment.getStatus());
            return "ignored: payment already captured (" + onlinePayment.getStatus() + ")";
        }
        Cheque cheque = onlinePayment.getCheque();
        if (cheque != null && cheque.getStatus() == ChequeStatus.ONLINE_PENDING) {
            chequeService.revertOnlinePending(cheque.getId());
        }
        onlinePayment.setStatus(OnlinePaymentStatus.FAILED);
        onlinePayment.setFailureReason(reason);
        onlinePayment.setUpdatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);
        return null;
    }

    /** The reason markUnapplied recorded, without its prefix, for a repeated report. */
    private static String recordedUnappliedReason(OnlinePayment onlinePayment) {
        String recorded = onlinePayment.getFailureReason();
        if (recorded == null || recorded.isBlank()) {
            return "already recorded as " + onlinePayment.getStatus();
        }
        return recorded.startsWith(UNAPPLIED_PREFIX) ? recorded.substring(UNAPPLIED_PREFIX.length()) : recorded;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private OnlinePayment lockPayment(UUID onlinePaymentId) {
        return onlinePaymentRepository.findByIdForUpdate(onlinePaymentId)
                .orElseThrow(() -> new NotFoundException("Online payment not found: " + onlinePaymentId));
    }

    private TenantGatewayConfig activeConfig() {
        return activeConfig(null);
    }

    /**
     * The tenant's active gateway configuration, resolved the same way every time.
     *
     * <p>{@code preferred} is the gateway the order was actually created under
     * ({@code OnlinePayment.gateway}), so a capture settles into <em>that</em>
     * provider's account rather than into whichever active config the database
     * happened to list first. The config <em>id</em> is not recorded on the payment
     * — there is no column for it — so two active configs for the same gateway
     * still resolve by order; that is why the query is ordered.</p>
     */
    private TenantGatewayConfig activeConfig(PaymentGateway preferred) {
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrueOrderByCreatedAtAscIdAsc();
        if (configs.isEmpty()) {
            throw new BusinessRuleViolationException("No active payment gateway configured");
        }
        if (preferred != null) {
            for (TenantGatewayConfig config : configs) {
                if (config.getGateway() != null && preferred.getId().equals(config.getGateway().getId())) {
                    return config;
                }
            }
        }
        return configs.get(0);
    }

    /**
     * Where the gateway's payout lands. Null when the organisation has not
     * nominated one, in which case the capture falls back to the property's BANK
     * role exactly as a counter receipt does.
     */
    private UUID settlementAccountId(OnlinePayment onlinePayment) {
        Account settlement = activeConfig(onlinePayment.getGateway()).getSettlementAccount();
        return settlement != null ? settlement.getId() : null;
    }

    /**
     * The currency an order is raised in. AED or nothing.
     *
     * <p>The register keeps its money in AED and a capture posts the <em>row's</em>
     * AED amount, so a gateway that cannot take AED cannot be used to collect a
     * rent instalment. The old fallback raised the order for the same number in
     * rupees: a 12,000 AED instalment charged as ₹12,000 — about a seventh of the
     * money — and then booked as settled in full. A refusal at order time is a
     * configuration problem somebody fixes; the fallback was a reconciliation
     * nobody would find.</p>
     */
    private static String currencyOf(TenantGatewayConfig config) {
        String supported = config.getGateway().getSupportedCurrencies();
        if (supported == null || !supported.contains("AED")) {
            throw new BusinessRuleViolationException(
                    "The configured payment gateway (" + config.getGateway().getCode()
                            + ") does not support AED, so this instalment cannot be collected through it.");
        }
        return "AED";
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
        // Not `getAuthentication() != null`: the anonymous filter makes that true
        // for the signature-verified webhook too. See hasAuthenticatedCaller.
        if (leaseAccessPolicy.hasAuthenticatedCaller() && !leaseAccessPolicy.canManage(lease)) {
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
