import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/models/auth_response.dart';

void main() {
  group('AuthResponse.fromJson token field', () {
    test('parses the token when the backend returns one', () {
      final response = AuthResponse.fromJson({
        'id': '123',
        'email': 'test@example.com',
        'name': 'Test User',
        'role': 'RENTER',
        'tenantId': 'tenant-1',
        'token': 'jwt-abc',
      });

      expect(response.token, 'jwt-abc');
      expect(response.id, '123');
      expect(response.role, 'RENTER');
    });

    test('tolerates a missing token (old backend without JWT support)', () {
      final response = AuthResponse.fromJson({
        'id': '123',
        'email': 'test@example.com',
        'name': 'Test User',
        'role': 'RENTER',
        'tenantId': 'tenant-1',
      });

      expect(response.token, isNull);
      expect(response.id, '123');
    });
  });
}
