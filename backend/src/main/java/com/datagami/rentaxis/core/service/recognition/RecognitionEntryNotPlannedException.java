package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;

import java.util.Map;

/**
 * The row is no longer {@code PLANNED}: another run (or an amendment) got to it
 * first. Break-it R4 money4 F1: a month-end run treats this as "already done",
 * not as a failure, so it is its own type rather than a message to match on. The
 * message names no internal id.
 */
public class RecognitionEntryNotPlannedException extends BusinessRuleViolationException {

    private final RecognitionStatus status;

    public RecognitionEntryNotPlannedException(RecognitionStatus status) {
        super("This recognition entry is already " + status + ".", "recognition.notPlanned",
                Map.of("status", String.valueOf(status)));
        this.status = status;
    }

    public RecognitionStatus getStatus() {
        return status;
    }
}
