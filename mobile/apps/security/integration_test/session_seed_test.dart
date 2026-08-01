// Harness: persists a guard session directly into secure storage (guards
// authenticate via Firebase phone OTP, which has no headless path), so a
// subsequently launched normal build starts signed in. Identity comes from
// --dart-define; carries no credentials itself.
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

const _userId = String.fromEnvironment('SEED_USER_ID');
const _tenantId = String.fromEnvironment('SEED_TENANT_ID');

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('seed guard session', (tester) async {
    expect(_userId, isNotEmpty, reason: 'pass --dart-define=SEED_USER_ID=...');
    expect(_tenantId, isNotEmpty,
        reason: 'pass --dart-define=SEED_TENANT_ID=...');
    const storage = FlutterSecureStorage();
    await storage.write(key: 'userId', value: _userId);
    await storage.write(key: 'userRole', value: 'SECURITY_GUARD');
    await storage.write(key: 'tenantId', value: _tenantId);
    await storage.write(key: 'userTenantId', value: _tenantId);
  });
}
