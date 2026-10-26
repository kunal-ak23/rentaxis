// Production renter regression tour. Credentials are supplied through private
// dart defines. Optional TOUR_BOOKINGS only creates/cancels free requests in
// the named sandbox tenant. Never approves bookings, pays, or signs contracts.
import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:integration_test/integration_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/app.dart';
import 'package:renter/router.dart';
import 'package:renter/providers/renter_data.dart';
import 'package:renter/providers/facility_provider.dart';
import 'package:renter/widgets/renter_document_button.dart';

Future<void> pumpFor(WidgetTester tester, [int seconds = 2]) async {
  final end = DateTime.now().add(Duration(seconds: seconds));
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> waitFor(
  WidgetTester tester,
  bool Function() ready,
  String description,
) async {
  final end = DateTime.now().add(const Duration(seconds: 30));
  while (!ready() && DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 200));
  }
  expect(ready(), isTrue, reason: description);
}

const emails = String.fromEnvironment('TOUR_EMAILS');
const password = String.fromEnvironment('TOUR_PASSWORD');

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  for (final email in emails.split(',')) {
    testWidgets('production renter tour ${email.split('@').first}', (
      tester,
    ) async {
      expect(
        ApiClient.defaultBaseUrl,
        'https://rentaxis.uaenorth.cloudapp.azure.com/api',
        reason: 'Renter simulator builds must always target production',
      );
      GoogleFonts.config.allowRuntimeFetching = false;
      final client = ApiClient();
      // Do not print login credentials, tokens, documents, or gate credentials.
      client.dio.interceptors.removeWhere((i) => i is LogInterceptor);
      final failures = <String>[];
      client.dio.interceptors.add(
        InterceptorsWrapper(
          onError: (e, handler) {
            failures.add(
              '${e.requestOptions.method} ${e.requestOptions.path}: ${e.response?.statusCode}',
            );
            handler.next(e);
          },
        ),
      );
      final downloaded = <String>[];
      final container = ProviderContainer(
        overrides: [
          apiClientProvider.overrideWithValue(client),
          shareRenterDocumentProvider.overrideWithValue((
            bytes,
            name,
            origin,
          ) async {
            expect(utf8.decode(bytes.take(4).toList()), '%PDF');
            downloaded.add(name);
          }),
        ],
      );
      addTearDown(container.dispose);
      expect(email, isNotEmpty);
      expect(password, isNotEmpty);
      container.read(authProvider);
      await tester.runAsync(() async {
        while (container.read(authProvider).isLoading) {
          await Future<void>.delayed(const Duration(milliseconds: 100));
        }
        await container.read(authProvider.notifier).logout();
        await container
            .read(appLanguageProvider.notifier)
            .setLanguage(AppLanguage.en);
      });
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const RenterApp(),
        ),
      );
      await waitFor(
        tester,
        () => find.byType(TextFormField).evaluate().length >= 2,
        'login form',
      );
      await tester.enterText(find.byType(TextFormField).at(0), email);
      await tester.enterText(find.byType(TextFormField).at(1), password);
      await tester.ensureVisible(find.text('SIGN IN'));
      await tester.tap(find.text('SIGN IN'));
      await waitFor(
        tester,
        () => container.read(authProvider).isAuthenticated,
        'production sandbox login',
      );
      expect(container.read(authProvider).role, 'RENTER');
      failures.clear();
      await pumpFor(tester, 3);

      Future<void> route(String path, String shot) async {
        container.read(routerProvider).go(path);
        await pumpFor(tester, 3);
        expect(
          find.byType(ErrorState),
          findsNothing,
          reason: '$path must load',
        );
        expect(tester.takeException(), isNull, reason: '$path layout');
        await binding.takeScreenshot('prod-${email.split('@').first}-$shot');
      }

      await route('/', '01-home');
      await route('/payments', '02-payments');
      final rows = await container.read(renterPaymentsProvider.future);
      debugPrint('Production payment rows: ${rows.length}');
      final due = rows
          .where((r) => r['due'] == true && (r['payable'] as num) > 0)
          .fold<num>(0, (n, r) => n + (r['payable'] as num));
      expect(
        tester.widget<Text>(find.byKey(const Key('wallet-due'))).data,
        Formatters.currency(due),
      );
      await tester.tap(find.widgetWithText(ChoiceChip, 'Cleared'));
      await pumpFor(tester);
      if (find.text('Receipt').evaluate().isNotEmpty) {
        await tester.ensureVisible(find.text('Receipt').first);
        await tester.tap(find.text('Receipt').first);
        await pumpFor(tester, 3);
        expect(downloaded.any((n) => n.startsWith('receipt-')), isTrue);
      }
      await route('/penalties', '03-penalties');
      debugPrint(
        'Production penalty rows: ${(await container.read(renterPenaltiesProvider.future)).length}',
      );
      await route('/contracts', '04-contracts');
      final contractButton = find.text('View / save contract');
      if (contractButton.evaluate().isNotEmpty) {
        await tester.ensureVisible(contractButton.first);
        await tester.tap(contractButton.first);
        await pumpFor(tester, 3);
        expect(downloaded.any((n) => n.startsWith('contract-')), isTrue);
      }
      await route('/renewals', '05-renewals');
      await route('/facilities', '06-facilities');
      await route('/facilities/requests', '07-booking-requests');

      if (const bool.fromEnvironment('TOUR_BOOKINGS')) {
        expect(
          container.read(authProvider).tenantId,
          'f40018ef-f489-4ef1-993c-5249fe228c08',
        );
        final facilities = await client.dio.get('/v1/facilities/my');
        for (final type in ['AMENITY', 'PARKING_SPOT']) {
          final candidates =
              facilities.data[type == 'AMENITY' ? 'amenities' : 'parkingSpots']
                  as List;
          final resource = candidates.firstWhere(
            (r) =>
                r['feeType'] == 'FREE' &&
                r['pendingCount'] == 0 &&
                (type == 'AMENITY' ? r['bookable'] == true : r['held'] != true),
          );
          final name =
              (type == 'AMENITY' ? resource['nameEn'] : resource['spotNumber'])
                  as String;
          final note =
              'Renter simulator QA ${DateTime.now().millisecondsSinceEpoch} $type';
          await route('/facilities', 'booking-$type-before');
          await tester.scrollUntilVisible(
            find.text(name),
            250,
            scrollable: find.byType(Scrollable).first,
          );
          final card = find
              .ancestor(
                of: find.text(name),
                matching: find.byWidgetPredicate(
                  (w) =>
                      w.runtimeType.toString() ==
                      (type == 'AMENITY' ? '_AmenityCard' : '_SpotCard'),
                ),
              )
              .first;
          final request = find.descendant(
            of: card,
            matching: find.text('REQUEST'),
          );
          await tester.ensureVisible(request);
          await tester.tap(request);
          await pumpFor(tester);
          if (type == 'PARKING_SPOT') {
            for (var i = 0; i < 2; i++) {
              await tester.ensureVisible(find.text('Pick a date').first);
              await tester.tap(find.text('Pick a date').first);
              await pumpFor(tester, 1);
              await tester.tap(find.text('OK'));
              await pumpFor(tester, 1);
            }
          }
          await tester.enterText(find.byKey(const Key('booking-note')), note);
          await tester.testTextInput.receiveAction(TextInputAction.done);
          await tester.ensureVisible(find.byKey(const Key('booking-submit')));
          await tester.tap(find.byKey(const Key('booking-submit')));
          await pumpFor(tester, 3);
          final after = (await client.dio.get('/v1/bookings/my')).data as List;
          final created = after.singleWhere((r) => r['note'] == note);
          expect(created['status'], 'PENDING');
          expect(created['feeAmount'], 0);
          final id = created['id'];
          try {
            await route('/facilities/requests', 'booking-$type-created');
            await tester.scrollUntilVisible(
              find.text(note),
              250,
              scrollable: find.byType(Scrollable).first,
            );
            final cancel = find.byKey(Key('cancel-$id'));
            await tester.scrollUntilVisible(
              cancel,
              250,
              scrollable: find.byType(Scrollable).first,
            );
            await pumpFor(tester, 1);
            for (
              var attempt = 0;
              cancel.hitTestable().evaluate().isEmpty && attempt < 10;
              attempt++
            ) {
              await tester.drag(
                find.byType(Scrollable).first,
                const Offset(0, -200),
              );
              await pumpFor(tester, 1);
            }
            expect(cancel.hitTestable(), findsOneWidget);
            await tester.tap(cancel);
            await pumpFor(tester, 1);
            await tester.tap(find.text('Confirm'));
            await pumpFor(tester, 3);
            final finalRows =
                (await client.dio.get('/v1/bookings/my')).data as List;
            expect(
              finalRows.singleWhere((r) => r['id'] == id)['status'],
              'CANCELLED',
            );
          } finally {
            final current =
                (await client.dio.get('/v1/bookings/my')).data as List;
            if (current.singleWhere((r) => r['id'] == id)['status'] ==
                'PENDING') {
              await client.dio.post('/v1/bookings/$id/cancel');
            }
            container.invalidate(myBookingRequestsProvider);
            container.invalidate(myFacilitiesProvider);
          }
        }
      }
      final tickets = (await client.dio.get('/v1/tickets')).data as List;
      if (tickets.isNotEmpty) {
        await route('/tickets/${tickets.first['id']}', 'ticket-detail');
      }
      final passes = (await client.dio.get('/v1/gatepass/mine')).data as List;
      if (passes.isNotEmpty) {
        await route('/gatepass/${passes.first['id']}', 'gatepass-detail');
      }
      final meetings =
          (await client.dio.get('/v1/meetings/my')).data['content'] as List;
      if (meetings.isNotEmpty) {
        await route('/meetings/${meetings.first['id']}', 'meeting-detail');
      }

      for (final entry in {
        '/tickets': '08-tickets',
        '/tickets/create': '09-ticket-create',
        '/meetings': '10-meetings',
        '/meetings/create': '11-meeting-create',
        '/gatepass': '12-gatepasses',
        '/gatepass/create': '13-gatepass-create',
        '/tickets/approvals': '14-approvals',
        '/notifications': '15-notifications',
        '/browse': '16-browse',
        '/wishlist': '17-wishlist',
        '/offers': '18-offers',
        '/profile': '19-profile',
        '/services': '20-services',
      }.entries) {
        await route(entry.key, entry.value);
      }

      container.read(appLanguageProvider.notifier).setLanguage(AppLanguage.ar);
      await pumpFor(tester);
      for (final entry in {
        '/payments': '21-wallet-ar',
        '/penalties': '22-penalties-ar',
        '/contracts': '23-contracts-ar',
        '/facilities': '24-facilities-ar',
      }.entries) {
        await route(entry.key, entry.value);
      }
      expect(
        failures,
        isEmpty,
        reason: 'No renter API request may fail silently',
      );
      container.read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
      container.read(routerProvider).go('/');
      await pumpFor(tester);
    });
  }
}
