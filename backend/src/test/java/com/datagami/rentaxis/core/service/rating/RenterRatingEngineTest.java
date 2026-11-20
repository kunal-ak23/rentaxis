package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.core.service.rating.RenterHistory.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RenterRatingEngineTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    static final ZoneId ZONE = ZoneId.of("Asia/Dubai");
    static final UUID RENTER = UUID.randomUUID();

    static Instant at(LocalDate d) { return d.atTime(12, 0).atZone(ZONE).toInstant(); }

    /** Mutable builder for a RenterHistory. */
    static class H {
        List<LeaseRow> leases = new ArrayList<>();
        List<ChequeRow> cheques = new ArrayList<>();
        List<PenaltyRow> penalties = new ArrayList<>();
        List<WriteOffRow> writeOffs = new ArrayList<>();
        List<LeaseEventRow> leaseEvents = new ArrayList<>();
        List<OpportunityRow> opportunities = new ArrayList<>();
        List<MeetingRow> meetings = new ArrayList<>();
        List<AdjustmentRow> adjustments = new ArrayList<>();
        UUID lease = UUID.randomUUID();

        H lease(LocalDate start, LocalDate end) {
            leases.add(new LeaseRow(lease, LeaseStatus.ACTIVE, start, end, null));
            return this;
        }
        H lease13() { return lease(TODAY.minusMonths(13), TODAY.plusMonths(2)); }
        H onTime(int n) {
            for (int i = 0; i < n; i++) {
                LocalDate cd = TODAY.minusDays(10 + (i % 12) * 28L);
                cheques.add(new ChequeRow(UUID.randomUUID(), lease, ChequeStatus.CLEARED, cd,
                        cd.plusDays(i % 8), null, null, null));
            }
            return this;
        }
        H cheque(ChequeStatus st, LocalDate cd, LocalDate cleared, LocalDate bounced, LocalDate imported,
                 ChequeFailureReason fr) {
            cheques.add(new ChequeRow(UUID.randomUUID(), lease, st, cd, cleared, bounced, imported, fr));
            return this;
        }
        H penalty(PenaltyReason r, PenaltyAssessmentStatus s, LocalDate d, UUID srcCheque) {
            penalties.add(new PenaltyRow(UUID.randomUUID(), lease, r, s, d, srcCheque));
            return this;
        }
        H writeOff(String st, LocalDate d) {
            writeOffs.add(new WriteOffRow(UUID.randomUUID(), lease, st, d));
            return this;
        }
        H hold(LocalDate in, LocalDate out) {
            leaseEvents.add(new LeaseEventRow(lease, "ACTIVE", "ON_HOLD", at(in)));
            if (out != null) leaseEvents.add(new LeaseEventRow(lease, "ON_HOLD", "ACTIVE", at(out)));
            return this;
        }
        H opp(RenewalIntent i, LocalDate captured, RenewalOutcome o, LocalDate closed) {
            opportunities.add(new OpportunityRow(UUID.randomUUID(), lease, i,
                    captured == null ? null : at(captured), o, closed == null ? null : at(closed)));
            return this;
        }
        H meeting(UUID leaseId, MeetingStatus s, LocalDate d) {
            meetings.add(new MeetingRow(UUID.randomUUID(), leaseId, s, at(d)));
            return this;
        }
        H adj(int pts, String reason, LocalDate d) {
            adjustments.add(new AdjustmentRow(UUID.randomUUID(), pts, reason, UUID.randomUUID(), at(d)));
            return this;
        }
        RenterRatingResult run() {
            return run(TODAY);
        }
        RenterRatingResult run(LocalDate today) {
            return RenterRatingEngine.score(new RenterHistory(RENTER, leases, cheques, penalties, writeOffs,
                    leaseEvents, opportunities, meetings, adjustments), today);
        }
    }

    static H history() { return new H(); }

    static List<RatingEvent> of(RenterRatingResult r, RatingEventType t) {
        return r.events().stream().filter(e -> e.type() == t).toList();
    }

    @Test
    void emptyHistoryIsNewAt70() {
        RenterRatingResult r = history().run();
        assertThat(r.score()).isEqualTo(70);
        assertThat(r.grade()).isEqualTo(RatingGrade.NEW);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void onTimeChequesCreditOneEach() {
        RenterRatingResult r = history().lease13().onTime(12).run();
        assertThat(r.score()).isEqualTo(82);
        assertThat(r.grade()).isEqualTo(RatingGrade.B);
        assertThat(of(r, RatingEventType.ON_TIME_CHEQUE)).hasSize(12);
    }

    @Test
    void onTimeCreditCappedAt20() {
        RenterRatingResult r = history().lease13().onTime(30).run();
        assertThat(r.score()).isEqualTo(90);
        assertThat(of(r, RatingEventType.ON_TIME_CHEQUE).stream().mapToDouble(RatingEvent::appliedPoints).sum())
                .isCloseTo(20.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    H example() {
        H h = history().lease(TODAY.minusMonths(13), TODAY.plusDays(20)).onTime(12);
        return h.opp(RenewalIntent.RENEW, TODAY.minusDays(20), null, null);
    }

    @Test
    void workedExampleA() {
        RenterRatingResult r = example().run();
        assertThat(r.score()).isEqualTo(85);
        assertThat(r.grade()).isEqualTo(RatingGrade.A);
    }

    @Test
    void workedExampleBounce() {
        RenterRatingResult r = example()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusMonths(8).minusDays(5), null, TODAY.minusMonths(8), null,
                        ChequeFailureReason.BOUNCE).run();
        assertThat(r.score()).isEqualTo(70);
        assertThat(r.grade()).isEqualTo(RatingGrade.B);
        assertThat(of(r, RatingEventType.BOUNCE)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-15.0);
    }

    @Test
    void replacedBounceStillCounts() {
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.REPLACED, TODAY.minusDays(60), null, TODAY.minusDays(50), null, null).run();
        assertThat(of(r, RatingEventType.BOUNCE)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-15.0);
    }

    @Test
    void returnedChequeIsNotABounce() {
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.RETURNED, TODAY.minusDays(60), null, null, null, null).run();
        assertThat(of(r, RatingEventType.BOUNCE)).isEmpty();
    }

    @Test
    void technicalReturnExcluded() {
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusDays(60), null, TODAY.minusDays(50), null,
                        ChequeFailureReason.TECHNICAL_RETURN).run();
        assertThat(of(r, RatingEventType.BOUNCE)).isEmpty();
    }

    @Test
    void importedBounceCountsOnce() {
        LocalDate b = TODAY.minusDays(50);
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusDays(60), null, b, TODAY.minusDays(49), null).run();
        assertThat(of(r, RatingEventType.BOUNCE)).singleElement().extracting(RatingEvent::date).isEqualTo(b);
    }

    @Test
    void lateClearMinus3() {
        LocalDate cd = TODAY.minusDays(60);
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.CLEARED, cd, cd.plusDays(8), null, null, null).run();
        assertThat(of(r, RatingEventType.LATE_CLEAR)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-3.0);
        assertThat(of(r, RatingEventType.ON_TIME_CHEQUE)).isEmpty();
    }

    @Test
    void weightBoundaryAt12Months() {
        // TODAY.minusMonths(12) is exactly 365 days back for this TODAY; see the leap-year test for months vs days.
        RenterRatingResult a = history().lease13()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusDays(400), null, TODAY.minusDays(365), null, null).run();
        assertThat(of(a, RatingEventType.BOUNCE)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-15.0);
        RenterRatingResult b = history().lease13()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusDays(400), null, TODAY.minusDays(366), null, null).run();
        assertThat(of(b, RatingEventType.BOUNCE)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-7.5);
    }

    @Test
    void weightBoundaryAt24Months() {
        RenterRatingResult a = history().lease13()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusDays(800), null, TODAY.minusDays(730), null, null).run();
        assertThat(of(a, RatingEventType.BOUNCE)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-7.5);
        RenterRatingResult b = history().lease13()
                .cheque(ChequeStatus.BOUNCED, TODAY.minusDays(800), null, TODAY.minusDays(731), null, null).run();
        assertThat(of(b, RatingEventType.BOUNCE)).isEmpty();
    }

    /** Spec 5.1 counts calendar months: across a leap day, 12 months is 366 days and 24 months is 731. */
    @Test
    void weightBoundariesAreCalendarMonthsNotDays() {
        LocalDate today = LocalDate.of(2024, 3, 1);
        assertThat(today.minusMonths(12)).isEqualTo(LocalDate.of(2023, 3, 1));
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2023, 3, 1), today)).isEqualTo(366);
        assertThat(RenterRatingEngine.weight(LocalDate.of(2023, 3, 1), today)).isEqualTo(1.0);
        assertThat(RenterRatingEngine.weight(LocalDate.of(2023, 2, 28), today)).isEqualTo(0.5);
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2022, 3, 1), today)).isEqualTo(731);
        assertThat(RenterRatingEngine.weight(LocalDate.of(2022, 3, 1), today)).isEqualTo(0.5);
        assertThat(RenterRatingEngine.weight(LocalDate.of(2022, 2, 28), today)).isEqualTo(0.0);

        H h = history().lease(today.minusMonths(30), today.plusMonths(2));
        h.cheque(ChequeStatus.BOUNCED, LocalDate.of(2023, 2, 1), null, LocalDate.of(2023, 3, 1), null, null);
        h.cheque(ChequeStatus.BOUNCED, LocalDate.of(2022, 2, 1), null, LocalDate.of(2022, 3, 1), null, null);
        RenterRatingResult r = h.run(today);
        assertThat(of(r, RatingEventType.BOUNCE)).extracting(RatingEvent::appliedPoints)
                .containsExactlyInAnyOrder(-15.0, -7.5);
        assertThat(RenterRatingEngine.windowStart(today)).isEqualTo(LocalDate.of(2022, 3, 1));
    }

    /** M6: an event dated after today has not happened yet and is not scored. */
    @Test
    void eventsDatedAfterTodayAreSkipped() {
        assertThat(RenterRatingEngine.weight(TODAY.plusDays(1), TODAY)).isEqualTo(0.0);
        assertThat(RenterRatingEngine.weight(TODAY, TODAY)).isEqualTo(1.0);

        // a posted renewal that starts next month: no credit yet
        H h = history().lease13();
        h.leases.add(new LeaseRow(UUID.randomUUID(), LeaseStatus.ACTIVE, TODAY.plusMonths(1), TODAY.plusMonths(13),
                h.lease));
        RenterRatingResult r = h.run();
        assertThat(of(r, RatingEventType.RENEWAL)).isEmpty();
        assertThat(r.score()).isEqualTo(70);

        // the same renewal once it has started is credited
        H started = history().lease13();
        started.leases.add(new LeaseRow(UUID.randomUUID(), LeaseStatus.ACTIVE, TODAY, TODAY.plusMonths(12),
                started.lease));
        assertThat(of(started.run(), RatingEventType.RENEWAL)).singleElement()
                .extracting(RatingEvent::appliedPoints).isEqualTo(5.0);
    }

    /** An adjustment is dated when it was recorded; one recorded after the batch's "today" still counts. */
    @Test
    void adjustmentRecordedAfterTodayStillCounts() {
        RenterRatingResult r = history().lease13().adj(5, "Recorded after midnight", TODAY.plusDays(1)).run();
        assertThat(of(r, RatingEventType.ADJUSTMENT)).singleElement()
                .extracting(RatingEvent::appliedPoints).isEqualTo(5.0);
        assertThat(r.score()).isEqualTo(75);
    }

    @Test
    void latePaymentApprovedAndWrittenOffCount() {
        for (PenaltyAssessmentStatus s : PenaltyAssessmentStatus.values()) {
            RenterRatingResult r = history().lease13()
                    .penalty(PenaltyReason.LATE_PAYMENT, s, TODAY.minusDays(30), null).run();
            boolean counts = s == PenaltyAssessmentStatus.APPROVED || s == PenaltyAssessmentStatus.WRITTEN_OFF;
            if (counts) {
                assertThat(of(r, RatingEventType.LATE_PAYMENT_PENALTY)).singleElement()
                        .extracting(RatingEvent::appliedPoints).isEqualTo(-8.0);
            } else {
                assertThat(r.events()).as(s.name()).isEmpty();
            }
        }
    }

    @Test
    void damageCountsMisconduct() {
        RenterRatingResult r = history().lease13()
                .penalty(PenaltyReason.DAMAGE, PenaltyAssessmentStatus.APPROVED, TODAY.minusDays(30), null).run();
        assertThat(of(r, RatingEventType.MISCONDUCT_PENALTY)).singleElement()
                .extracting(RatingEvent::appliedPoints).isEqualTo(-10.0);
    }

    @Test
    void chargeReasonsIgnored() {
        for (PenaltyReason pr : List.of(PenaltyReason.SERVICE_RECHARGE, PenaltyReason.ADMIN_FEE,
                PenaltyReason.MAINTENANCE_RECHARGE, PenaltyReason.BOOKING_FEE, PenaltyReason.JOURNAL_VOUCHER,
                PenaltyReason.OTHER, PenaltyReason.USE_AND_OCCUPATION)) {
            RenterRatingResult r = history().lease13()
                    .penalty(pr, PenaltyAssessmentStatus.APPROVED, TODAY.minusDays(30), null).run();
            assertThat(r.events()).as(pr.name()).isEmpty();
        }
    }

    @Test
    void chequeReturnPenaltyLinkedToChequeNotDoubleCounted() {
        RenterRatingResult linked = history().lease13().penalty(PenaltyReason.CHEQUE_RETURN,
                PenaltyAssessmentStatus.APPROVED, TODAY.minusDays(30), UUID.randomUUID()).run();
        assertThat(linked.events()).isEmpty();
        RenterRatingResult unlinked = history().lease13().penalty(PenaltyReason.CHEQUE_RETURN,
                PenaltyAssessmentStatus.APPROVED, TODAY.minusDays(30), null).run();
        assertThat(of(unlinked, RatingEventType.MISCONDUCT_PENALTY)).singleElement()
                .extracting(RatingEvent::appliedPoints).isEqualTo(-10.0);
    }

    @Test
    void writeOffMinus30OnlyWhenWrittenOff() {
        RenterRatingResult r = history().lease13().writeOff("WRITTEN_OFF", TODAY.minusDays(20))
                .writeOff("PROPOSED", TODAY.minusDays(20)).writeOff("REJECTED", TODAY.minusDays(20))
                .writeOff("REVERSED", TODAY.minusDays(20)).run();
        assertThat(of(r, RatingEventType.WRITE_OFF)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-30.0);
        assertThat(r.score()).isEqualTo(40);
    }

    @Test
    void longHoldOver14Days() {
        LocalDate x = TODAY.minusDays(100);
        RenterRatingResult a = history().lease13().hold(x, x.plusDays(20)).run();
        assertThat(of(a, RatingEventType.LONG_HOLD)).singleElement().satisfies(e -> {
            assertThat(e.appliedPoints()).isEqualTo(-10.0);
            assertThat(e.date()).isEqualTo(x.plusDays(15));
        });
        assertThat(of(history().lease13().hold(x, x.plusDays(14)).run(), RatingEventType.LONG_HOLD)).isEmpty();
        assertThat(of(history().lease13().hold(TODAY.minusDays(30), null).run(), RatingEventType.LONG_HOLD))
                .singleElement().satisfies(e -> {
                    assertThat(e.appliedPoints()).isEqualTo(-10.0);
                    assertThat(e.date()).isEqualTo(TODAY.minusDays(15));
                });
    }

    @Test
    void longHoldOncePerLease() {
        LocalDate x = TODAY.minusDays(200);
        RenterRatingResult r = history().lease13().hold(x, x.plusDays(20)).hold(x.plusDays(60), x.plusDays(90)).run();
        assertThat(of(r, RatingEventType.LONG_HOLD)).hasSize(1);
    }

    @Test
    void noResponseMinus5() {
        RenterRatingResult r = history().lease13()
                .opp(RenewalIntent.DISCUSS, null, RenewalOutcome.EXPIRED_NO_RESPONSE, TODAY.minusDays(10)).run();
        assertThat(of(r, RatingEventType.NO_RESPONSE)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-5.0);
    }

    @Test
    void leaseLinkedNoShowMinus5AndUnlinkedIgnored() {
        H h = history().lease13();
        h.meeting(h.lease, MeetingStatus.NO_SHOW, TODAY.minusDays(10));
        h.meeting(null, MeetingStatus.NO_SHOW, TODAY.minusDays(10));
        h.meeting(h.lease, MeetingStatus.COMPLETED, TODAY.minusDays(10));
        RenterRatingResult r = h.run();
        assertThat(of(r, RatingEventType.MEETING_NO_SHOW)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(-5.0);
        assertThat(r.events()).hasSize(1);
    }

    @Test
    void renewalCreditCappedAt10() {
        H h = history().lease13();
        UUID prev = h.lease;
        for (int i = 0; i < 3; i++) {
            UUID next = UUID.randomUUID();
            h.leases.add(new LeaseRow(next, LeaseStatus.ACTIVE, TODAY.minusMonths(10 - i * 2), TODAY.plusMonths(1),
                    prev));
            prev = next;
        }
        RenterRatingResult r = h.run();
        assertThat(of(r, RatingEventType.RENEWAL)).hasSize(3);
        assertThat(r.score()).isEqualTo(80);
    }

    @Test
    void earlyIntentNeeds30Days() {
        // intents captured in the past (an intent dated after today is not scored)
        LocalDate end = TODAY.plusDays(20);
        RenterRatingResult a = history().lease(TODAY.minusMonths(13), end)
                .opp(RenewalIntent.RENEW, end.minusDays(29), null, null).run();
        assertThat(of(a, RatingEventType.EARLY_INTENT)).isEmpty();
        RenterRatingResult b = history().lease(TODAY.minusMonths(13), end)
                .opp(RenewalIntent.RENEW, end.minusDays(30), null, null).run();
        assertThat(of(b, RatingEventType.EARLY_INTENT)).singleElement().extracting(RatingEvent::appliedPoints)
                .isEqualTo(3.0);
    }

    @Test
    void newRuleBroken() {
        H a = history().lease(TODAY.minusMonths(5), TODAY.plusMonths(7)).onTime(6);
        assertThat(a.run().grade()).isEqualTo(RatingGrade.NEW);

        H b = history().lease(TODAY.minusMonths(5), TODAY.plusMonths(7)).onTime(6);
        b.cheque(ChequeStatus.CLEARED, TODAY.minusDays(60), TODAY.minusDays(50), null, null, null);
        RenterRatingResult rb = b.run();
        assertThat(rb.grade()).isEqualTo(RatingGrade.B);
        assertThat(rb.score()).isEqualTo(73);

        H c = history().lease(TODAY.minusMonths(5), TODAY.plusMonths(7)).onTime(6).adj(5, "vouched", TODAY.minusDays(3));
        RenterRatingResult rc = c.run();
        assertThat(rc.grade()).isEqualTo(RatingGrade.B);
        assertThat(rc.score()).isEqualTo(81);

        H d = history().lease13().onTime(2);
        assertThat(d.run().grade()).isEqualTo(RatingGrade.NEW);
    }

    @Test
    void draftLeaseIgnored() {
        H h = history();
        h.leases.add(new LeaseRow(h.lease, LeaseStatus.DRAFT, TODAY.minusMonths(13), TODAY.plusMonths(2), null));
        h.onTime(5);
        RenterRatingResult r = h.run();
        assertThat(r.grade()).isEqualTo(RatingGrade.NEW);
        assertThat(r.events()).isEmpty();
    }

    @Test
    void adjustmentAppliesAndCountsAsDeductionWhenNegative() {
        RenterRatingResult r = history().lease13().adj(-10, "Disputed move-out", TODAY.minusDays(2)).run();
        assertThat(r.score()).isEqualTo(60);
        assertThat(r.deductionsCount()).isEqualTo(1);
        assertThat(of(r, RatingEventType.ADJUSTMENT)).singleElement().extracting(RatingEvent::note)
                .isEqualTo("Disputed move-out");
    }

    @Test
    void adjustmentCarriesItsCreatorAndOtherEventsDoNot() {
        H h = history().lease13().onTime(3).adj(5, "Vouched", TODAY.minusDays(2));
        UUID creator = h.adjustments.get(0).createdBy();
        RenterRatingResult r = h.run();
        assertThat(of(r, RatingEventType.ADJUSTMENT)).singleElement().extracting(RatingEvent::createdBy)
                .isEqualTo(creator);
        assertThat(of(r, RatingEventType.ON_TIME_CHEQUE)).isNotEmpty().allMatch(e -> e.createdBy() == null);
    }

    @Test
    void clampAt0And100() {
        H low = history().lease13();
        for (int i = 0; i < 5; i++) low.writeOff("WRITTEN_OFF", TODAY.minusDays(10 + i));
        assertThat(low.run().score()).isEqualTo(0);

        H high = history().lease(TODAY.minusMonths(13), TODAY.plusDays(100)).onTime(30);
        UUID prev = high.lease;
        for (int i = 0; i < 3; i++) {
            UUID next = UUID.randomUUID();
            high.leases.add(new LeaseRow(next, LeaseStatus.ACTIVE, TODAY.minusMonths(10 - i), TODAY.plusMonths(1), prev));
            prev = next;
        }
        for (int i = 0; i < 3; i++) {
            high.opp(RenewalIntent.RENEW, TODAY.minusDays(i + 1), null, null);
        }
        assertThat(high.run().score()).isEqualTo(100);
    }

    @Test
    void fingerprintStableAndSensitive() {
        H h = history().lease13().onTime(5);
        RenterRatingResult base = h.run();
        assertThat(h.run().fingerprint()).isEqualTo(base.fingerprint()).hasSize(64);
        RenterRatingResult changed = new RenterRatingResult(base.renterId(), base.score() + 1, base.grade(),
                base.deductionsCount(), base.events());
        assertThat(changed.fingerprint()).isNotEqualTo(base.fingerprint());
        List<RatingEvent> swapped = new ArrayList<>(base.events());
        RatingEvent e0 = swapped.get(0);
        swapped.set(0, new RatingEvent(e0.type(), e0.date(), e0.basePoints(), e0.weight(), e0.appliedPoints(),
                e0.sourceType(), UUID.randomUUID(), e0.leaseId(), e0.note(), e0.createdBy()));
        RenterRatingResult sameScore = new RenterRatingResult(base.renterId(), base.score(), base.grade(),
                base.deductionsCount(), swapped);
        assertThat(sameScore.fingerprint()).isNotEqualTo(base.fingerprint());
    }

    @Test
    void technicalReturnLaterClearedGetsClearRules() {
        LocalDate cd = TODAY.minusDays(60);
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.CLEARED, cd, cd.plusDays(2), cd.plusDays(1), null,
                        ChequeFailureReason.TECHNICAL_RETURN).run();
        assertThat(of(r, RatingEventType.BOUNCE)).isEmpty();
        assertThat(of(r, RatingEventType.ON_TIME_CHEQUE)).hasSize(1);
    }

    @Test
    void renewalFromForeignLeaseNotCredited() {
        H h = history().lease13();
        h.leases.add(new LeaseRow(UUID.randomUUID(), LeaseStatus.ACTIVE, TODAY.minusMonths(3), TODAY.plusMonths(9),
                UUID.randomUUID()));
        assertThat(of(h.run(), RatingEventType.RENEWAL)).isEmpty();
    }

    @Test
    void postDatedAndVoidChequesDontLeaveNew() {
        H h = history().lease(TODAY.minusDays(195), TODAY.plusMonths(6));
        h.cheque(ChequeStatus.CLEARED, TODAY.minusDays(60), TODAY.minusDays(60), null, null, null);
        h.cheque(ChequeStatus.CLEARED, TODAY.minusDays(30), TODAY.minusDays(30), null, null, null);
        h.cheque(ChequeStatus.REGISTERED, TODAY.plusDays(30), null, null, null, null);
        h.cheque(ChequeStatus.CANCELLED, TODAY.minusDays(90), null, null, null, null);
        assertThat(h.run().grade()).isEqualTo(RatingGrade.NEW);
    }

    @Test
    void nonClearedStatusWithStrayClearedAtGivesNoEvent() {
        LocalDate cd = TODAY.minusDays(60);
        RenterRatingResult r = history().lease13()
                .cheque(ChequeStatus.REPLACED, cd, cd.plusDays(1), null, null, null).run();
        assertThat(r.events()).isEmpty();
    }

    @Test
    void eventsNewestFirst() {
        RenterRatingResult r = history().lease13().onTime(6)
                .penalty(PenaltyReason.LATE_PAYMENT, PenaltyAssessmentStatus.APPROVED, TODAY.minusDays(1), null)
                .writeOff("WRITTEN_OFF", TODAY.minusDays(200)).run();
        List<LocalDate> dates = r.events().stream().map(RatingEvent::date).toList();
        assertThat(dates).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(dates.get(0)).isEqualTo(TODAY.minusDays(1));
    }
}
