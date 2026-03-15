import 'package:dio/dio.dart';
import '../../models/auth_response.dart';

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
}
