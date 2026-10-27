import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/notifications_screen.dart';

/// Holds one already-read row and never reaches the network.
class _OneNotification extends NotificationNotifier {
  _OneNotification(Map<String, dynamic> row)
    : super(NotificationApiService(ApiClient().dio)) {
    state = NotificationState(notifications: [row]);
  }

  @override
  Future<void> fetchNotifications({int page = 0, int size = 20}) async {}

  @override
  Future<void> fetchUnreadCount() async {}
}

Future<void> _tapRow(WidgetTester tester, String type) async {
  final router = GoRouter(
    initialLocation: '/notifications',
    routes: [
      GoRoute(
        path: '/notifications',
        builder: (_, _) => const NotificationsScreen(),
      ),
      GoRoute(path: '/renewals', builder: (_, _) => const Text('RENEWALS')),
      GoRoute(path: '/contracts', builder: (_, _) => const Text('CONTRACTS')),
    ],
  );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        notificationProvider.overrideWith(
          (ref) => _OneNotification({
            'id': 'n1',
            'type': type,
            'title': 'Row',
            'referenceType': 'LEASE',
            'referenceId': 'lease-1',
            'isRead': true,
          }),
        ),
      ],
      child: MaterialApp.router(
        theme: MiftahTheme.light,
        routerConfig: router,
      ),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text('Row').first);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a renewal reminder opens Renewals, not Contracts', (
    tester,
  ) async {
    await _tapRow(tester, 'LEASE_RENEWAL_REMINDER');
    expect(find.text('RENEWALS'), findsOneWidget);
    expect(find.text('CONTRACTS'), findsNothing);
  });

  testWidgets('other lease notifications still open Contracts', (
    tester,
  ) async {
    await _tapRow(tester, 'LEASE_UPDATE');
    expect(find.text('CONTRACTS'), findsOneWidget);
  });
}
