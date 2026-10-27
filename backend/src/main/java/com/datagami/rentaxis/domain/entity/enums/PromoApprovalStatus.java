package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where an ad stands with the organisation's admins. Only APPROVED ads reach a
 * renter. Admin-written ads are APPROVED on create; a promoter's write (create,
 * edit, or an edit of the ad's business) puts the ad back to PENDING until an
 * admin approves it again.
 */
public enum PromoApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
