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

  Future<AuthResponse> login(String email, String password) async {
    final response = await _dio.post('/auth/login', data: {
      'email': email,
      'password': password,
    });
    return AuthResponse.fromJson(response.data);
  }

  /// Exchanges a Firebase Phone Authentication ID token for a RentAxis guard
  /// session. Firebase has already verified the SMS code on the device; the
  /// backend independently verifies this signed token before trusting its
  /// phone-number claim.
  Future<AuthResponse> loginWithFirebase(String idToken) async {
    final response = await _dio.post('/v1/auth/firebase', data: {
      'idToken': idToken,
    });
    return AuthResponse.fromJson(response.data);
  }

  Future<Map<String, dynamic>> getProfile() async {
    final response = await _dio.get('/auth/me');
    return response.data;
  }

  Future<void> updateProfile({String? name, String? phoneNumber}) async {
    await _dio.put('/auth/me', data: {
      if (name != null) 'name': name,
      if (phoneNumber != null) 'phoneNumber': phoneNumber,
    });
  }

  Future<void> changePassword(
      String currentPassword, String newPassword) async {
    await _dio.put('/auth/me/password', data: {
      'currentPassword': currentPassword,
      'newPassword': newPassword,
    });
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
