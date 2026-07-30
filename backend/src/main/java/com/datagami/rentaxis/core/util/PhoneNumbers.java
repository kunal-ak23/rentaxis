package com.datagami.rentaxis.core.util;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.UserRole;

import java.util.regex.Pattern;

/**
 * The single definition of what a stored phone number looks like, shared by
 * every path that writes one and by the OTP login that reads one back.
 *
 * <h2>Why this is not private to one service</h2>
 * It used to be. The original OTP service normalized the <i>login input</i> and
 * then matched it against {@code users.phone_number}, which
 * {@code UserService.createUser} stored <i>verbatim</i>. A guard provisioned as
 * {@code "+971 50 123 4567"} was therefore unreachable forever: login normalized
 * to {@code "+971501234567"}, the stored string kept its spaces, and no row
 * matched. Nothing failed loudly, so the manager saw success while the guard
 * remained unable to log in.
 *
 * <p>A normalizer that only one side of a comparison uses is not a normalizer.
 * Both sides now call into here, so read and write cannot drift apart again: the
 * rules live in one place and are tested in one place ({@code PhoneNumbersTest}).
 * Anything that persists a phone must go through {@link #normalizeForRole}.
 */
public final class PhoneNumbers {

    /** E.164: a leading '+' then 8-15 digits. Applied after stripping spaces/hyphens. */
    private static final Pattern E164 = Pattern.compile("\\+\\d{8,15}");

    private PhoneNumbers() {
    }

    /**
     * Strips spaces and hyphens; maps blank to {@code null}.
     *
     * <p>Lossless in the only sense that matters here — it removes presentation,
     * never digits — so it is safe to apply to every role. Blank collapses to
     * {@code null} deliberately: {@code phone_number} is nullable, and
     * {@code uq_users_guard_phone} treats NULLs as distinct while it would treat
     * two empty strings as a collision.
     */
    public static String compact(String phone) {
        if (phone == null) {
            return null;
        }
        String compacted = phone.replaceAll("[\\s-]", "");
        return compacted.isEmpty() ? null : compacted;
    }

    /**
     * Compacts and requires E.164. For input that must be a dialable number —
     * i.e. the OTP login, where the phone <i>is</i> the identifier.
     *
     * @throws BusinessRuleViolationException if absent or not E.164 (400)
     */
    public static String toE164(String phone) {
        String compacted = compact(phone);
        if (compacted == null) {
            throw new BusinessRuleViolationException("Phone number is required");
        }
        if (!E164.matcher(compacted).matches()) {
            throw new BusinessRuleViolationException(
                    "Phone number must be in international format, e.g. +971501234567");
        }
        return compacted;
    }

    /**
     * As {@link #toE164}, but tolerates absence: {@code null}/blank in, {@code null}
     * out. For write paths where the phone is optional but must be dialable when
     * supplied.
     *
     * @throws BusinessRuleViolationException if present and not E.164 (400)
     */
    public static String toE164OrNull(String phone) {
        String compacted = compact(phone);
        return compacted == null ? null : toE164(compacted);
    }

    /**
     * The write-side rule, role-aware. Call this from anything that persists
     * {@code users.phone_number}.
     *
     * <h2>Why the rule is split by role rather than uniform</h2>
     * Enforcing E.164 on everyone would be the tidier rule and is the wrong one:
     * it would start rejecting user creation that works today. Renters are
     * provisioned by {@code RenterService} straight from operator-typed input
     * ({@code dto.getPhone()}), and local UAE formats like {@code "050 8831786"}
     * — no country code at all — are normal in that data. A renter's phone is
     * display metadata; a malformed one is cosmetic.
     *
     * <p>For a SECURITY_GUARD it is not cosmetic. The phone is the guard's
     * <i>only</i> credential and the only key OTP login can resolve them by, so a
     * malformed one is a silently dead account — the exact failure documented on
     * this class. Guards are the one role where rejecting at the door is kinder
     * than storing it, because the alternative is a 200, no code, and no trace.
     *
     * <p>So: everyone gets whitespace/hyphens stripped (safe, lossless), and only
     * guards must additionally be E.164 — <b>when a phone is given</b>. A guard
     * with no phone stays creatable: {@code createUser} has always allowed it and
     * the role-parameterized membership tests provision guards with a null phone.
     * That case is already unreachable-but-harmless (login rejects a null phone
     * before any lookup), and closing it belongs with the API contract, not here.
     *
     * @throws BusinessRuleViolationException if the role is SECURITY_GUARD and the
     *                                        supplied phone is not E.164 (400)
     */
    public static String normalizeForRole(String phone, UserRole role) {
        return role == UserRole.SECURITY_GUARD ? toE164OrNull(phone) : compact(phone);
    }
}
