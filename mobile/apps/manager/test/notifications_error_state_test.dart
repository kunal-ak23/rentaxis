import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/notifications_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// A failed notification fetch must not read as an empty inbox.
///
/// NotificationState.hasError exists specifically for this — its own doc
/// comment says it lets screens "distinguish 'no notifications' from 'couldn't
/// load them'" — but the screen never referenced it. On a 401, a 500 or a
/// dropped connection the list was empty, so it rendered
/// "No Notifications / You're all caught up!": a success message on an error
/// path, with no retry and no way to tell that pending gate-pass, ticket or
/// payment alerts had failed to load.
class _FixedNotifications extends StateNotifier<NotificationState>
    implements NotificationNotifier {
  _FixedNotifications(super.state);

  // The screen fetches on mount and on refresh. Both are no-ops here: the
  // point is to pin what the given state renders as, not to re-fetch.
  @override
  Future<void> fetchNotifications({int page = 0, int size = 20}) async {}

  @override
  Future<void> fetchUnreadCount() async {}

  @override
  Future<void> markAllAsRead() async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Widget _host(NotificationState state) {
  return ProviderScope(
    overrides: [
      notificationProvider.overrideWith((ref) => _FixedNotifications(state)),
    ],
    child: const MaterialApp(home: NotificationsScreen()),
  );
}

void main() {
  testWidgets('a failed fetch shows an error, not "all caught up"',
      (tester) async {
    await tester.pumpWidget(
      _host(const NotificationState(hasError: true, isLoading: false)),
    );
    await tester.pump();

    expect(find.textContaining("Couldn't load notifications"), findsWidgets);
    expect(find.text('NO NOTIFICATIONS'), findsNothing);
    expect(find.text("You're all caught up!"), findsNothing);
  });

  testWidgets('a genuinely empty inbox still says all caught up',
      (tester) async {
    await tester.pumpWidget(
      _host(const NotificationState(hasError: false, isLoading: false)),
    );
    await tester.pump();

    // The distinction the fix exists for: empty is not an error.
    // EmptyState uppercases its title.
    expect(find.text('NO NOTIFICATIONS'), findsWidgets);
    expect(find.textContaining("Couldn't load notifications"), findsNothing);
  });
}
