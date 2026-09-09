package com.datagami.rentaxis.domain.entity.enums;

/**
 * How a renewal opportunity ended.
 *
 * <p>Stored as a STRING in {@code renewal_opportunities.outcome}
 * (varchar(30), changeset 58), so adding a value needs no migration.
 *
 * <p>The first three were the whole vocabulary, which left the nightly close
 * with nowhere honest to put two common cases — a renter who answered RENEW but
 * whose lease lapsed before anyone created the renewal, and a lease terminated
 * early. Both were written as {@link #MOVED_OUT}, so the renewal funnel reported
 * renters as having moved out when they had asked to stay.
 */
public enum RenewalOutcome {
	/** The PM created the renewal. The only CLOSED_WON outcome. */
	RENEWED,

	/** The renter said they were leaving, and the lease ended. */
	MOVED_OUT,

	/** The lease ended with no intent ever captured. */
	EXPIRED_NO_RESPONSE,

	/**
	 * The renter answered RENEW or DISCUSS, but the lease lapsed before anyone
	 * created the renewal. A lost renewal, not a departure — and the one worth
	 * chasing, because the renter wanted to stay.
	 */
	RENEWAL_NOT_ACTIONED,

	/** The lease was terminated early, so the renewal question never arose. */
	LEASE_TERMINATED
}
