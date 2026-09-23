package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.domain.entity.enums.NoticeParty;

import java.time.LocalDate;

/**
 * The body of {@code POST /api/v1/leases/{id}/notice}. Every field is optional,
 * so an existing caller that posts no body, or only {@code notes}, keeps working.
 *
 * @param notes free text kept on the lease event, e.g. "Relocating to Abu Dhabi".
 * @param noticeDate the day notice was given (#27); defaults to today on the
 *        app clock (Asia/Dubai) and may not be in the future.
 * @param givenBy who gave it (#27); defaults to RENTER, which is what this
 *        endpoint always meant before the party was recorded.
 * @param intendedMoveOutDate the move-out date the notice names, if any; not
 *        before the notice date.
 */
public record GiveNoticeRequest(String notes,
                                LocalDate noticeDate,
                                NoticeParty givenBy,
                                LocalDate intendedMoveOutDate) {

    /** The shape before #27. */
    public GiveNoticeRequest(String notes) {
        this(notes, null, null, null);
    }
}
