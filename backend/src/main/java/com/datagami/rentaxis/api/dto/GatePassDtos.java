package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire types for {@code GatePassController}.
 *
 * <p>Grouped in one container rather than the one-record-per-file convention used
 * elsewhere in {@code api/dto} because these are only meaningful as a set: the
 * split between {@link GatePassResponse} and {@link GatePassSummary} IS the
 * security boundary of this module, and keeping the two adjacent is what makes
 * that split reviewable. See the note on {@link GatePassSummary}.
 */
public final class GatePassDtos {

    private GatePassDtos() {
    }

    /**
     * {@code propertyId} is deliberately absent: it is derived server-side from the
     * unit's active lease, never taken from the client.
     */
    public record CreateGatePassRequest(UUID unitId, String guestName, String guestPhone, String purpose,
                                        String vehicleNumber, GatePassType passType, Instant validFrom,
                                        Instant validTo) {
    }

    /**
     * The creator's full view, <b>including the pass credentials</b>
     * ({@code qrToken}, {@code numericCode}).
     *
     * <p>Anyone holding either value can walk through the gate, so this record must
     * only ever be returned to the pass's creator. Every other audience — guards
     * scanning, guards or managers approving, the expected-today board — gets
     * {@link GatePassSummary} instead.
     */
    public record GatePassResponse(UUID id, UUID propertyId, UUID unitId, String guestName, String guestPhone,
                                   String purpose, String vehicleNumber, GatePassType passType, Instant validFrom,
                                   Instant validTo, GatePassStatus status, String qrToken, String numericCode,
                                   Instant createdAt) {
    }

    /**
     * The non-creator view: same pass, no credentials and no renter identity.
     *
     * <p>Used for the approvals list, the approval decision, and expected-today.
     * Deliberately omits {@code qrToken} / {@code numericCode}: an approver decides
     * yes/no on a guest, which needs none of the guest's admission credentials, and
     * a guard already receives the code by scanning it. Handing either out here
     * would let any approver — including a guard, who is an approver for their own
     * properties — silently collect live codes for passes they never scan.
     *
     * <p>Also omits {@code createdByUserId} so the guard-facing paths carry no renter
     * identity (SOW §3.1). {@code unitNumber} rather than {@code unitId} because it is
     * for display at the gate.
     */
    public record GatePassSummary(UUID id, UUID propertyId, UUID unitId, String unitNumber, String guestName,
                                  String guestPhone, String purpose, String vehicleNumber, GatePassType passType,
                                  Instant validFrom, Instant validTo, GatePassStatus status, Instant createdAt) {
    }

    /** Exactly one of {@code qrToken} / {@code numericCode} must be set — the controller enforces it. */
    public record ScanRequest(String qrToken, String numericCode, ScanDirection direction) {
    }

    /**
     * The gate's verdict as the guard's app sees it.
     *
     * <p><b>This record is a security boundary.</b> SOW §3.1 requires the Security
     * role never reach tenant financial or private information, so every field here
     * is guest-facing: no renter identity ({@code createdByUserId}, name, email), no
     * lease data, no financials, and no pass credentials. Adding a field to this
     * record is a security decision, not a formatting one —
     * {@code GatePassControllerTest.scanResponseCarriesNoRenterOrFinancialFields}
     * asserts the serialized JSON keys and will fail if this grows.
     */
    public record ScanResponse(ScanResult result, String reason, String guestName, String guestPhone,
                               String vehicleNumber, String purpose, String unitNumber, GatePassType passType,
                               Instant validFrom, Instant validTo) {
    }

    public record ApprovalDecision(boolean approved) {
    }

    /** One scan joined to its pass — a row of the manager gate-traffic report, shaped for CSV export. */
    public record GatePassReportRow(UUID scanId, Instant scannedAt, ScanDirection direction, ScanResult result,
                                    String rejectionReason, UUID scannedByUserId, UUID gatePassId, UUID propertyId,
                                    String unitNumber, String guestName, String guestPhone, String vehicleNumber,
                                    String purpose, GatePassType passType) {
    }
}
