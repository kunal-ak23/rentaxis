import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:renter/gatepass/pass_share_card.dart';

/// The card is what the guest actually receives, so these assert its contents
/// rather than its styling: a wrong or missing entry code is the failure that
/// leaves someone standing at a gate.
void main() {
  const pass = <String, dynamic>{
    'guestName': 'Nour Al Sayed',
    'numericCode': '482913',
    'qrToken': 'tok_abc123',
    'validFrom': '2026-08-20T18:00:00Z',
    'validTo': '2026-08-20T23:00:00Z',
  };

  // The card is ~550pt tall — taller than the default 800x600 test viewport,
  // so give it unbounded height rather than asserting against an overflow.
  Widget host(Widget child) => MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(
        child: Align(alignment: Alignment.topCenter, child: child),
      ),
    ),
  );

  testWidgets('carries guest, code and place', (tester) async {
    await tester.pumpWidget(
      host(
        const MiftahPassShareCard(
          pass: pass,
          propertyName: 'Marsa Residences',
          unitIdentifier: '1204',
          ar: false,
          strings: PassShareStrings(false),
        ),
      ),
    );

    expect(find.text('Nour Al Sayed'), findsOneWidget);
    expect(find.text('482913'), findsOneWidget);
    expect(find.text('Marsa Residences · Unit 1204'), findsOneWidget);
    expect(find.text('GATE PASS'), findsOneWidget);
  });

  testWidgets('omits the place line when the lease no longer resolves', (
    tester,
  ) async {
    await tester.pumpWidget(
      host(
        const MiftahPassShareCard(
          pass: pass,
          propertyName: null,
          unitIdentifier: null,
          ar: false,
          strings: PassShareStrings(false),
        ),
      ),
    );

    // The card still sends — it just does not claim an address it cannot name.
    expect(find.text('Where'), findsNothing);
    expect(find.text('482913'), findsOneWidget);
  });

  testWidgets('renders in Arabic without Latin labels', (tester) async {
    await tester.pumpWidget(
      host(
        const MiftahPassShareCard(
          pass: pass,
          propertyName: 'مرسى',
          unitIdentifier: '1204',
          ar: true,
          strings: PassShareStrings(true),
        ),
      ),
    );

    expect(find.text('تصريح دخول'), findsOneWidget);
    expect(find.text('GATE PASS'), findsNothing);
    expect(find.text('482913'), findsOneWidget);
  });

  testWidgets('paints to a real PNG', (tester) async {
    // Covers the risky half of sharing: that the card actually rasterises.
    // The Overlay plumbing in renderPassShareCard is exercised on device, not
    // here - a test cannot pump frames while awaiting its own capture future.
    final key = GlobalKey();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: RepaintBoundary(
              key: key,
              child: const MiftahPassShareCard(
                pass: pass,
                propertyName: 'Marsa Residences',
                unitIdentifier: '1204',
                ar: false,
                strings: PassShareStrings(false),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final boundary =
        key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    // runAsync: rasterisation happens off the fake-async clock, so awaiting
    // toImage on the test's own zone never completes.
    final bytes = await tester.runAsync(() async {
      final image = await boundary.toImage(pixelRatio: 1);
      final data = await image.toByteData(format: ui.ImageByteFormat.png);
      image.dispose();
      return data?.buffer.asUint8List();
    });

    expect(bytes, isNotNull);
    expect(bytes!.length, greaterThan(1000));
    // PNG magic number - proves an image, not an empty buffer.
    expect(bytes.sublist(0, 4), [0x89, 0x50, 0x4E, 0x47]);
  });
}
