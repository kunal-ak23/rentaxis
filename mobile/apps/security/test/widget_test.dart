import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_auth_service.dart';
import 'support/harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  testWidgets('SecurityApp boots to the login screen with no stored session', (
    tester,
  ) async {
    // Booted through the harness rather than a bare `ProviderScope(child:
    // SecurityApp())`. The bare version passed only while `/` was a
    // placeholder: the real home screen reads the gate, so an un-stubbed boot
    // leaves AuthNotifier waiting on a secure-storage channel that does not
    // exist and the visitors board spinning on a live HTTP call — and
    // pumpAndSettle waits out both. A smoke test that only passes while the app
    // does nothing is not a smoke test.
    await pumpSecurityApp(tester, authService: FakeAuthService());

    expect(find.byKey(const Key('phoneNationalField')), findsOneWidget);
  });
}
