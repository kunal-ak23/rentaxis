import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:renter/screens/profile_screen.dart';

import 'support/harness.dart';

/// App Store Review Guideline 5.1.1(v) and the legal links every app must
/// carry: the profile screen has to offer account deletion and the privacy /
/// terms / data-deletion pages in-app, not only on the website.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpProfile(WidgetTester tester) => pumpScreen(
    tester,
    initialLocation: '/profile',
    routes: [
      GoRoute(
        path: '/profile',
        builder: (context, state) => const ProfileScreen(),
      ),
    ],
    // The screen is a lazily built ListView; a tall surface keeps the rows
    // near the bottom in existence for `find`.
    surfaceSize: const Size(800, 2600),
  );

  testWidgets('offers the legal pages and account deletion', (tester) async {
    await pumpProfile(tester);

    expect(find.text('Privacy Policy'), findsOneWidget);
    expect(find.text('Terms of Use'), findsOneWidget);
    expect(find.text('Account & data deletion'), findsOneWidget);
    expect(find.text('Delete account'), findsOneWidget);
    // Sign out is still there and distinct from deletion.
    expect(find.text('SIGN OUT'), findsOneWidget);
  });

  testWidgets('deletion asks before doing anything', (tester) async {
    await pumpProfile(tester);

    await tester.tap(find.text('Delete account'));
    await tester.pumpAndSettle();

    expect(find.textContaining('cannot be undone'), findsOneWidget);
    expect(find.text('Cancel'), findsOneWidget);
  });
}
