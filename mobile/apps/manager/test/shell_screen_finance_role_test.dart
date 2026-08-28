import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/shell_screen.dart';

void main() {
  test('tenant and super admins open the full Finance ledger', () {
    expect(managerFinanceRouteForRole('TENANT_ADMIN'), '/finance');
    expect(managerFinanceRouteForRole('SUPER_ADMIN'), '/finance');
  });

  test('property managers open permitted payment operations', () {
    expect(managerFinanceRouteForRole('PROPERTY_MANAGER'), '/payments');
  });

  test('both Finance destinations select the Finance navigation item', () {
    expect(managerShellIndexForLocation('/finance'), 2);
    expect(managerShellIndexForLocation('/finance-reports'), 2);
    expect(managerShellIndexForLocation('/payments'), 2);
    expect(managerShellIndexForLocation('/portfolio-pnl'), 2);
  });
}
