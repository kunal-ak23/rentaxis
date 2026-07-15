package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
     *
     * <p>The constraints cover exactly the fields nothing else checks. {@code unitId} is
     * validated by the controller's active-lease rule and {@code validFrom}/{@code
     * validTo} by {@code GatePassService.create}, so annotating them here would only
     * duplicate a check and move its error message; the fields below had no check at all
     * before reaching Hibernate, where a null or an over-length value surfaced as a
     * {@code PropertyValueException} — a 500 echoing the entity class name rather than
     * the 400 it is. Lengths mirror the column definitions on {@code GatePass}, so the
     * DB constraint and this one cannot disagree.
     */
    public record CreateGatePassRequest(UUID unitId,
                                        @NotBlank @Size(max = 160) String guestName,
                                        @NotBlank @Size(max = 32) String guestPhone,
                                        @Size(max = 240) String purpose,
                                        @Size(max = 32) String vehicleNumber,
                                        @NotNull GatePassType passType,
                                        Instant validFrom,
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
     * identity (SOW §3.1). It carries {@code unitNumber} <i>as well as</i> {@code unitId}:
     * the number is what gets displayed at the gate, but it is only unique within a
     * property, so clients keying on a unit still need the id. Neither is a credential
     * and neither identifies the renter, so both are in scope for this view.
     *
     * <p>{@code propertyName} follows the same reasoning as {@code unitNumber}, and for
     * the same reason: it is the building's display name ({@code Property.nameEn}), and
     * a guard has no other way to get one — {@code PropertyController} is
     * admin/manager-only. Without it a guard covering two properties reads a raw id
     * fragment as a heading, which is a discriminator, not a name. It is neither a
     * credential nor renter identity nor financial data, so it is in scope for this
     * view; the property's actual sensitive fields ({@code fixedExpenses},
     * {@code makaniNumber}, address) stay out of every guard-facing payload — see
     * {@link GuardProperty}.
     */
    public record GatePassSummary(UUID id, UUID propertyId, String propertyName, UUID unitId, String unitNumber,
                                  String guestName, String guestPhone, String purpose, String vehicleNumber,
                                  GatePassType passType, Instant validFrom, Instant validTo, GatePassStatus status,
                                  Instant createdAt) {
    }

    /**
     * One property a guard is posted to — <b>id and display name, nothing else</b>.
     *
     * <p><b>This record is a security boundary.</b> It is the only property-shaped
     * payload the Security role can reach, and it exists so the guard app can tell "no
     * properties assigned" from "a quiet day" — both of which {@code expected-today}
     * answers with {@code []}.
     *
     * <p>Deliberately NOT the {@code Property} entity, and deliberately not widened:
     * SOW §3.1 requires the Security role never reach tenant financial or private
     * information, and {@code Property} carries {@code fixedExpenses}, {@code makaniNumber}
     * and address data — none of which a guard needs to know which gate they are on.
     * Adding a field here is a security decision, not a formatting one;
     * {@code GatePassControllerTest.myPropertiesCarriesNoFinancialOrPrivatePropertyFields}
     * asserts the serialized JSON keys and will fail if this grows.
     */
    public record GuardProperty(UUID id, String name) {
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

    /**
     * One scan joined to its pass — a row of the manager gate-traffic report, shaped
     * for CSV export.
     *
     * <p>{@code scannedByName} carries the guard's display name alongside
     * {@code scannedByUserId}, and both stay: "who scanned this" is the question an
     * audit report exists to answer, and the id alone cannot answer it for the role
     * that most needs it. A PROPERTY_MANAGER is explicitly allowed on this report but
     * cannot resolve a user id — {@code /api/admin/users} is SUPER_ADMIN/TENANT_ADMIN
     * only — so before this field the manager's report displayed a UUID fragment and
     * had no way to turn it into a person. The id remains because names are not
     * unique and the CSV is an audit artifact that has to be joinable.
     *
     * <p>Null when the user row is gone or outside the tenant; clients fall back to
     * the id. See {@code GatePassController.guardNames}.
     */
    public record GatePassReportRow(UUID scanId, Instant scannedAt, ScanDirection direction, ScanResult result,
                                    String rejectionReason, UUID scannedByUserId, String scannedByName,
                                    UUID gatePassId, UUID propertyId,
                                    String unitNumber, String guestName, String guestPhone, String vehicleNumber,
                                    String purpose, GatePassType passType) {
    }
}
