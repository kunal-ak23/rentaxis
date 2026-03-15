import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  test('AuthResponse parses from JSON', () {
    final json = {
      'id': '123',
      'email': 'test@example.com',
      'name': 'Test User',
      'role': 'RENTER',
      'tenantId': 'tenant-1',
    };
    final response = AuthResponse.fromJson(json);
    expect(response.id, '123');
    expect(response.email, 'test@example.com');
    expect(response.role, 'RENTER');
  });

  test('Formatters.currency formats AED correctly', () {
    final result = Formatters.currency(1500);
    expect(result, contains('1,500'));
    expect(result, contains('AED'));
  });

  test('StatusHelper returns correct colors for lease statuses', () {
    expect(StatusHelper.getLeaseStatusColor('ACTIVE'), AppColors.statusActive);
    expect(StatusHelper.getLeaseStatusColor('DRAFT'), AppColors.statusDraft);
    expect(
        StatusHelper.getLeaseStatusColor('TERMINATED'), AppColors.danger);
  });
}
