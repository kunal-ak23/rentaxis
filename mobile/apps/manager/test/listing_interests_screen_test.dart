import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/listings/listing_interests_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Canned [ListingApiService] serving a fixed interests page. Only
/// `getInterests` is exercised by the screen; everything else inherits the
/// real implementation (never called — the Dio has no adapter to reach).
class _FakeListingApi extends ListingApiService {
  _FakeListingApi({required this.rows, required this.total}) : super(Dio());

  final List<Map<String, dynamic>> rows;
  final int total;

  @override
  Future<Map<String, dynamic>> getInterests(
    String listingId, {
    int page = 0,
    int size = 20,
  }) async => {'content': rows, 'totalElements': total};
}

Map<String, dynamic> _interest(int i) => {
  'id': 'int-$i',
  'renterName': 'Renter $i',
  'renterEmail': 'renter$i@example.com',
  'renterPhone': '+97150000000$i',
  'note': null,
  'status': 'ACTIVE',
  'createdAt': '2026-08-01T10:00:00Z',
};

void main() {
  Future<void> pumpScreen(WidgetTester tester, _FakeListingApi fake) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [listingApiServiceProvider.overrideWithValue(fake)],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const ListingInterestsScreen(listingId: 'lst-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'truncated page shows the backend total and a showing-first note',
    (tester) async {
      // Backend reports more interests than the single fetched page holds —
      // the header must show the real total, not the page length, and the
      // list must say it is cut off.
      final fake = _FakeListingApi(
        rows: [for (var i = 0; i < 3; i++) _interest(i)],
        total: 5,
      );
      await pumpScreen(tester, fake);

      expect(find.text('Renter 0'), findsOneWidget);
      expect(find.text('5'), findsOneWidget);
      expect(find.text('3'), findsNothing);
      expect(find.text('Showing first 3 interests'), findsOneWidget);
    },
  );

  testWidgets('complete page shows no truncation note', (tester) async {
    final fake = _FakeListingApi(
      rows: [for (var i = 0; i < 2; i++) _interest(i)],
      total: 2,
    );
    await pumpScreen(tester, fake);

    expect(find.text('Renter 1'), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    expect(find.textContaining('Showing first'), findsNothing);
  });
}
