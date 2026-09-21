package com.datagami.rentaxis.domain.entity.enums;

/**
 * What has become of a rent segment (spec §8.1, §8.5).
 *
 * <p>A segment is never edited in place and never deleted: the entries cut from
 * it point at it, and the journals those entries posted point at them. A segment
 * that stops being the truth is retired and a new one takes over.</p>
 */
public enum SegmentStatus {
    /** The live segment: its entries are the schedule. */
    ACTIVE,
    /** Cut short by a termination at a date inside its window; {@code toDate} is that date. */
    TRUNCATED,
    /** Superseded — by an amendment that restated the lines, or by a termination before it began. */
    CANCELLED
}
