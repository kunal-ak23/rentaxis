import 'package:dio/dio.dart';
import '../../models/auth_response.dart';

/// Thrown by [AuthService.validateInviteToken] when the backend returns 410
/// (invite token has expired).
class InviteExpiredException implements Exception {
  const InviteExpiredException();
  @override
  String toString() => 'InviteExpiredException: invite token has expired';
}

class AuthService {
  final Dio _dio;
  AuthService(this._dio);

  /// Password login. [tenantId] disambiguates when the same email + password
  /// exists in multiple tenants: the backend answers such a login with 409 and
  /// a `{tenants: [{tenantId, tenantName}]}` body, expecting the client to
  /// re-submit with the chosen [tenantId].
  Future<AuthResponse> login(
    String email,
    String password, {
    String? tenantId,
  }) async {
    final response = await _dio.post(
      '/auth/login',
      data: {'email': email, 'password': password, 'tenantId': ?tenantId},
    );
    return AuthResponse.fromJson(response.data);
  }

  /// Exchanges a Firebase Phone Authentication ID token for a RentAxis guard
  /// session. Firebase has already verified the SMS code on the device; the
  /// backend independently verifies this signed token before trusting its
  /// phone-number claim.
  Future<AuthResponse> loginWithFirebase(String idToken) async {
    final response = await _dio.post(
      '/v1/auth/firebase',
      data: {'idToken': idToken},
    );
    return AuthResponse.fromJson(response.data);
  }

  /// Exchanges a native Sign in with Apple identity token for a RentAxis
  /// Resident/Manager session. The raw nonce is verified against the signed
  /// nonce claim by the backend before the Apple identity can be linked.
  Future<AuthResponse> loginWithApple(
    String identityToken,
    String rawNonce, {
    String? tenantId,
    // The credential's one-time code. The backend exchanges it for the
    // refresh token that account deletion revokes (App Store Guideline
    // 5.1.1(v)); omitted when the platform did not return one.
    String? authorizationCode,
  }) async {
    final response = await _dio.post(
      '/auth/apple',
      data: {
        'identityToken': identityToken,
        'nonce': rawNonce,
        'tenantId': ?tenantId,
        'authorizationCode': ?authorizationCode,
      },
    );
    return AuthResponse.fromJson(response.data);
  }

  /// Deletes the signed-in user's own account. DELETE /v1/account acts on the
  /// principal the backend verified — there is nothing to pass. 204 on
  /// success; 400 with a `message` when the backend refuses (sole
  /// administrator), 403 for accounts that cannot be deleted from the app.
  Future<void> deleteAccount() async {
    await _dio.delete('/v1/account');
  }

  Future<Map<String, dynamic>> getProfile() async {
    final response = await _dio.get('/auth/me');
    return response.data;
  }

  /// Updates the caller's profile. `null` means "leave unchanged" — the key is
  /// omitted and the backend skips the field. To CLEAR the stored phone number
  /// pass an empty string: PUT /auth/me normalizes blank to null server-side
  /// (PhoneNumbers.compact), which is how the web client clears it too.
  Future<void> updateProfile({String? name, String? phoneNumber}) async {
    await _dio.put(
      '/auth/me',
      data: {'name': ?name, 'phoneNumber': ?phoneNumber},
    );
  }

  /// Changes the caller's password. The backend revokes every token the user
  /// holds, this device's included, and answers with a replacement `token`;
  /// the caller must store it (see [AuthNotifier.replaceAuthToken]) or the
  /// next request 401s and signs this device out too. Returns null when the
  /// backend issued none (token auth not configured).
  Future<String?> changePassword(
    String currentPassword,
    String newPassword,
  ) async {
    final response = await _dio.put(
      '/auth/me/password',
      data: {'currentPassword': currentPassword, 'newPassword': newPassword},
    );
    final data = response.data;
    return data is Map ? data['token'] as String? : null;
  }

  Future<List<dynamic>> getTenants() async {
    final response = await _dio.get('/auth/me/tenants');
    return response.data;
  }

  /// Validates an invite/set-password token.
  ///
  /// Returns the payload map (keys: `email`, `name`, `expiresAt`) on 200.
  /// Returns `null` on 404 (token not found).
  /// Throws [InviteExpiredException] on 410 (token expired).
  Future<Map<String, dynamic>?> validateInviteToken(String token) async {
    try {
      final response = await _dio.get(
        '/auth/set-password/validate',
        queryParameters: {'token': token},
      );
      return response.data as Map<String, dynamic>;
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      if (e.response?.statusCode == 410) throw const InviteExpiredException();
      rethrow;
    }
  }

  /// Submits a new password for the given invite token.
  ///
  /// Returns the raw HTTP status code so the caller can map:
  ///   204 → success, 400 → bad request, 409 → already used, 410 → expired.
  /// Never throws on a non-2xx response from the server.
  Future<int> acceptInvite({
    required String token,
    required String newPassword,
  }) async {
    try {
      final response = await _dio.post(
        '/auth/set-password',
        data: {'token': token, 'newPassword': newPassword},
      );
      return response.statusCode ?? 200;
    } on DioException catch (e) {
      if (e.response != null) return e.response!.statusCode ?? 500;
      rethrow;
    }
  }
}
