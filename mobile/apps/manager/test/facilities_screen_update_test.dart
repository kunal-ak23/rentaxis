import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/providers/facility_provider.dart';
import 'package:manager/providers/gate_pass_provider.dart' show propertiesProvider;
import 'package:manager/screens/facilities/facilities_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_facility_service.dart';

/// Covers the update-payload seam fixed alongside PR-86's merge-gate review:
/// an edit must send cleared optional fields as `""` (not omit them — the
/// backend's patch semantics read "absent" as "unchanged") and must be able
/// to flip a row back to inactive from the edit sheet.
void main() {
  final property = {'id': 'prop-1', 'name': 'Marina Heights'};

  final amenity = <String, dynamic>{
    'id': 'am-1',
    'propertyId': 'prop-1',
    'nameEn': 'Pool',
    'nameAr': 'مسبح',
    'description': 'Rooftop pool',
    'bookable': true,
    'active': true,
    'buildingIds': <String>[],
  };

  Future<FakeFacilityService> pumpScreen(
    WidgetTester tester, {
    required FakeFacilityService fake,
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          facilityServiceProvider.overrideWithValue(fake),
          propertiesProvider.overrideWith((ref) async => [property]),
          // The towers chip list depends on a real network call this test
          // has no interest in exercising — pin it to "no towers" so the
          // sheet renders instantly instead of waiting on/racing a doomed
          // HTTP request.
          buildingsProvider(
            'prop-1',
          ).overrideWith((ref) async => <Map<String, dynamic>>[]),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const FacilitiesScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return fake;
  }

  testWidgets(
    'editing an amenity with a cleared Arabic name and Active switched off '
    'sends both as explicit values, not omitted',
    (tester) async {
      final fake = await pumpScreen(
        tester,
        fake: FakeFacilityService(amenities: [amenity]),
      );

      // Open the edit sheet via the card.
      await tester.tap(find.text('Pool'));
      await tester.pumpAndSettle();

      // Blank out the Arabic name (it starts prefilled from `amenity`).
      await tester.enterText(find.byKey(const Key('amenity-name-ar')), '');

      // Flip Active off — only present in edit mode.
      expect(find.byKey(const Key('amenity-active')), findsOneWidget);
      await tester.tap(find.byKey(const Key('amenity-active')));
      await tester.pump();

      // The sheet's content can exceed the test viewport, leaving Save
      // below the fold — scroll it into view before tapping.
      await tester.ensureVisible(find.byKey(const Key('amenity-save')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('amenity-save')));
      await tester.pumpAndSettle();

      expect(fake.updateCalls, hasLength(1));
      final (id, body) = fake.updateCalls.single;
      expect(id, 'am-1');
      // Empty string present in the payload — not omitted — is exactly the
      // "clear it" signal the backend's patch semantics require.
      expect(body['nameAr'], '');
      expect(body['active'], false);
    },
  );
}
