import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/ticket_detail_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pins the activity-history rows to TicketHistoryDTO's field names: the
/// human-readable text is `notes` (e.g. 'Status changed: X → Y'), not
/// `description` (which the DTO does not have), with `action` as the raw-code
/// fallback, and `performedByName` shown alongside the timestamp — matching
/// the web activity list (tickets/[id]/page.tsx renders `h.notes || h.action`
/// and `by {h.performedByName}`).
class _FakeTicketApi implements HttpClientAdapter {
  _FakeTicketApi({required this.history});

  final List<Map<String, dynamic>> history;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.path;
    if (path.endsWith('/v1/tickets/ticket-1/replies')) return _json([]);
    if (path.endsWith('/v1/tickets/ticket-1/attachments')) return _json([]);
    if (path.endsWith('/v1/tickets/ticket-1/history')) return _json(history);
    if (path.endsWith('/v1/tickets/ticket-1')) {
      return _json({
        'id': 'ticket-1',
        'title': 'Leaking tap',
        'status': 'IN_PROGRESS',
        'priority': 'MEDIUM',
        'category': 'PLUMBING',
        'description': 'Kitchen tap drips constantly.',
        'createdAt': '2026-08-01T10:00:00Z',
      });
    }
    return ResponseBody.fromString('not found', 404);
  }

  ResponseBody _json(Object? body) => ResponseBody.fromString(
    jsonEncode(body),
    200,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

ApiClient _fakeClient(_FakeTicketApi api) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = api;
  return client;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpDetail(
    WidgetTester tester,
    List<Map<String, dynamic>> history,
  ) async {
    // Tall surface so the non-lazy assertions below see every list child.
    await tester.binding.setSurfaceSize(const Size(500, 3000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(
            _fakeClient(_FakeTicketApi(history: history)),
          ),
        ],
        child: const MaterialApp(
          home: TicketDetailScreen(ticketId: 'ticket-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('history row renders DTO notes and performedByName', (
    tester,
  ) async {
    await pumpDetail(tester, [
      {
        'id': 'h1',
        'ticketId': 'ticket-1',
        'action': 'STATUS_CHANGED',
        'fromStatus': 'OPEN',
        'toStatus': 'IN_PROGRESS',
        'performedByName': 'Amira Manager',
        'notes': 'Status changed: OPEN → IN_PROGRESS',
        'createdAt': '2026-08-01T12:00:00Z',
      },
    ]);

    // Positive control proves the screen (and history section) rendered.
    expect(find.text('ACTIVITY HISTORY'), findsOneWidget);

    // `notes` is the human-readable text — the old item['description'] read
    // always fell back to the raw action code.
    expect(find.text('Status changed: OPEN → IN_PROGRESS'), findsOneWidget);
    expect(find.text('STATUS_CHANGED'), findsNothing);

    // performedByName is shown on the meta line ('<name> · <time ago>').
    expect(find.textContaining('Amira Manager · '), findsOneWidget);
  });

  testWidgets('history row falls back to action when notes is null', (
    tester,
  ) async {
    await pumpDetail(tester, [
      {
        'id': 'h2',
        'ticketId': 'ticket-1',
        'action': 'CREATED',
        'performedByName': null,
        'notes': null,
        'createdAt': '2026-08-01T09:00:00Z',
      },
    ]);

    expect(find.text('CREATED'), findsOneWidget);
    // No name — the meta line must not render a dangling ' · ' separator.
    expect(find.textContaining(' · '), findsNothing);
  });
}
