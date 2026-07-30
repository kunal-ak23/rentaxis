# Cheque Deposit Reminder + Fix Broken Cheque/Payment Emails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Renters get an email 3 days (configurable) before their post-dated cheque's `chequeDate`, and along the way fix three existing cheque/payment email types (`PAYMENT_DUE_REMINDER`, `CHEQUE_CLEARED`, `CHEQUE_BOUNCED`) that today silently never send an email.

**Architecture:** All four notification paths converge on the same fix: publish a structured `EmailEvent` carrying a typed payload (`ChequePayload` or the new `PaymentReminderPayload`) directly via `ApplicationEventPublisher`, instead of routing through `NotificationService.notify()`'s legacy path (which can't resolve `RENTER`/`PROPERTY_MANAGER` recipients). This mirrors the already-correct `CHEQUE_RECEIVED`/`CHEQUE_DEPOSITED` wiring in `PaymentScheduleService`.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Liquibase, Thymeleaf email templates, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-11-cheque-deposit-reminder-design.md`

---

## File Structure

**Modify:**
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/RentCollectionSettings.java` — add `chequeDepositReminderDays`
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — include new changeset
- `backend/src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java` — add `CHEQUE_DEPOSIT_REMINDER`
- `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java` — wire the new type
- `backend/src/main/resources/messages/email_en.properties`, `email_ar.properties` — new i18n keys
- `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java` — fix `clearPayment`, `markFailed`
- `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationScheduler.java` — fix `checkPaymentDueReminders`, add `checkChequeDepositReminders`
- `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java` — add regression cases
- `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceMarkFailedTest.java` — fix stale assertion

**Create:**
- `backend/src/main/resources/db/changelog/changesets/64-cheque-deposit-reminder-days.yaml`
- `backend/src/main/resources/templates/email/events/cheque_deposit_reminder.html`
- `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/PaymentReminderPayload.java`
- `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeEmailEventsTest.java`
- `backend/src/test/java/com/datagami/rentaxis/core/service/NotificationSchedulerTest.java`

---

### Task 1: Configurable cheque-deposit-reminder-days setting

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/RentCollectionSettings.java`
- Create: `backend/src/main/resources/db/changelog/changesets/64-cheque-deposit-reminder-days.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Create the Liquibase changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 64-cheque-deposit-reminder-days
      author: rentaxis-system
      comment: "Configurable per-tenant lead time (days) for the renter cheque-deposit reminder email"
      changes:
        - addColumn:
            tableName: rent_collection_settings
            columns:
              - column:
                  name: cheque_deposit_reminder_days
                  type: integer
                  remarks: "Days before chequeDate to remind renter before deposit; null = default 3"
```

- [ ] **Step 2: Register the changeset in the master changelog**

In `backend/src/main/resources/db/changelog/db.changelog-master.yaml`, after the `63-payment-schedule-vat-amount.yaml` include, add:

```yaml
  - include:
      file: db/changelog/changesets/64-cheque-deposit-reminder-days.yaml
```

- [ ] **Step 3: Add the field to the entity**

In `RentCollectionSettings.java`, after the `paymentReminderDays` field (currently ending at line 58), add:

```java
    @Column(name = "cheque_deposit_reminder_days")
    private Integer chequeDepositReminderDays;
```

- [ ] **Step 4: Build to confirm compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

No dedicated unit test for this step — it's a plain persisted field with no logic of its own. It's exercised by Task 7's `NotificationSchedulerTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/RentCollectionSettings.java \
        backend/src/main/resources/db/changelog/changesets/64-cheque-deposit-reminder-days.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(payments): add configurable cheque deposit reminder days setting"
```

---

### Task 2: `CHEQUE_DEPOSIT_REMINDER` email event type + template

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java`
- Modify: `backend/src/main/resources/messages/email_en.properties`
- Modify: `backend/src/main/resources/messages/email_ar.properties`
- Create: `backend/src/main/resources/templates/email/events/cheque_deposit_reminder.html`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java`

- [ ] **Step 1: Write the failing test**

In `RecipientResolverTest.java`, add the import and a new test method:

```java
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
```

```java
    @Test
    void resolvesChequeDepositReminderToRenterOnly() {
        UUID renterUserId = UUID.randomUUID();
        User renterUser = user(renterUserId, "renter@x", "Sara");

        when(userRepo.findById(renterUserId)).thenReturn(Optional.of(renterUser));
        when(renterRepo.findByUserId(renterUserId)).thenReturn(Optional.empty());

        ChequePayload payload = new ChequePayload(
                UUID.randomUUID(), UUID.randomUUID(), renterUserId, null,
                1, "CHQ-1", "Bank", "5,000 AED", "2026-07-20", null, null);

        List<ResolvedRecipient> recipients = resolver.resolve(EmailEventType.CHEQUE_DEPOSIT_REMINDER, payload);

        assertThat(recipients).hasSize(1);
        assertThat(recipients.get(0).userId()).isEqualTo(renterUserId);
    }
```

- [ ] **Step 2: Run test to verify it fails to compile**

Run: `cd backend && ./gradlew test --tests RecipientResolverTest`
Expected: COMPILATION FAILED — `cannot find symbol: CHEQUE_DEPOSIT_REMINDER`

- [ ] **Step 3: Add the enum value**

In `EmailEventType.java`, insert after the `CHEQUE_BOUNCED` line (currently line 41), before `PAYMENT_DUE_REMINDER`:

```java
    CHEQUE_DEPOSIT_REMINDER (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
```

- [ ] **Step 4: Wire it into `PayloadVarsExtractor`**

In `computeCtaUrl`, change the existing cheque/payment case group (currently lines 64-67) to include the new type:

```java
            case CHEQUE_RECEIVED, CHEQUE_DEPOSITED, CHEQUE_CLEARED, CHEQUE_BOUNCED, CHEQUE_DEPOSIT_REMINDER,
                 PAYMENT_DUE_REMINDER, PAYMENT_OVERDUE, ONLINE_PAYMENT_RECEIVED, ONLINE_PAYMENT_FAILED,
                 PENALTY_INCURRED, PENALTY_CLEARED, PENALTY_WAIVED
                    -> prefix + "/finance/payments";
```

In `subjectArgsFor`, change the existing cheque case group (currently lines 87-88) to include the new type:

```java
            case CHEQUE_RECEIVED, CHEQUE_DEPOSITED, CHEQUE_CLEARED, CHEQUE_BOUNCED, CHEQUE_DEPOSIT_REMINDER
                    -> new Object[]{ vars.get("chequeNumber"), vars.get("amountDisplay"), vars.get("installmentNumber") };
```

- [ ] **Step 5: Add i18n message keys**

In `backend/src/main/resources/messages/email_en.properties`, after the `email.cheque_bounced.*` block (currently ending at line 137), add:

```properties
email.cheque_deposit_reminder.subject=Cheque deposit reminder
email.cheque_deposit_reminder.title=Your cheque will be deposited soon
email.cheque_deposit_reminder.greeting=Hi {0},
email.cheque_deposit_reminder.body=Cheque <b>{0}</b> for <b>{1}</b> (installment <b>{2}</b>) will be deposited soon. Please ensure sufficient funds are available to avoid a bounce.
email.cheque_deposit_reminder.cta=View payments
```

In `backend/src/main/resources/messages/email_ar.properties`, after the corresponding `email.cheque_bounced.*` block (currently ending at line 137), add:

```properties
email.cheque_deposit_reminder.subject=تذكير بإيداع الشيك
email.cheque_deposit_reminder.title=سيتم إيداع شيكك قريبا
email.cheque_deposit_reminder.greeting=مرحبا {0}،
email.cheque_deposit_reminder.body=سيتم إيداع الشيك <b>{0}</b> بقيمة <b>{1}</b> للقسط <b>{2}</b> قريبا. يرجى التأكد من توفر رصيد كافٍ لتجنب ارتجاع الشيك.
email.cheque_deposit_reminder.cta=عرض المدفوعات
```

- [ ] **Step 6: Create the template**

Create `backend/src/main/resources/templates/email/events/cheque_deposit_reminder.html`:

```html
<th:block xmlns:th="http://www.thymeleaf.org" th:replace="~{${layout} :: layout(~{::content})}"><th:block th:fragment="content"><h2 th:text="#{email.cheque_deposit_reminder.title}" style="margin:0 0 8px;color:#0F172A;font-size:18px;font-weight:600;"></h2><p th:text="#{email.cheque_deposit_reminder.greeting(${recipient.name})}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p><p th:utext="#{email.cheque_deposit_reminder.body(${chequeNumber}, ${amountDisplay}, ${installmentNumber})}" style="margin:0 0 16px;color:#475569;font-size:14px;line-height:1.6;"></p><table cellpadding="0" cellspacing="0" style="margin:16px 0;"><tr><td><a th:href="${ctaUrl}" th:text="#{email.cheque_deposit_reminder.cta}" style="display:inline-block;background:#0F766E;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600;"></a></td></tr></table></th:block></th:block>
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests RecipientResolverTest`
Expected: PASS (2 tests: the existing lease test + the new one)

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/EmailEventType.java \
        backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java \
        backend/src/main/resources/messages/email_en.properties \
        backend/src/main/resources/messages/email_ar.properties \
        backend/src/main/resources/templates/email/events/cheque_deposit_reminder.html \
        backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java
git commit -m "feat(payments): add CHEQUE_DEPOSIT_REMINDER email event type"
```

---

### Task 3: `PaymentReminderPayload` for the payment-due-reminder fix

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/PaymentReminderPayload.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java`

`ChequePayload` doesn't fit `PAYMENT_DUE_REMINDER`: its template (`payment_due_reminder.html`) and `subjectArgsFor` both need a `daysUntilDue` variable that `ChequePayload` doesn't have, and `PAYMENT_DUE_REMINDER` fires for any pending installment, not just cheques.

- [ ] **Step 1: Write the failing test**

Add the import and a new test method to `RecipientResolverTest.java`:

```java
import com.datagami.rentaxis.core.email.event.payload.PaymentReminderPayload;
```

```java
    @Test
    void resolvesPaymentDueReminderToRenterOnly() {
        UUID renterUserId = UUID.randomUUID();
        User renterUser = user(renterUserId, "renter@x", "Sara");

        when(userRepo.findById(renterUserId)).thenReturn(Optional.of(renterUser));
        when(renterRepo.findByUserId(renterUserId)).thenReturn(Optional.empty());

        PaymentReminderPayload payload = new PaymentReminderPayload(
                UUID.randomUUID(), renterUserId, "5,000 AED", "2026-07-20", 3);

        List<ResolvedRecipient> recipients = resolver.resolve(EmailEventType.PAYMENT_DUE_REMINDER, payload);

        assertThat(recipients).hasSize(1);
        assertThat(recipients.get(0).userId()).isEqualTo(renterUserId);
    }
```

- [ ] **Step 2: Run test to verify it fails to compile**

Run: `cd backend && ./gradlew test --tests RecipientResolverTest`
Expected: COMPILATION FAILED — `cannot find symbol: PaymentReminderPayload`

- [ ] **Step 3: Create the payload record**

Create `backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/PaymentReminderPayload.java`:

```java
package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PaymentReminderPayload(
        UUID paymentScheduleId,
        UUID renterUserId,
        String amountDisplay,
        String dueDateIso,
        int daysUntilDue
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests RecipientResolverTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/email/event/payload/PaymentReminderPayload.java \
        backend/src/test/java/com/datagami/rentaxis/core/email/dispatch/RecipientResolverTest.java
git commit -m "feat(payments): add PaymentReminderPayload for payment-due-reminder emails"
```

---

### Task 4: Fix `clearPayment` to actually send the `CHEQUE_CLEARED` email

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java:801-813`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeEmailEventsTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeEmailEventsTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the CHEQUE_CLEARED / CHEQUE_BOUNCED email delivery
 * bug: clearPayment/markFailed used to route through the legacy
 * NotificationService.notify() path, whose LegacyNotificationPayload cannot
 * resolve RENTER-role recipients (see RecipientResolver), so the email was
 * silently never sent. They must publish a structured EmailEvent carrying a
 * ChequePayload instead, matching applyChequeReceived/depositPayment.
 */
class PaymentScheduleServiceChequeEmailEventsTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private AccountRepository accountRepository;
    private FinancialTransactionService financialTransactionService;
    private AccountMappingService accountMappingService;
    private NotificationService notificationService;
    private FineConfigResolver fineConfigResolver;
    private PaymentPenaltyRepository paymentPenaltyRepository;
    private ApplicationEventPublisher events;
    private PaymentScheduleService service;

    private UUID tenantId;
    private UUID propertyId;
    private UUID leaseId;
    private UUID renterUserId;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        accountRepository = mock(AccountRepository.class);
        financialTransactionService = mock(FinancialTransactionService.class);
        accountMappingService = mock(AccountMappingService.class);
        notificationService = mock(NotificationService.class);
        fineConfigResolver = mock(FineConfigResolver.class);
        paymentPenaltyRepository = mock(PaymentPenaltyRepository.class);
        events = mock(ApplicationEventPublisher.class);

        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(com.datagami.rentaxis.domain.repository.LeaseChargeRepository.class),
                mock(com.datagami.rentaxis.domain.repository.LeaseRepository.class),
                accountRepository,
                financialTransactionService,
                accountMappingService,
                mock(RentCollectionSettingsRepository.class),
                notificationService,
                fineConfigResolver,
                paymentPenaltyRepository,
                mock(LeaseEventRepository.class),
                events,
                new com.fasterxml.jackson.databind.ObjectMapper());

        tenantId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        leaseId = UUID.randomUUID();
        renterUserId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void clearPayment_publishesChequeClearedEmailEventWithChequePayload() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        payment.setChequeNumber("CHQ-100");
        payment.setBankName("Emirates NBD");
        payment.setDueDate(LocalDate.of(2026, 7, 20));
        stubAccountMapping();
        when(paymentScheduleRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.clearPayment(payment.getId(), new UpdatePaymentStatusDTO());

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publishEvent(captor.capture());
        EmailEvent published = captor.getValue();
        assertThat(published.getType()).isEqualTo(EmailEventType.CHEQUE_CLEARED);
        assertThat(published.getPayload()).isInstanceOf(ChequePayload.class);
        ChequePayload payload = (ChequePayload) published.getPayload();
        assertThat(payload.renterUserId()).isEqualTo(renterUserId);
        assertThat(payload.chequeNumber()).isEqualTo("CHQ-100");
        assertThat(payload.bankName()).isEqualTo("Emirates NBD");
        assertThat(payload.amountDisplay()).isEqualTo("5000 AED");
    }

    private PaymentSchedule depositedPayment(BigDecimal amount) {
        Lease lease = new Lease();
        lease.setId(leaseId);
        lease.setStatus(LeaseStatus.ACTIVE);
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        lease.setRenter(renter);

        Property property = new Property();
        property.setId(propertyId);
        Unit unit = new Unit();

        PaymentSchedule payment = new PaymentSchedule();
        payment.setId(UUID.randomUUID());
        payment.setLease(lease);
        payment.setProperty(property);
        payment.setUnit(unit);
        payment.setAmount(amount);
        payment.setInstallmentNumber(1);
        payment.setStatus(PaymentStatus.DEPOSITED);
        return payment;
    }

    private void stubAccountMapping() {
        AccountMapping mapping = new AccountMapping();
        Account bank = new Account();
        Account income = new Account();
        mapping.setDebitAccount(bank);
        mapping.setCreditAccount(income);
        when(accountMappingService.resolveMapping(TransactionNature.RENT_PAYMENT_CLEARED))
                .thenReturn(mapping);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceChequeEmailEventsTest`
Expected: FAIL — `Wanted but not invoked: events.publishEvent(...)`

- [ ] **Step 3: Fix `clearPayment`**

In `PaymentScheduleService.java`, replace the "Notify renter that payment has been cleared" block (currently lines 801-813):

```java
        // Notify renter that payment has been cleared
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(TenantContextHolder.getTenantId(),
                        renterUserId,
                        "PAYMENT_CLEARED", "Payment Cleared",
                        "Installment #" + payment.getInstallmentNumber() + " has been cleared. Receipt available.",
                        "PAYMENT", payment.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send payment cleared notification for payment {}: {}", payment.getId(), e.getMessage());
        }
```

with:

```java
        // Notify renter that payment has been cleared. Publishes a structured
        // EmailEvent with ChequePayload directly (not notificationService.notify()'s
        // legacy path — LegacyNotificationPayload cannot resolve RENTER-role
        // recipients, so that path silently drops the email; see ChequePayload
        // usage in applyChequeReceived/depositPayment above).
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notifyInAppInNewTx(TenantContextHolder.getTenantId(), renterUserId,
                        "PAYMENT_CLEARED", "Payment Cleared",
                        "Installment #" + payment.getInstallmentNumber() + " has been cleared. Receipt available.",
                        "PAYMENT", payment.getId());
                events.publishEvent(new EmailEvent(this,
                        EmailEventType.CHEQUE_CLEARED,
                        payment.getTenantId(),
                        new ChequePayload(
                                payment.getId(),
                                payment.getLease().getId(),
                                renterUserId,
                                null,
                                payment.getInstallmentNumber(),
                                payment.getChequeNumber(),
                                payment.getBankName(),
                                payment.getAmount() != null ? payment.getAmount().toPlainString() + " AED" : null,
                                payment.getDueDate() != null ? payment.getDueDate().toString() : null,
                                payment.getStatusChangedAt() != null
                                        ? payment.getStatusChangedAt().atZone(UAE_ZONE).toLocalDate().toString()
                                        : null,
                                null),
                        "CHEQUE_CLEARED:" + payment.getId()));
            }
        } catch (Exception e) {
            log.warn("Failed to send payment cleared notification for payment {}: {}", payment.getId(), e.getMessage());
        }
```

(`UAE_ZONE` is the existing `private static final ZoneId UAE_ZONE = ZoneId.of("Asia/Dubai");` constant already declared in this class.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceChequeEmailEventsTest`
Expected: PASS

- [ ] **Step 5: Run the existing VAT test suite to confirm no regression**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceVatTest`
Expected: PASS (5 tests) — these only assert on `FinancialTransaction` fields, unaffected by the notification change.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeEmailEventsTest.java
git commit -m "fix(payments): send CHEQUE_CLEARED email via structured ChequePayload event"
```

---

### Task 5: Fix `markFailed` to actually send the `CHEQUE_BOUNCED` email

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java:886-899`
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeEmailEventsTest.java`
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceMarkFailedTest.java`

- [ ] **Step 1: Write the failing test**

Add to `PaymentScheduleServiceChequeEmailEventsTest.java` (needs `ChequeFailureReason`, `FineConfig` — already imported/same package):

```java
    @Test
    void markFailed_publishesChequeBouncedEmailEventWithChequePayload() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        payment.setChequeNumber("CHQ-200");
        payment.setBankName("ADCB");
        payment.setDueDate(LocalDate.of(2026, 7, 20));
        when(paymentScheduleRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentPenaltyRepository.save(any())).thenAnswer(inv -> {
            var p = (com.datagami.rentaxis.domain.entity.PaymentPenalty) inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(fineConfigResolver.resolve(propertyId, tenantId)).thenReturn(new FineConfig(
                new BigDecimal("500"), new BigDecimal("750"), new BigDecimal("1000"),
                7, new BigDecimal("25"), FineConfig.Source.ORG));

        service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publishEvent(captor.capture());
        EmailEvent published = captor.getValue();
        assertThat(published.getType()).isEqualTo(EmailEventType.CHEQUE_BOUNCED);
        ChequePayload payload = (ChequePayload) published.getPayload();
        assertThat(payload.renterUserId()).isEqualTo(renterUserId);
        assertThat(payload.chequeNumber()).isEqualTo("CHQ-200");
        assertThat(payload.failureReason()).isEqualTo("BOUNCE");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceChequeEmailEventsTest`
Expected: FAIL — `Wanted but not invoked: events.publishEvent(...)` for the new test

- [ ] **Step 3: Fix `markFailed`**

In `PaymentScheduleService.java`, replace the "Renter notifications" block (currently lines 882-899):

```java
        // Renter notifications — both PAYMENT_BOUNCED (existing template) and
        // PENALTY_INCURRED (extracted to NotificationService.sendPenaltyIncurred
        // in M7). Wrapped so a downed mailer never blocks the status transition
        // (notifications are best-effort, not part of the audit-critical path).
        try {
            UUID renterUserId = saved.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(tenantId, renterUserId,
                        "PAYMENT_BOUNCED", "Cheque Failed",
                        "Installment #" + saved.getInstallmentNumber() + " cheque of " + saved.getAmount()
                                + " was marked " + reason + ". A fine of " + fineAmount
                                + " AED has been added. Please arrange a replacement and clear the fine.",
                        "PAYMENT", saved.getId());
                notificationService.sendPenaltyIncurred(saved, reason, fineAmount, savedPenalty.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send mark-failed notifications for payment {}: {}", saved.getId(), e.getMessage());
        }
```

with:

```java
        // Renter notifications — CHEQUE_BOUNCED (structured EmailEvent with
        // ChequePayload, not the legacy notify() path — see clearPayment above
        // for why) and PENALTY_INCURRED (extracted to
        // NotificationService.sendPenaltyIncurred in M7). Wrapped so a downed
        // mailer never blocks the status transition (notifications are
        // best-effort, not part of the audit-critical path).
        try {
            UUID renterUserId = saved.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notifyInAppInNewTx(tenantId, renterUserId,
                        "PAYMENT_BOUNCED", "Cheque Failed",
                        "Installment #" + saved.getInstallmentNumber() + " cheque of " + saved.getAmount()
                                + " was marked " + reason + ". A fine of " + fineAmount
                                + " AED has been added. Please arrange a replacement and clear the fine.",
                        "PAYMENT", saved.getId());
                events.publishEvent(new EmailEvent(this,
                        EmailEventType.CHEQUE_BOUNCED,
                        tenantId,
                        new ChequePayload(
                                saved.getId(),
                                saved.getLease().getId(),
                                renterUserId,
                                null,
                                saved.getInstallmentNumber(),
                                saved.getChequeNumber(),
                                saved.getBankName(),
                                saved.getAmount() != null ? saved.getAmount().toPlainString() + " AED" : null,
                                saved.getDueDate() != null ? saved.getDueDate().toString() : null,
                                null,
                                reason.name()),
                        "CHEQUE_BOUNCED:" + saved.getId()));
                notificationService.sendPenaltyIncurred(saved, reason, fineAmount, savedPenalty.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send mark-failed notifications for payment {}: {}", saved.getId(), e.getMessage());
        }
```

- [ ] **Step 4: Fix the now-stale assertion in `PaymentScheduleServiceMarkFailedTest`**

`markFailed_firesChequeBouncedNotification` (currently lines 250-278) asserts `notify(...)` was called with type `"PAYMENT_BOUNCED"`. That call is now `notifyInAppInNewTx(...)` (the in-app row — same type string, same body text, only the method name changed; the email itself is now covered by Task 5's new test). Update the `verify(...)` call:

```java
        verify(notificationService, times(1)).notifyInAppInNewTx(
                eq(tenantId), eq(renterUserId),
                typeCaptor.capture(),
                any(),
                bodyCaptor.capture(),
                any(),
                any());
```

(Only the method name in the `verify(...)` line changes — the captors, assertions below it, and the rest of the test file are unchanged.)

- [ ] **Step 5: Run both test files to verify everything passes**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceChequeEmailEventsTest --tests PaymentScheduleServiceMarkFailedTest`
Expected: PASS (all tests in both files)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeEmailEventsTest.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceMarkFailedTest.java
git commit -m "fix(payments): send CHEQUE_BOUNCED email via structured ChequePayload event"
```

---

### Task 6: Fix `checkPaymentDueReminders` to actually send the reminder email

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationScheduler.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/NotificationSchedulerTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/NotificationSchedulerTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.email.event.payload.PaymentReminderPayload;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSchedulerTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private LeaseRepository leaseRepository;
    private NotificationService notificationService;
    private RentCollectionSettingsRepository rentSettingsRepository;
    private ApplicationEventPublisher events;
    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        leaseRepository = mock(LeaseRepository.class);
        notificationService = mock(NotificationService.class);
        rentSettingsRepository = mock(RentCollectionSettingsRepository.class);
        events = mock(ApplicationEventPublisher.class);
        scheduler = new NotificationScheduler(
                paymentScheduleRepository, leaseRepository, notificationService,
                rentSettingsRepository, events);
        when(paymentScheduleRepository.findOverdue(any(LocalDate.class))).thenReturn(List.of());
        when(leaseRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void checkPaymentDueReminders_publishesPaymentDueReminderEmailEventForMatchingPending() {
        UUID renterUserId = UUID.randomUUID();
        PaymentSchedule payment = pendingPayment(renterUserId, LocalDate.now().plusDays(3), "3000");
        when(rentSettingsRepository.findAll()).thenReturn(List.of());
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(payment));

        scheduler.sendDailyNotifications();

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publishEvent(captor.capture());
        EmailEvent published = captor.getValue();
        assertThat(published.getType()).isEqualTo(EmailEventType.PAYMENT_DUE_REMINDER);
        assertThat(published.getPayload()).isInstanceOf(PaymentReminderPayload.class);
        PaymentReminderPayload payload = (PaymentReminderPayload) published.getPayload();
        assertThat(payload.renterUserId()).isEqualTo(renterUserId);
        assertThat(payload.daysUntilDue()).isEqualTo(3);
    }

    private PaymentSchedule pendingPayment(UUID renterUserId, LocalDate dueDate, String amount) {
        Lease lease = new Lease();
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        lease.setRenter(renter);

        PaymentSchedule payment = new PaymentSchedule();
        payment.setId(UUID.randomUUID());
        payment.setLease(lease);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setDueDate(dueDate);
        payment.setAmount(new BigDecimal(amount));
        payment.setInstallmentNumber(1);
        return payment;
    }
}
```

- [ ] **Step 2: Run test to verify it fails to compile**

Run: `cd backend && ./gradlew test --tests NotificationSchedulerTest`
Expected: COMPILATION FAILED — `constructor NotificationScheduler cannot be applied to given types` (5 args vs. current 4)

- [ ] **Step 3: Inject `ApplicationEventPublisher` and fix `checkPaymentDueReminders`**

In `NotificationScheduler.java`, add imports:

```java
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.email.event.payload.PaymentReminderPayload;
import org.springframework.context.ApplicationEventPublisher;
```

and (next to `import java.util.Set;`):

```java
import java.util.UUID;
```

Add the new field after `rentSettingsRepository` (currently line 30):

```java
    private final ApplicationEventPublisher events;
```

Replace the loop body inside `checkPaymentDueReminders()` (currently lines 79-96):

```java
            for (PaymentSchedule payment : payments) {
                try {
                    if (payment.getLease() != null && payment.getLease().getRenter() != null
                            && payment.getLease().getRenter().getUserId() != null) {
                        notificationService.notify(
                                payment.getTenantId(),
                                payment.getLease().getRenter().getUserId(),
                                "PAYMENT_DUE",
                                "Payment Due Reminder",
                                "Installment #" + payment.getInstallmentNumber() + " of "
                                        + payment.getAmount() + " is due in " + daysBefore + " day(s).",
                                "PAYMENT",
                                payment.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to send payment reminder for payment {}", payment.getId(), e);
                }
            }
```

with:

```java
            for (PaymentSchedule payment : payments) {
                try {
                    if (payment.getLease() != null && payment.getLease().getRenter() != null
                            && payment.getLease().getRenter().getUserId() != null) {
                        UUID renterUserId = payment.getLease().getRenter().getUserId();
                        notificationService.notifyInApp(
                                payment.getTenantId(),
                                renterUserId,
                                "PAYMENT_DUE",
                                "Payment Due Reminder",
                                "Installment #" + payment.getInstallmentNumber() + " of "
                                        + payment.getAmount() + " is due in " + daysBefore + " day(s).",
                                "PAYMENT",
                                payment.getId());
                        // Structured EmailEvent — notificationService.notify()'s legacy path
                        // can't resolve RENTER-role recipients (see ChequePayload usage in
                        // PaymentScheduleService for the same fix applied to cheque events).
                        events.publishEvent(new EmailEvent(this,
                                EmailEventType.PAYMENT_DUE_REMINDER,
                                payment.getTenantId(),
                                new PaymentReminderPayload(
                                        payment.getId(),
                                        renterUserId,
                                        payment.getAmount() != null ? payment.getAmount().toPlainString() + " AED" : null,
                                        payment.getDueDate() != null ? payment.getDueDate().toString() : null,
                                        daysBefore),
                                "PAYMENT_DUE_REMINDER:" + payment.getId() + ":" + daysBefore));
                    }
                } catch (Exception e) {
                    log.warn("Failed to send payment reminder for payment {}", payment.getId(), e);
                }
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests NotificationSchedulerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/NotificationScheduler.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/NotificationSchedulerTest.java
git commit -m "fix(payments): send PAYMENT_DUE_REMINDER email via structured payload event"
```

---

### Task 7: Add `checkChequeDepositReminders`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/NotificationScheduler.java`
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/NotificationSchedulerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `NotificationSchedulerTest.java` (needs the `RentCollectionSettings` import already present):

```java
    @Test
    void checkChequeDepositReminders_publishesChequeDepositReminderForPendingChequeDueInWindow() {
        UUID renterUserId = UUID.randomUUID();
        PaymentSchedule cheque = collectedCheque(renterUserId, LocalDate.now().plusDays(3));
        RentCollectionSettings settings = new RentCollectionSettings();
        settings.setChequeDepositReminderDays(3);
        when(rentSettingsRepository.findAll()).thenReturn(List.of(settings));
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(cheque));

        scheduler.sendDailyNotifications();

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publishEvent(captor.capture());
        EmailEvent published = captor.getValue();
        assertThat(published.getType()).isEqualTo(EmailEventType.CHEQUE_DEPOSIT_REMINDER);
        ChequePayload payload = (ChequePayload) published.getPayload();
        assertThat(payload.renterUserId()).isEqualTo(renterUserId);
        assertThat(payload.chequeNumber()).isEqualTo("CHQ-DEP-1");
    }

    @Test
    void checkChequeDepositReminders_skipsClearedCheque() {
        UUID renterUserId = UUID.randomUUID();
        PaymentSchedule cheque = collectedCheque(renterUserId, LocalDate.now().plusDays(3));
        cheque.setStatus(PaymentStatus.CLEARED);
        when(rentSettingsRepository.findAll()).thenReturn(List.of());
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(cheque));

        scheduler.sendDailyNotifications();

        verify(events, never()).publishEvent(any());
    }

    @Test
    void checkChequeDepositReminders_fallsBackToDefaultThreeDaysWhenNoSettingConfigured() {
        UUID renterUserId = UUID.randomUUID();
        PaymentSchedule cheque = collectedCheque(renterUserId, LocalDate.now().plusDays(3));
        // No RentCollectionSettings rows at all (empty tenant list) — the scheduler
        // must fall back to a default of 3 days rather than firing zero reminders.
        when(rentSettingsRepository.findAll()).thenReturn(List.of());
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(cheque));

        scheduler.sendDailyNotifications();

        ArgumentCaptor<EmailEvent> captor = ArgumentCaptor.forClass(EmailEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(EmailEventType.CHEQUE_DEPOSIT_REMINDER);
    }

    @Test
    void checkChequeDepositReminders_skipsChequeWithNullChequeDate() {
        UUID renterUserId = UUID.randomUUID();
        PaymentSchedule cheque = collectedCheque(renterUserId, LocalDate.now().plusDays(3));
        cheque.setChequeDate(null);
        when(rentSettingsRepository.findAll()).thenReturn(List.of());
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(cheque));

        scheduler.sendDailyNotifications();

        verify(events, never()).publishEvent(any());
    }

    private PaymentSchedule collectedCheque(UUID renterUserId, LocalDate chequeDate) {
        Lease lease = new Lease();
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        lease.setRenter(renter);

        PaymentSchedule payment = new PaymentSchedule();
        payment.setId(UUID.randomUUID());
        payment.setLease(lease);
        payment.setStatus(PaymentStatus.COLLECTED);
        payment.setChequeDate(chequeDate);
        payment.setDueDate(chequeDate);
        payment.setAmount(new BigDecimal("4000"));
        payment.setInstallmentNumber(2);
        payment.setChequeNumber("CHQ-DEP-1");
        payment.setBankName("Mashreq");
        return payment;
    }
```

Note: `checkPaymentDueReminders_publishesPaymentDueReminderEmailEventForMatchingPending` (Task 6's test) uses `payment.setChequeDate(...)` = never set (null), so it won't also match `checkChequeDepositReminders` and double-publish — no change needed there.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests NotificationSchedulerTest`
Expected: FAIL — `checkChequeDepositReminders_publishesChequeDepositReminderForPendingChequeDueInWindow` and `checkChequeDepositReminders_fallsBackToDefaultThreeDaysWhenNoSettingConfigured` fail with "Wanted but not invoked" (the method doesn't exist / isn't called yet); the two "skips..." tests pass vacuously (nothing is published either way yet — not a useful signal, will be re-run in Step 4).

- [ ] **Step 3: Implement `checkChequeDepositReminders` and wire it in**

In `NotificationScheduler.java`, add the call to `sendDailyNotifications()`:

```java
    @Scheduled(cron = "0 0 8 * * *") // 8 AM daily
    @Transactional(readOnly = true)
    public void sendDailyNotifications() {
        log.info("Running daily notification check...");
        checkPaymentDueReminders();
        checkOverduePayments();
        checkExpiringLeases();
        checkChequeDepositReminders();
        log.info("Daily notification check complete.");
    }
```

Add the new private method after `checkExpiringLeases()` (before the closing brace of the class):

```java
    private void checkChequeDepositReminders() {
        log.info("Checking cheque deposit reminders...");
        LocalDate today = LocalDate.now();

        List<RentCollectionSettings> allSettings = rentSettingsRepository.findAll();
        Set<Integer> reminderDays = new java.util.HashSet<>();
        for (RentCollectionSettings settings : allSettings) {
            if (settings.getChequeDepositReminderDays() != null) {
                reminderDays.add(settings.getChequeDepositReminderDays());
            }
        }
        if (reminderDays.isEmpty()) {
            reminderDays.add(3); // default
        }

        Set<PaymentStatus> eligibleStatuses = Set.of(PaymentStatus.PENDING, PaymentStatus.COLLECTED);
        for (int daysBefore : reminderDays) {
            LocalDate targetDate = today.plusDays(daysBefore);
            List<PaymentSchedule> cheques = paymentScheduleRepository.findAll().stream()
                    .filter(ps -> eligibleStatuses.contains(ps.getStatus()))
                    .filter(ps -> ps.getChequeDate() != null && ps.getChequeDate().equals(targetDate))
                    .toList();

            for (PaymentSchedule cheque : cheques) {
                try {
                    if (cheque.getLease() != null && cheque.getLease().getRenter() != null
                            && cheque.getLease().getRenter().getUserId() != null) {
                        UUID renterUserId = cheque.getLease().getRenter().getUserId();
                        notificationService.notifyInApp(
                                cheque.getTenantId(),
                                renterUserId,
                                "CHEQUE_DEPOSIT_REMINDER",
                                "Cheque Deposit Reminder",
                                "Cheque " + cheque.getChequeNumber() + " for installment #"
                                        + cheque.getInstallmentNumber() + " will be deposited in "
                                        + daysBefore + " day(s). Please ensure sufficient funds are available.",
                                "PAYMENT",
                                cheque.getId());
                        events.publishEvent(new EmailEvent(this,
                                EmailEventType.CHEQUE_DEPOSIT_REMINDER,
                                cheque.getTenantId(),
                                new ChequePayload(
                                        cheque.getId(),
                                        cheque.getLease().getId(),
                                        renterUserId,
                                        null,
                                        cheque.getInstallmentNumber(),
                                        cheque.getChequeNumber(),
                                        cheque.getBankName(),
                                        cheque.getAmount() != null ? cheque.getAmount().toPlainString() + " AED" : null,
                                        cheque.getDueDate() != null ? cheque.getDueDate().toString() : null,
                                        null,
                                        null),
                                "CHEQUE_DEPOSIT_REMINDER:" + cheque.getId() + ":" + daysBefore));
                    }
                } catch (Exception e) {
                    log.warn("Failed to send cheque deposit reminder for payment {}", cheque.getId(), e);
                }
            }

            log.info("Sent {} cheque deposit reminders for {} days before cheque date", cheques.size(), daysBefore);
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests NotificationSchedulerTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/NotificationScheduler.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/NotificationSchedulerTest.java
git commit -m "feat(payments): add cheque deposit reminder scheduler check"
```

---

### Task 8: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full backend test suite**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including `PaymentScheduleServiceMarkFailedTest`, `PaymentScheduleServiceVatTest`, `PaymentScheduleServiceBulkAttachTest`, `PaymentScheduleServiceChequeImageTest`, `RecipientResolverTest`, `NotificationSchedulerTest`, `PaymentScheduleServiceChequeEmailEventsTest`, and the existing `ChequeFailurePenaltyIT` which boots the full Spring context including Liquibase — confirms changeset 64 applies cleanly).

- [ ] **Step 2: Full build**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL

If everything passes, the plan is complete — no further commit needed (Task 7's commit is the final one). If Step 1 or Step 2 surfaces any failure, fix it and re-run before considering the plan done.
