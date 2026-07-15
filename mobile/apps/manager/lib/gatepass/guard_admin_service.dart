/// Guard provisioning over the admin user endpoint.
///
/// **Why this lives in the manager app and not in `rentaxis_core`.** Core has no
/// wrapper for `/admin/users` at all — no app has ever called it, the web client
/// owns user administration today. Rather than open core's service surface for a
/// single screen, this stays app-local; if a second app ever provisions users,
/// this is the file to lift.
///
/// The path is `/admin/users`, not `/v1/admin/users`: `ApiClient.baseUrl` ends in
/// `/api` and `UserController` is mapped at `/api/admin/users`, outside the `/v1`
/// tree every other service in core sits in.
library;

import 'dart:math';

import 'package:dio/dio.dart';

/// `\+\d{8,15}` — the exact pattern `OtpLoginService.E164` enforces at login.
///
/// Kept identical on purpose. A guard is provisioned here and authenticates
/// there, and the two are joined by a raw string comparison:
/// `createUser` stores `phoneNumber` **verbatim**, with no normalization of its
/// own, while `OtpLoginService.normalize` strips spaces/dashes from the *login*
/// input and then looks the stored value up with `findByPhoneNumberAndRole`.
/// So a guard saved as `+971 50 123 4567` is never found by a login normalized
/// to `+971501234567`, and that guard can never sign in — silently, with no
/// error anywhere. [normalizeGuardPhone] closes that gap on the way in.
final RegExp _e164 = RegExp(r'^\+\d{8,15}$');

/// Strips the separators a human types, matching `OtpLoginService.normalize`.
String normalizeGuardPhone(String input) =>
    input.trim().replaceAll(RegExp(r'[\s-]'), '');

/// Validates a phone the way login will, so a guard cannot be created into a
/// state where they can never authenticate. Returns null when valid.
String? validateGuardPhone(String? input) {
  final raw = input?.trim() ?? '';
  if (raw.isEmpty) return 'Phone number is required';
  if (!_e164.hasMatch(normalizeGuardPhone(raw))) {
    return 'Use international format, e.g. +971501234567';
  }
  return null;
}

/// Email as `UserService.createUser` requires it.
///
/// It calls `email.toLowerCase().trim()` unguarded, so a null email is an NPE
/// and a 500 — the field is mandatory whether or not a guard has an address of
/// their own. Validated here so that arrives as a 400-free client-side message
/// instead.
String? validateGuardEmail(String? input) {
  final raw = input?.trim() ?? '';
  if (raw.isEmpty) return 'Email is required';
  if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(raw)) {
    return 'Enter a valid email';
  }
  return null;
}

/// A password the guard will never use, and no one will ever know.
///
/// `UserService.createUser` hashes `rawPassword` unconditionally, so the field
/// cannot be omitted — but a SECURITY_GUARD is the one role that never receives
/// an invite token (`issuesInviteToken` covers RENTER, PROPERTY_MANAGER and
/// TENANT_USER only), so no set-password mail is ever sent and there is no flow
/// through which a guard could learn or change one. Guards authenticate solely
/// by phone OTP.
///
/// Generating a random secret here is therefore the *safer* of the two options,
/// not a shortcut: the alternative — prompting the manager for a password —
/// would create a known credential for an account whose whole login path
/// bypasses passwords, i.e. a shared secret with no purpose and a real blast
/// radius. This value is discarded immediately and never displayed.
String generateUnusedGuardPassword() {
  const alphabet =
      'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*';
  final rng = Random.secure();
  return List.generate(32, (_) => alphabet[rng.nextInt(alphabet.length)])
      .join();
}

/// Thin wrapper over `/admin/users`, scoped to what guard management needs.
class GuardAdminService {
  final Dio _dio;
  GuardAdminService(this._dio);

  /// GET /admin/users — every user in the caller's tenant, all roles.
  ///
  /// There is no role filter on the endpoint, so the caller filters. Requires
  /// TENANT_ADMIN or SUPER_ADMIN: `UserController` is annotated at class level
  /// and a PROPERTY_MANAGER gets a 403 here — even though the same manager may
  /// legitimately *assign* guard properties via `GatePassController`.
  Future<List<dynamic>> users() async {
    final response = await _dio.get('/admin/users');
    return response.data as List<dynamic>;
  }

  /// POST /admin/users with `role=SECURITY_GUARD`.
  ///
  /// [phoneNumber] is normalized here rather than trusted from the form — see
  /// [_e164]. `tenantId` is deliberately not sent: for any caller below
  /// SUPER_ADMIN, `UserController.authorizeRoleAssignment` discards the
  /// submitted value and forces the caller's own tenant, and that forced tenant
  /// is what gives the new guard their `user_tenant_memberships` row
  /// (`getsTenantMembership` includes SECURITY_GUARD). Sending one would be
  /// ignored at best and misleading at worst.
  Future<Map<String, dynamic>> createGuard({
    required String name,
    required String email,
    required String phoneNumber,
  }) async {
    final response = await _dio.post('/admin/users', data: {
      'name': name.trim(),
      'email': email.trim(),
      'phoneNumber': normalizeGuardPhone(phoneNumber),
      'role': 'SECURITY_GUARD',
      'password': generateUnusedGuardPassword(),
    });
    return response.data as Map<String, dynamic>;
  }
}

/// Turns a failed guard creation into something a manager can act on.
///
/// The duplicate-phone case is the one that matters and the one the backend
/// words badly. `uq_users_guard_phone` is a **global** partial unique index on
/// `users(phone_number) WHERE role='SECURITY_GUARD'`, so a phone already held by
/// a guard — including one in another tenant, whom this manager cannot see —
/// fails at INSERT with a `DataIntegrityViolationException`. `createUser` catches
/// that and rethrows it as *"A user with this email already exists in this
/// tenant."*, because its only `existsBy` pre-check is on email. The message is
/// simply wrong for a phone collision, and nothing in the 400 distinguishes the
/// two.
///
/// So this deliberately names both fields instead of asserting the email one the
/// server guessed at. The screen pre-checks the visible guard list for a phone
/// clash before ever posting, which catches the same-tenant case precisely; this
/// covers what is left, where honest ambiguity beats a confident lie.
String describeGuardCreateFailure(Object error) {
  if (error is! DioException) {
    return 'Could not create the guard. Check the details and try again.';
  }

  final status = error.response?.statusCode;
  final data = error.response?.data;
  final serverMessage =
      (data is Map && data['message'] is String) ? (data['message'] as String).trim() : null;

  if (status == 403) {
    return 'Your account is not allowed to add guards. Ask a tenant admin to '
        'create the guard, then assign their properties here.';
  }
  if (status == 400) {
    if (serverMessage != null &&
        serverMessage.toLowerCase().contains('already exists')) {
      return 'That phone number or email is already registered. A guard phone '
          'number can belong to only one guard.';
    }
    if (serverMessage != null && serverMessage.isNotEmpty) return serverMessage;
  }
  return 'Could not create the guard. Check the details and try again.';
}
