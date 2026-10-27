// Play Store review videos for the manager app, one scenario per run.
// Driven by mobile/scripts/record-store-review.sh, which films the phone
// between the record-start and record-stop markers this test prints.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:manager/main.dart' as app;
import 'package:manager/screens/cheque_scan/cheque_scan_flow_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'store_review/cheque_art.dart';
import 'store_review/fake_gallery.dart';
import 'store_review/reviewer.dart';

const scenario = String.fromEnvironment('SCENARIO');
const _email = String.fromEnvironment('MANAGER_EMAIL');
const _password = String.fromEnvironment('MANAGER_PASSWORD');
const _leaseId = String.fromEnvironment('SCAN_LEASE_ID');

/// The organisation's valid payee name in the sandbox, and one that is not.
const _validPayee = 'Miftah Sandbox';
const _otherPayee = 'Gulf Star Trading LLC';

final _scenarios = <String, Future<void> Function(Reviewer, FakeGallery)>{
  'sign-in-and-dashboard': _signInAndDashboard,
  'portfolio': _portfolio,
  'maintenance-tickets': _maintenanceTickets,
  'cheque-scan-payee-check': _chequeScanPayeeCheck,
  'cheque-scan-multi': _chequeScanMulti,
  'profile-arabic-and-account': _profileArabicAndAccount,
};

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  binding.framePolicy = LiveTestWidgetsFlutterBindingFramePolicy.fullyLive;

  testWidgets('store review: $scenario', (tester) async {
    final run = _scenarios[scenario];
    if (run == null) {
      fail(
        'unknown SCENARIO "$scenario"; one of ${_scenarios.keys.join(', ')}',
      );
    }
    expect(_email, isNotEmpty, reason: 'MANAGER_EMAIL is not defined');
    expect(_password, isNotEmpty, reason: 'MANAGER_PASSWORD is not defined');
    // Typed text reaches the fields through the test's text input; the
    // phone's own keyboard would otherwise open over the Sign in button.
    tester.testTextInput.register();
    final gallery = FakeGallery.install();
    final r = Reviewer(tester);

    app.main();
    final end = DateTime.now().add(const Duration(seconds: 60));
    while (find.byType(TextFormField).evaluate().isEmpty &&
        find.text('Today').evaluate().isEmpty) {
      if (DateTime.now().isAfter(end)) fail('the app never reached sign-in');
      await r.pause(const Duration(milliseconds: 300));
    }
    await r.pause(const Duration(seconds: 2));
    await r.resetToSignIn();

    // ignore: avoid_print
    print('[store-review] record-start');
    await r.pause(const Duration(seconds: 2));
    await run(r, gallery);
    await r.pause(const Duration(seconds: 2));
    // ignore: avoid_print
    print('[store-review] record-stop');
    await r.signOutQuietly();
  }, timeout: const Timeout(Duration(minutes: 6)));
}

Future<void> _signInAndDashboard(Reviewer r, FakeGallery _) async {
  await r.signIn(_email, _password);
  await r.pause();
  await r.scrollDown();
  await r.scrollDown();
  await r.scrollUp(fraction: 0.8);
}

Future<void> _portfolio(Reviewer r, FakeGallery _) async {
  await r.signIn(_email, _password);

  // Properties → a property → its units.
  await r.tap(find.text('Portfolio'));
  await r.tap(Reviewer.byClass('_PropertyCard'), what: 'a property');
  await r.waitFor(Reviewer.byClass('_HeroHeader'), what: 'property detail');
  await r.pause();
  await r.scrollDown();
  await r.scrollDown();
  await r.back();

  // Renters (More → Renters).
  await r.tap(find.text('Today'));
  await r.tap(find.byType(MiftahAvatarButton), what: 'the account button');
  await r.tap(find.text('Renters'));
  await r.waitFor(Reviewer.byClass('_RenterRow'), what: 'the renters list');
  await r.pause();
  await r.scrollDown(fraction: 0.3);
  await r.back();
  await r.back();

  // Leases. Lease detail is not opened: it still reads the retired
  // /v1/payments/lease/{id} and shows "Failed to load lease details".
  await r.tap(Reviewer.label('New lease'), what: 'the leases tile');
  await r.waitFor(Reviewer.byClass('_LeaseCard'), what: 'the leases list');
  await r.pause(const Duration(milliseconds: 2500));
  await r.scrollDown(fraction: 0.3);
}

Future<void> _maintenanceTickets(Reviewer r, FakeGallery _) async {
  await r.signIn(_email, _password);
  await r.tap(Reviewer.label('Maintenance'));
  // The list opens on the tickets assigned to me; show the building's.
  await r.tap(find.text('All Tickets'));
  await r.waitFor(Reviewer.byClass('_TicketCard'), what: 'the tickets list');
  await r.pause();
  await r.tap(Reviewer.byClass('_TicketCard'));
  await r.waitFor(Reviewer.byClass('_InfoRow'), what: 'ticket detail');
  await r.pause();
  await r.scrollDown();
  await r.scrollDown();
  await r.back();
}

/// The lease's REGISTERED post-dated rows that carry no scan yet, read
/// through the app's own signed-in client.
Future<List<Map<String, dynamic>>> _unscannedRows(
  Reviewer r,
  int needed,
) async {
  expect(_leaseId, isNotEmpty, reason: 'SCAN_LEASE_ID is not defined');
  final dio = r.container.read(apiClientProvider).dio;
  final res = await dio.get('/v1/leases/$_leaseId/cheques');
  final rows =
      (res.data as List)
          .whereType<Map>()
          .map((e) => Map<String, dynamic>.from(e))
          .where(
            (c) =>
                c['status'] == 'REGISTERED' &&
                c['mode'] == 'PDC' &&
                c['imageUrl'] == null,
          )
          .toList()
        ..sort((a, b) => (a['seqNo'] as num).compareTo(b['seqNo'] as num));
  if (rows.length < needed) {
    fail(
      'lease $_leaseId has ${rows.length} REGISTERED cheque(s) without a scan; '
      'this scenario needs $needed. Reset or reseed the lease before recording.',
    );
  }
  return rows.take(needed).toList();
}

ChequeArt _artFor(Map<String, dynamic> row, String payee) => ChequeArt(
  number:
      row['chequeNumber']?.toString() ?? '${700000 + (row['seqNo'] as num)}',
  date: DateTime.parse(row['chequeDate'].toString()),
  amount: row['amount'] as num,
  payee: payee,
  payer: row['renterName']?.toString() ?? 'Demo Renter',
);

Future<void> _openScanAndPickFromGallery(Reviewer r) async {
  await r.tap(Reviewer.label('Scan cheque'));
  await r.waitFor(find.byIcon(Icons.image_outlined), what: 'the scan screen');
  await r.pause(const Duration(seconds: 2));
  await r.tap(find.byIcon(Icons.image_outlined), after: Duration.zero);
}

/// In the picker sheet: search the register for [number] and take the row.
Future<void> _pickRegisterRow(Reviewer r, String number) async {
  final sheet = find.byType(ChequeTargetPickerSheet);
  await r.waitFor(sheet);
  await r.pause();
  await r.type(find.byKey(const ValueKey('chequePickerSearch')), number);
  final row = find.descendant(
    of: find.descendant(of: sheet, matching: find.byType(ListView)),
    matching: find.byType(InkWell),
  );
  await r.pause();
  await r.tap(row, what: 'cheque $number in the register');
}

Future<void> _finishOnSuccess(Reviewer r) async {
  await r.waitFor(find.text('Cheque saved'), what: 'the success screen');
  await r.pause(const Duration(milliseconds: 2500));
  await r.tap(find.text('Done'));
}

Future<void> _chequeScanPayeeCheck(Reviewer r, FakeGallery gallery) async {
  await r.signIn(_email, _password);
  final row = (await _unscannedRows(r, 1)).single;
  final photo = await drawChequePhoto([
    _artFor(row, _otherPayee),
  ], 'payee-check');
  gallery.nextPath = photo.path;

  await _openScanAndPickFromGallery(r);
  final confirm = Reviewer.goldButton('Confirm & log cheque');
  await r.waitFor(
    confirm,
    timeout: const Duration(seconds: 90),
    what: 'the review step',
  );
  await r.pause();

  // The organisation's payee check: red, with the name read, and a tick.
  final mismatch = find.byKey(const ValueKey('payeeMismatch'));
  await r.waitFor(mismatch, what: 'the payee mismatch');
  await r.tester.ensureVisible(mismatch);
  await r.pause(const Duration(milliseconds: 2500));
  await r.tap(find.byKey(const ValueKey('payeeMismatchConfirm')));

  await r.tap(find.text('Pick instalment'));
  await _pickRegisterRow(r, row['chequeNumber'].toString());
  await r.tap(find.text('Hold in safe'));
  await r.tap(confirm);
  await _finishOnSuccess(r);
}

Future<void> _chequeScanMulti(Reviewer r, FakeGallery gallery) async {
  await r.signIn(_email, _password);
  final rows = await _unscannedRows(r, 2);
  final photo = await drawChequePhoto([
    for (final row in rows) _artFor(row, _validPayee),
  ], 'multi');
  gallery.nextPath = photo.path;

  await _openScanAndPickFromGallery(r);
  await r.waitFor(
    find.text('Review 2 cheques'),
    timeout: const Duration(seconds: 90),
    what: 'two cheques read from one photo',
  );
  await r.pause();
  await r.tap(find.byKey(const ValueKey('multiPickLease')));
  await _pickRegisterRow(r, rows.first['chequeNumber'].toString());
  await r.pause();
  await r.scrollDown(fraction: 0.35);
  await r.scrollDown(fraction: 0.35);
  // The list builds lazily: the count below the cards exists once in view.
  await r.waitFor(find.text('Ready: 2 of 2'), what: 'both cheques mapped');
  await r.tap(find.byKey(const ValueKey('multiAttachButton')));
  await _finishOnSuccess(r);
}

Future<void> _profileArabicAndAccount(Reviewer r, FakeGallery _) async {
  await r.signIn(_email, _password);
  final account = find.byType(MiftahAvatarButton);

  // Profile.
  await r.tap(account, what: 'the account button');
  await r.tap(find.text('Profile'));
  await r.pause();
  await r.scrollDown();
  await r.back();

  // Arabic, right to left, then back to English.
  await r.tap(find.text('عربي'));
  await r.pause(const Duration(seconds: 2));
  await r.back();
  await r.waitFor(find.text('اليوم'), what: 'the Arabic dashboard');
  await r.pause(const Duration(milliseconds: 2500));
  await r.scrollDown(fraction: 0.3);
  await r.scrollUp(fraction: 0.6);
  await r.tap(account.first);
  await r.tap(find.text('EN'));
  await r.pause();

  // Dark mode on, shown on the dashboard, then off.
  await r.tap(Reviewer.label('DARK'));
  await r.back();
  await r.pause(const Duration(milliseconds: 2500));
  await r.tap(account.first);
  await r.tap(Reviewer.label('LIGHT'));

  // Privacy and terms live under Account.
  await r.tester.ensureVisible(find.text('Terms of Use'));
  await r.pause(const Duration(milliseconds: 2500));

  // Account deletion starts in-app; open it and cancel.
  await r.tap(find.text('Profile'));
  final delete = find.text('Delete account');
  await r.waitFor(delete);
  await r.tester.ensureVisible(delete.first);
  await r.pause();
  await r.tap(delete.first);
  await r.pause(const Duration(milliseconds: 2500));
  await r.tap(find.text('Cancel').last);
  await r.back();
  await r.back();
}
