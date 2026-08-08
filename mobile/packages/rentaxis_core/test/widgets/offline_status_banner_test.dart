import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  Widget testApp({
    required Stream<List<ConnectivityResult>> changes,
    required List<ConnectivityResult> initial,
    TextDirection textDirection = TextDirection.ltr,
  }) {
    return MaterialApp(
      home: Directionality(
        textDirection: textDirection,
        child: OfflineStatusBanner(
          connectivityChanges: changes,
          checkConnectivity: () async => initial,
          child: const Scaffold(body: Text('Application content')),
        ),
      ),
    );
  }

  testWidgets('shows the banner when the app starts offline', (tester) async {
    final changes = StreamController<List<ConnectivityResult>>();
    addTearDown(changes.close);

    await tester.pumpWidget(
      testApp(changes: changes.stream, initial: [ConnectivityResult.none]),
    );
    await tester.pump();

    expect(find.byKey(offlineStatusBannerKey), findsOneWidget);
    expect(
      find.text('You’re offline. Some features may be unavailable.'),
      findsOneWidget,
    );
    expect(find.text('Application content'), findsOneWidget);
  });

  testWidgets('appears and disappears as connectivity changes', (tester) async {
    final changes = StreamController<List<ConnectivityResult>>();
    addTearDown(changes.close);

    await tester.pumpWidget(
      testApp(changes: changes.stream, initial: [ConnectivityResult.wifi]),
    );
    await tester.pump();
    expect(find.byKey(offlineStatusBannerKey), findsNothing);

    changes.add([ConnectivityResult.none]);
    await tester.pump();
    await tester.pump();
    expect(find.byKey(offlineStatusBannerKey), findsOneWidget);

    changes.add([ConnectivityResult.mobile]);
    await tester.pump();
    await tester.pump();
    expect(find.byKey(offlineStatusBannerKey), findsNothing);
  });

  testWidgets('uses Arabic copy in a right-to-left app', (tester) async {
    final changes = StreamController<List<ConnectivityResult>>();
    addTearDown(changes.close);

    await tester.pumpWidget(
      testApp(
        changes: changes.stream,
        initial: [ConnectivityResult.none],
        textDirection: TextDirection.rtl,
      ),
    );
    await tester.pump();

    expect(
      find.text('أنت غير متصل بالإنترنت. قد لا تتوفر بعض الميزات.'),
      findsOneWidget,
    );
  });
}
