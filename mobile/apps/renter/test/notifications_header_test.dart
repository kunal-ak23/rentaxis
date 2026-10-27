import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/notifications_screen.dart';

/// A notifier that never reaches the network: the header is what's under test.
class _QuietNotifications extends NotificationNotifier {
  _QuietNotifications() : super(NotificationApiService(ApiClient().dio));

  @override
  Future<void> fetchNotifications({int page = 0, int size = 20}) async {}

  @override
  Future<void> fetchUnreadCount() async {}
}

void main() {
  testWidgets('the navy header shows a white back arrow and title', (
    tester,
  ) async {
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (context, _) => Scaffold(
            body: TextButton(
              onPressed: () => context.push('/notifications'),
              child: const Text('open'),
            ),
          ),
        ),
        GoRoute(
          path: '/notifications',
          builder: (_, _) => const NotificationsScreen(),
        ),
      ],
    );
    // The app's own theme, whose AppBar title and icons are ink.
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          notificationProvider.overrideWith((ref) => _QuietNotifications()),
        ],
        child: MaterialApp.router(
          theme: MiftahTheme.light,
          routerConfig: router,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    final back = find.byType(BackButton);
    expect(back, findsOneWidget);
    expect(
      IconTheme.of(tester.element(find.byType(BackButtonIcon))).color,
      Colors.white,
    );
    final title = tester.element(find.text('Notifications'));
    expect(DefaultTextStyle.of(title).style.color, Colors.white);
  });
}
