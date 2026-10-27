import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/shell_screen.dart';
import 'package:renter/screens/ticket_detail_screen.dart';

class _QuietNotifications extends NotificationNotifier {
  _QuietNotifications() : super(NotificationApiService(ApiClient().dio));

  @override
  Future<void> fetchNotifications({int page = 0, int size = 20}) async {}

  @override
  Future<void> fetchUnreadCount() async {}
}

/// Answers every request locally: an open ticket with no replies.
ApiClient _fakeApi() {
  final client = ApiClient();
  client.dio.interceptors
    ..clear()
    ..add(
      InterceptorsWrapper(
        onRequest: (options, handler) => handler.resolve(
          Response(
            requestOptions: options,
            statusCode: 200,
            data: options.path == '/v1/tickets/t1'
                ? {'id': 't1', 'title': 'Leaking tap', 'status': 'OPEN'}
                : [],
          ),
        ),
      ),
    );
  return client;
}

void main() {
  testWidgets('the update composer sits clear of the raised Pass button', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(390, 844));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(_fakeApi()),
          notificationProvider.overrideWith((ref) => _QuietNotifications()),
        ],
        child: MaterialApp.router(
          theme: MiftahTheme.light,
          routerConfig: GoRouter(
            initialLocation: '/tickets/t1',
            routes: [
              ShellRoute(
                builder: (_, _, child) => ShellScreen(child: child),
                routes: [
                  GoRoute(
                    path: '/tickets/:id',
                    builder: (_, state) => TicketDetailScreen(
                      ticketId: state.pathParameters['id']!,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final composer = find.ancestor(
      of: find.byType(TextField),
      matching: find.byType(Row),
    );
    final passButton = find.descendant(
      of: find.byType(MiftahNavBar),
      matching: find.byWidgetPredicate(
        (w) =>
            w is Container &&
            w.decoration is BoxDecoration &&
            (w.decoration as BoxDecoration).gradient ==
                MiftahGradients.goldCompact,
      ),
    );
    expect(composer, findsWidgets);
    expect(passButton, findsOneWidget);

    final composerRect = tester.getRect(composer.first);
    final passRect = tester.getRect(passButton);
    expect(
      composerRect.overlaps(passRect),
      isFalse,
      reason: 'composer $composerRect vs Pass button $passRect',
    );
  });
}
