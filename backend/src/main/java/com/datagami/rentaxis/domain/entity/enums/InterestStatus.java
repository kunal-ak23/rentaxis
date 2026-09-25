package com.datagami.rentaxis.domain.entity.enums;

public enum InterestStatus {
    ACTIVE,
    NOTIFIED,
    WITHDRAWN,
    /** F14-51: the interest became a draft lease. */
    CONVERTED
}
