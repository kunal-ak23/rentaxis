// Harness: logs in with --dart-define credentials and persists the session
// (secure storage), so a subsequently launched normal build starts signed in.
// Used for local-stack E2E runs; carries no credentials itself.
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('SEED_EMAIL');
const _password = String.fromEnvironment('SEED_PASSWORD');

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('seed session', (tester) async {
    expect(_email, isNotEmpty, reason: 'pass --dart-define=SEED_EMAIL=...');
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final ok = await container
        .read(authProvider.notifier)
        .login(_email, _password);
    expect(ok, isTrue, reason: 'login failed for $_email');
  });
}
