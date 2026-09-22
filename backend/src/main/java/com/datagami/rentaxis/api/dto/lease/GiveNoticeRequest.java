package com.datagami.rentaxis.api.dto.lease;

/**
 * The body of {@code POST /api/v1/leases/{id}/notice} — optional, because the
 * fact itself is the whole of it.
 *
 * <p>There is no notice <em>date</em> field, and that is deliberate: the lease
 * carries no column for one, and inventing one here would put a date on a screen
 * that nothing reads back. What the notice was about goes on the lease's event
 * trail, which is where somebody later asks "why did this tenancy end?".</p>
 *
 * @param notes free text kept on the lease event, e.g. "Relocating to Abu Dhabi".
 */
public record GiveNoticeRequest(String notes) {
}
