package com.datagami.rentaxis.core.service.rating;

/** All renter-rating policy numbers in one place. */
public final class RenterRatingPolicy {

    private RenterRatingPolicy() {}

    public static final int BASE_SCORE = 70;
    public static final int ON_TIME_GRACE_DAYS = 7;

    // credits
    public static final int ON_TIME_CHEQUE_POINTS = 1;
    public static final int ON_TIME_CHEQUE_CAP = 20;
    public static final int RENEWAL_POINTS = 5;
    public static final int RENEWAL_CAP = 10;
    public static final int EARLY_INTENT_POINTS = 3;
    public static final int EARLY_INTENT_CAP = 6;
    public static final int EARLY_INTENT_MIN_DAYS = 30;

    // deductions
    public static final int BOUNCE_POINTS = -15;
    public static final int LATE_CLEAR_POINTS = -3;
    public static final int LATE_PAYMENT_POINTS = -8;
    public static final int MISCONDUCT_POINTS = -10;
    public static final int WRITE_OFF_POINTS = -30;
    public static final int LONG_HOLD_POINTS = -10;
    public static final int LONG_HOLD_MAX_DAYS = 14;
    public static final int NO_RESPONSE_POINTS = -5;
    public static final int NO_SHOW_POINTS = -5;

    // age weights
    public static final int FULL_WEIGHT_DAYS = 365;
    public static final int HALF_WEIGHT_DAYS = 730;
    public static final double FULL_WEIGHT = 1.0;
    public static final double HALF_WEIGHT = 0.5;

    // grades
    public static final int GRADE_A_MIN = 85;
    public static final int GRADE_B_MIN = 70;
    public static final int GRADE_C_MIN = 50;

    // NEW rule
    public static final int NEW_MIN_LEASE_MONTHS = 6;
    public static final int NEW_MIN_CHEQUES = 3;

    // adjustments
    public static final int ADJUSTMENT_MIN = -30;
    public static final int ADJUSTMENT_MAX = 30;
    public static final int ADJUSTMENT_REASON_MAX_LENGTH = 500;
}
