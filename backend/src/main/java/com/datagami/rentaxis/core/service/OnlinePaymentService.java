package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterPaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayFactory;
import com.datagami.rentaxis.core.service.gateway.PaymentGatewayProvider;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private final AccountRepository accountRepository;
    private final FinancialTransactionService financialTransactionService;
    private final LeaseRepository leaseRepository;
    private final RenterRepository renterRepository;

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

                result.add(dto);
            }
        }

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
            clearPaymentOnline(onlinePayment.getPaymentSchedule());

            response.setSuccess(true);
            response.setMessage("Payment verified and recorded successfully");
            response.setPaymentId(onlinePayment.getGatewayPaymentId());
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
        }

        return response;
    }

    public void clearPaymentFromWebhook(PaymentSchedule payment) {
        clearPaymentOnline(payment);
    }

    private void clearPaymentOnline(PaymentSchedule payment) {
        // Set schedule status to CLEARED, paymentMethod to "ONLINE"
        payment.setStatus(PaymentStatus.CLEARED);
        payment.setPaymentMethod("ONLINE");
        payment.setStatusChangedAt(Instant.now());
        paymentScheduleRepository.save(payment);

        // Auto-create financial transactions (same pattern as PaymentScheduleService.clearPayment())
        UUID tenantId = TenantContextHolder.getTenantId();

        // Debit: Bank/Cash account (A-01-01)
        Account bankAccount = accountRepository.findByCodeAndTenantId("A-01-01", tenantId)
                .orElseThrow(() -> new RuntimeException("Bank/Cash account (A-01-01) not found"));

        FinancialTransaction debitTxn = new FinancialTransaction();
        debitTxn.setDate(LocalDate.now());
        debitTxn.setDescription("Online payment cleared - Lease installment #" + payment.getInstallmentNumber());
        debitTxn.setAccount(bankAccount);
        debitTxn.setDebit(payment.getAmount());
        debitTxn.setCredit(BigDecimal.ZERO);
        debitTxn.setProperty(payment.getProperty());
        debitTxn.setUnit(payment.getUnit());
        financialTransactionService.createTransaction(debitTxn);

        // Credit: Rental Income account (C-01-01)
        Account rentalIncomeAccount = accountRepository.findByCodeAndTenantId("C-01-01", tenantId)
                .orElseThrow(() -> new RuntimeException("Rental Income account (C-01-01) not found"));

        FinancialTransaction creditTxn = new FinancialTransaction();
        creditTxn.setDate(LocalDate.now());
        creditTxn.setDescription("Online rental income - Lease installment #" + payment.getInstallmentNumber());
        creditTxn.setAccount(rentalIncomeAccount);
        creditTxn.setDebit(BigDecimal.ZERO);
        creditTxn.setCredit(payment.getAmount());
        creditTxn.setProperty(payment.getProperty());
        creditTxn.setUnit(payment.getUnit());
        financialTransactionService.createTransaction(creditTxn);
    }
}
