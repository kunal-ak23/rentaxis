import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/shell_screen.dart';

/// The role rule is unchanged by MOBILE_FINANCE — it just sits on top of the
/// flag now, so every case here passes `financeEnabled: true`. The flag's own
/// behaviour is pinned in finance_gate_test.dart.
void main() {
  test('tenant and super admins open the full Finance ledger', () {
    expect(managerFinanceRouteForRole('TENANT_ADMIN', true), '/finance');
    expect(managerFinanceRouteForRole('SUPER_ADMIN', true), '/finance');
  });

  test('property managers open permitted payment operations', () {
    expect(managerFinanceRouteForRole('PROPERTY_MANAGER', true), '/payments');
  });

  test('both Finance destinations select the Finance navigation item', () {
    expect(managerShellIndexForLocation('/finance', financeEnabled: true), 2);
    expect(
      managerShellIndexForLocation('/finance-reports', financeEnabled: true),
      2,
    );
    expect(managerShellIndexForLocation('/payments', financeEnabled: true), 2);
    expect(
      managerShellIndexForLocation('/portfolio-pnl', financeEnabled: true),
      2,
    );
  });
}
