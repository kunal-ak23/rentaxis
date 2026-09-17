package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterPaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.OnlinePaymentPayload;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlinePaymentService {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final OnlinePaymentRepository onlinePaymentRepository;
    private final TenantGatewayConfigRepository tenantGatewayConfigRepository;
    private final PaymentGatewayRepository paymentGatewayRepository;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final PaymentGatewayFactory paymentGatewayFactory;
    private final EncryptionService encryptionService;
    private final PenaltyCalculationService penaltyCalculationService;
    private final LeaseRepository leaseRepository;
    private final RenterRepository renterRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<RenterPaymentScheduleDTO> getMyPayments(UUID userId) {
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No renter profile linked to this user"));

        List<Lease> leases = leaseRepository.findByRenterId(renter.getId());
        List<RenterPaymentScheduleDTO> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Lease lease : leases) {
            List<PaymentSchedule> schedules = paymentScheduleRepository.findByLeaseId(lease.getId());
            RentCollectionSettings settings = rentCollectionSettingsRepository
                    .findByPropertyId(lease.getUnit().getProperty().getId())
                    .orElse(null);

            int gracePeriodDays = (settings != null && settings.getGracePeriodDays() != null)
                    ? settings.getGracePeriodDays() : 0;

            for (PaymentSchedule schedule : schedules) {
                if (schedule.getStatus() == PaymentStatus.REPLACED) {
                    continue;
                }

                RenterPaymentScheduleDTO dto = new RenterPaymentScheduleDTO();
                dto.setId(schedule.getId());
                dto.setInstallmentNumber(schedule.getInstallmentNumber());
                dto.setDueDate(schedule.getDueDate().toString());
                dto.setAmount(schedule.getAmount());
                dto.setStatus(schedule.getStatus().name());
                dto.setPropertyName(schedule.getProperty().getNameEn());
                dto.setUnitIdentifier(schedule.getUnit().getUnitNumber());
                dto.setRenterName(renter.getNameEn());
                dto.setLeaseId(lease.getId());
                dto.setPaymentMethod(schedule.getPaymentMethod());
                dto.setChequeNumber(schedule.getChequeNumber());
                dto.setBankName(schedule.getBankName());
                dto.setPurposeLabel(schedule.getPurposeLabel());
                dto.setIsBookingDeposit(schedule.isBookingDeposit());
                dto.setIsSecurityDeposit(schedule.isSecurityDeposit());
                dto.setIsCharge(schedule.isCharge());
                dto.setGracePeriodDays(gracePeriodDays);

                BigDecimal penalty = BigDecimal.ZERO;
                int daysOverdue = 0;

                if (schedule.getStatus() == PaymentStatus.PENDING || schedule.getStatus() == PaymentStatus.ONLINE_PENDING) {
                    penalty = penaltyCalculationService.calculatePenalty(schedule, settings, today);
                    daysOverdue = penaltyCalculationService.calculateDaysOverdue(schedule, gracePeriodDays, today);
                }

                dto.setPenaltyAmount(penalty);
                dto.setDaysOverdue(daysOverdue);
                dto.setTotalPayable(schedule.getAmount().add(penalty));
                dto.setStatusChangedAt(schedule.getStatusChangedAt() != null
                        ? schedule.getStatusChangedAt().toString()
                        : null);
                dto.setFailureReason(schedule.getFailureReason() != null
                        ? schedule.getFailureReason().name()
                        : null);

                result.add(dto);
            }
        }

        // Stable order across requests: the DTOs are assembled by iterating
        // leases and then each lease's schedules (findByLeaseId has no ORDER BY),
        // so without an explicit sort the renter's list can reorder between
        // refreshes. dueDate is stored ISO (yyyy-MM-dd) so lexical == chronological;
        // id is the unique tiebreaker for installments sharing a due date.
        result.sort(Comparator.comparing(RenterPaymentScheduleDTO::getDueDate)
                .thenComparing(RenterPaymentScheduleDTO::getId));

        return result;
    }

    @Transactional
    public CreateOrderResponseDTO createOrder(UUID paymentScheduleId) {
        PaymentSchedule schedule = paymentScheduleRepository.findById(paymentScheduleId)
                .orElseThrow(() -> new RuntimeException("Payment schedule not found"));

        if (schedule.getStatus() != PaymentStatus.PENDING) {
            throw new RuntimeException("Payment is not in PENDING status");
        }

        // Get active gateway config for current tenant
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrue();
        if (configs.isEmpty()) {
            throw new RuntimeException("No active payment gateway configured");
        }
        TenantGatewayConfig config = configs.get(0);

        // Decrypt API keys
        String apiKey = encryptionService.decrypt(config.getApiKeyEncrypted());
        String apiSecret = encryptionService.decrypt(config.getApiSecretEncrypted());

        // Calculate penalty
        RentCollectionSettings settings = rentCollectionSettingsRepository
                .findByPropertyId(schedule.getProperty().getId())
                .orElse(null);
        BigDecimal penalty = penaltyCalculationService.calculatePenalty(schedule, settings, LocalDate.now());
        BigDecimal totalAmount = schedule.getAmount().add(penalty);

        // Determine currency (default AED for UAE)
        String currency = "INR";
        String supportedCurrencies = config.getGateway().getSupportedCurrencies();
        if (supportedCurrencies != null && supportedCurrencies.contains("AED")) {
            currency = "AED";
        }

        // Create order via gateway provider
        String gatewayCode = config.getGateway().getCode();
        PaymentGatewayProvider provider = paymentGatewayFactory.getProvider(gatewayCode);
        String receiptId = "PS-" + schedule.getId().toString().substring(0, 8);

        CreateOrderResponseDTO response = provider.createOrder(totalAmount, currency, receiptId, apiKey, apiSecret);

        // Save OnlinePayment record
        OnlinePayment onlinePayment = new OnlinePayment();
        onlinePayment.setPaymentSchedule(schedule);
        onlinePayment.setGateway(config.getGateway());
        onlinePayment.setGatewayOrderId(response.getOrderId());
        onlinePayment.setAmount(totalAmount);
        onlinePayment.setCurrency(currency);
        onlinePayment.setStatus(OnlinePaymentStatus.CREATED);
        onlinePayment.setPenaltyAmount(penalty);
        onlinePayment.setCreatedAt(Instant.now());
        onlinePayment.setUpdatedAt(Instant.now());
        onlinePaymentRepository.save(onlinePayment);

        // Update payment schedule to ONLINE_PENDING
        schedule.setStatus(PaymentStatus.ONLINE_PENDING);
        schedule.setStatusChangedAt(Instant.now());
        paymentScheduleRepository.save(schedule);

        // Enrich response with renter info and SDK URL
        Renter renter = schedule.getLease().getRenter();
        response.setRenterName(renter.getNameEn());
        response.setRenterEmail(renter.getEmail());
        response.setSdkJsUrl(config.getGateway().getSdkJsUrl());

        return response;
    }

    @Transactional
    public VerifyPaymentResponseDTO verifyPayment(VerifyPaymentRequestDTO request) {
        OnlinePayment onlinePayment = onlinePaymentRepository.findByGatewayOrderId(request.getGatewayOrderId())
                .orElseThrow(() -> new RuntimeException("Online payment not found for order: " + request.getGatewayOrderId()));

        // Get gateway config
        List<TenantGatewayConfig> configs = tenantGatewayConfigRepository.findByIsActiveTrue();
        if (configs.isEmpty()) {
            throw new RuntimeException("No active payment gateway configured");
        }
        TenantGatewayConfig config = configs.get(0);
        String apiSecret = encryptionService.decrypt(config.getApiSecretEncrypted());

        // Verify signature
        String gatewayCode = config.getGateway().getCode();
        PaymentGatewayProvider provider = paymentGatewayFactory.getProvider(gatewayCode);
        boolean isValid = provider.verifyPaymentSignature(
                request.getGatewayOrderId(),
                request.getGatewayPaymentId(),
                request.getGatewaySignature(),
                apiSecret
        );

        VerifyPaymentResponseDTO response = new VerifyPaymentResponseDTO();

        if (isValid) {
            // Update OnlinePayment to CAPTURED
            onlinePayment.setStatus(OnlinePaymentStatus.CAPTURED);
            onlinePayment.setGatewayPaymentId(request.getGatewayPaymentId());
            onlinePayment.setGatewaySignature(request.getGatewaySignature());
            onlinePayment.setUpdatedAt(Instant.now());
            onlinePaymentRepository.save(onlinePayment);

            // Clear payment - same pattern as PaymentScheduleService.clearPayment()
            clearPaymentOnline(onlinePayment.getPaymentSchedule().getId());

            response.setSuccess(true);
            response.setMessage("Payment verified and recorded successfully");
            response.setPaymentId(onlinePayment.getGatewayPaymentId());

            // Structured email event: ONLINE_PAYMENT_RECEIVED
            {
                PaymentSchedule schedule = onlinePayment.getPaymentSchedule();
                events.publishEvent(new EmailEvent(this,
                        EmailEventType.ONLINE_PAYMENT_RECEIVED,
                        schedule.getTenantId(),
                        new OnlinePaymentPayload(
                                onlinePayment.getId(),
                                schedule.getLease().getId(),
                                schedule.getLease().getRenter().getUserId(),
                                null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                                onlinePayment.getAmount() != null ? onlinePayment.getAmount().toPlainString() + " " + onlinePayment.getCurrency() : null,
                                onlinePayment.getGatewayPaymentId(),
                                null   // failureReason — payment succeeded
                        ),
                        "ONLINE_PAYMENT_RECEIVED:" + onlinePayment.getId()));
            }

            // Notify renter in-app: payment success (ONLINE_PAYMENT_RECEIVED EmailEvent covers email)
            try {
                PaymentSchedule schedule = onlinePayment.getPaymentSchedule();
                UUID renterUserId = schedule.getLease().getRenter().getUserId();
                if (renterUserId != null) {
                    notificationService.notifyInApp(schedule.getTenantId(), renterUserId,
                            "PAYMENT_CLEARED", "Online Payment Successful",
                            "Installment #" + schedule.getInstallmentNumber() + " of " + schedule.getAmount() + " paid online successfully. Receipt available.",
                            "PAYMENT", schedule.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to send online payment success notification: {}", e.getMessage());
            }
        } else {
            // Update OnlinePayment to FAILED
            onlinePayment.setStatus(OnlinePaymentStatus.FAILED);
            onlinePayment.setFailureReason("Signature verification failed");
            onlinePayment.setUpdatedAt(Instant.now());
            onlinePaymentRepository.save(onlinePayment);

            // Revert PaymentSchedule to PENDING
            PaymentSchedule schedule = onlinePayment.getPaymentSchedule();
            schedule.setStatus(PaymentStatus.PENDING);
            schedule.setStatusChangedAt(Instant.now());
            paymentScheduleRepository.save(schedule);

            response.setSuccess(false);
            response.setMessage("Payment verification failed");

            // Notify renter: payment failed
            try {
                UUID renterUserId = schedule.getLease().getRenter().getUserId();
                if (renterUserId != null) {
                    notificationService.notify(schedule.getTenantId(), renterUserId,
                            "PAYMENT_FAILED", "Online Payment Failed",
                            "Installment #" + schedule.getInstallmentNumber() + " payment could not be verified. Please try again.",
                            "PAYMENT", schedule.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to send online payment failed notification: {}", e.getMessage());
            }
        }

        return response;
    }

    @Transactional
    public void cancelPendingOnlinePayment(UUID paymentScheduleId) {
        PaymentSchedule schedule = paymentScheduleRepository.findById(paymentScheduleId)
                .orElseThrow(() -> new RuntimeException("Payment schedule not found"));

        if (schedule.getStatus() == PaymentStatus.ONLINE_PENDING) {
            schedule.setStatus(PaymentStatus.PENDING);
            schedule.setStatusChangedAt(Instant.now());
            paymentScheduleRepository.save(schedule);

            // Also mark any CREATED online payments as FAILED
            List<OnlinePayment> onlinePayments = onlinePaymentRepository.findByPaymentScheduleId(paymentScheduleId);
            for (OnlinePayment op : onlinePayments) {
                if (op.getStatus() == OnlinePaymentStatus.CREATED) {
                    op.setStatus(OnlinePaymentStatus.FAILED);
                    op.setFailureReason("User cancelled checkout");
                    op.setUpdatedAt(Instant.now());
                    onlinePaymentRepository.save(op);
                }
            }
        }
    }

    /**
     * Clears the schedule for a captured online payment, invoked from
     * {@code WebhookService.processRazorpayWebhook}.
     *
     * <p>Runs in a {@code REQUIRES_NEW} transaction, deliberately NOT joining
     * the webhook handler's transaction. {@link #clearPaymentOnline} can throw
     * on a NOWAIT lock conflict or an unexpected schedule status; if it ran in
     * the caller's transaction, that throw would mark the shared transaction
     * rollback-only, which then (a) discards the {@code WebhookLog} audit row
     * the handler writes in its {@code finally} — losing the record of exactly
     * the anomaly {@code clearPaymentOnline} is trying to surface — and
     * (b) turns the handler's clean catch-and-continue into an
     * {@code UnexpectedRollbackException} at commit. A separate transaction
     * confines a clearing failure to its own rollback, leaving the handler's
     * transaction (and its audit write) intact.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearPaymentFromWebhook(PaymentSchedule payment) {
        clearPaymentOnline(payment.getId());
    }

    /**
     * Locks the schedule row and posts the "online payment cleared"
     * financial transactions. Previously this had no status precondition at
     * all and mutated whatever entity the caller happened to pass in —
     * unlike the cheque-clearing path, which requires DEPOSITED under a
     * pessimistic lock. That gap meant a redelivered/duplicate Razorpay
     * webhook (webhook providers commonly retry) could re-enter this method
     * on an already-CLEARED (or, in a cross-path scenario, already-BOUNCED)
     * schedule and post a second conflicting financial transaction.
     *
     * <p>Re-fetches by id under {@code findByIdForUpdate} rather than
     * trusting the caller's (possibly stale, definitely unlocked) entity.
     * An already-CLEARED row is treated as an idempotent no-op — that's the
     * expected shape of a duplicate webhook delivery, not an error. Any
     * other unexpected status is rejected loudly so the anomaly surfaces in
     * {@code WebhookLog} rather than silently corrupting the ledger.
     */
    private void clearPaymentOnline(UUID paymentScheduleId) {
        PaymentSchedule payment;
        try {
            payment = paymentScheduleRepository.findByIdForUpdate(paymentScheduleId)
                    .orElseThrow(() -> new RuntimeException("Payment schedule not found: " + paymentScheduleId));
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(
                    "This payment is currently being updated by another request. Please try again.");
        }

        if (payment.getStatus() == PaymentStatus.CLEARED) {
            log.info("clearPaymentOnline: schedule {} already CLEARED, ignoring duplicate call", paymentScheduleId);
            return;
        }
        if (payment.getStatus() != PaymentStatus.ONLINE_PENDING) {
            throw new BusinessRuleViolationException(
                    "Can only clear online payments in ONLINE_PENDING status (current: " + payment.getStatus() + ")");
        }

        // Set schedule status to CLEARED, paymentMethod to "ONLINE"
        payment.setStatus(PaymentStatus.CLEARED);
        payment.setPaymentMethod("ONLINE");
        payment.setStatusChangedAt(Instant.now());
        paymentScheduleRepository.save(payment);

        // Ledger posting moves to PostingService in accounting v2 plan 2/3 (see spec §7/§9).
    }
}
