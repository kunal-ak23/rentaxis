import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/demo/demo_flags.dart';
import 'package:renter/demo/wallet_demo.dart';
import 'package:renter/providers/renter_data.dart';
import 'package:renter/screens/payments_screen.dart';
import 'package:renter/screens/wallet/checkout_screen.dart';
import 'package:renter/screens/wallet/rewards_screen.dart';
import 'package:renter/screens/wallet/scratch_card.dart';

import 'support/harness.dart';

Map<String, dynamic> dueRow({
  String id = 'chq-1',
  num payable = 7000,
  int instalment = 3,
}) => {
  'id': id,
  'installmentNumber': instalment,
  'propertyName': 'Palm Ridge',
  'unitIdentifier': 'A-204',
  'amount': payable,
  'payable': payable,
  'due': true,
  'overdue': false,
  'status': 'REGISTERED',
  'mode': 'CHEQUE',
  'dueDate': '2026-11-01',
  'onlineEnabled': true,
};

List<Override> overrides(List<Map<String, dynamic>> rows) => [
  demoPaymentsEnabledProvider.overrideWithValue(true),
  renterPaymentsProvider.overrideWith((ref) async => rows),
  renterLeasesProvider.overrideWith((ref) async => const []),
  renterPenaltiesProvider.overrideWith((ref) async => const []),
  renterInvoicesProvider.overrideWith((ref) async => const []),
  walletDemoProvider.overrideWith(
    () => WalletDemoNotifier(
      clock: () => DateTime(2026, 11, 10, 9),
      random: Random(1),
    ),
  ),
];

List<RouteBase> routes({TargetPlatform platform = TargetPlatform.iOS}) => [
  GoRoute(
    path: '/pay/:chequeId',
    builder: (context, state) => CheckoutScreen(
      chequeId: state.pathParameters['chequeId']!,
      platform: platform,
    ),
  ),
  GoRoute(
    path: '/payments',
    builder: (context, state) => const Scaffold(body: PaymentsScreen()),
    routes: [
      GoRoute(
        path: 'rewards',
        builder: (context, state) => const RewardsScreen(),
      ),
    ],
  ),
];

/// Money as the app prints it (`Formatters.currency` wraps the text in
/// Unicode isolates, so a bare 'AED 7,000.00' never matches).
String money(num amount) => Formatters.currency(amount);

/// Walks the gateway's three 700 ms steps.
Future<void> settleGateway(WidgetTester tester) async {
  for (var i = 0; i < 3; i++) {
    await tester.pump(const Duration(milliseconds: 700));
  }
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('checkout shows the order, pays, and hands over a scratch card', (
    tester,
  ) async {
    final container = await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(),
      initialLocation: '/pay/chq-1',
      overrides: overrides([dueRow()]),
      surfaceSize: const Size(430, 1400),
    );

    // Review: amount, saved card selected by default, Apple Pay on iOS.
    expect(find.byKey(const Key('checkout-amount')), findsOneWidget);
    expect(find.text(money(7000)), findsWidgets);
    expect(find.text('Visa •••• 4242'), findsOneWidget);
    expect(find.text('Apple Pay'), findsOneWidget);
    expect(find.text('Google Pay'), findsNothing);
    expect(find.text('Waived'), findsOneWidget);

    await tester.tap(find.byKey(const Key('checkout-method-applePay')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('checkout-pay')));
    await tester.pump();
    expect(find.byKey(const Key('checkout-processing')), findsOneWidget);
    expect(find.text('Contacting your bank'), findsOneWidget);

    await settleGateway(tester);

    // Success: receipt, Apple Pay as the method, a card to scratch.
    expect(find.byKey(const Key('checkout-success')), findsOneWidget);
    expect(find.text('Apple Pay'), findsOneWidget);
    expect(find.byKey(const Key('checkout-scratch')), findsOneWidget);
    final wallet = container.read(walletDemoProvider);
    expect(wallet.payments['chq-1']!.method, DemoPayMethod.applePay);
    expect(wallet.unscratchedCount, 1);
    final balanceBefore = wallet.balance;

    // Scratch (via Reveal) → cashback lands in the wallet.
    await tester.tap(find.byKey(const Key('checkout-scratch')));
    await tester.pumpAndSettle();
    expect(find.text('Scratch to reveal your reward'), findsOneWidget);
    await tester.tap(find.byKey(const Key('scratch-reveal')));
    await tester.pumpAndSettle();
    final cashback = demoCashbackFor(7000);
    expect(find.text('You won ${money(cashback)} cashback!'), findsOneWidget);
    expect(
      container.read(walletDemoProvider).balance,
      balanceBefore + cashback,
    );

    await tester.tap(find.byKey(const Key('scratch-done')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('checkout-reward-collected')), findsOneWidget);
    expect(find.text('Cashback added to your wallet'), findsOneWidget);

    // Done → the Wallet tab shows the row paid and the new balance.
    await tester.tap(find.byKey(const Key('checkout-done')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('wallet-demo-card')), findsOneWidget);
    expect(find.text(money(balanceBefore + cashback)), findsWidgets);
    expect(find.byKey(const Key('paid-online-chq-1')), findsOneWidget);
    expect(find.byKey(const Key('pay-online-chq-1')), findsNothing);
    expect(find.text('Cleared'), findsOneWidget);
  });

  testWidgets('rubbing the cover reveals the card without the button', (
    tester,
  ) async {
    final container = await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(),
      initialLocation: '/pay/chq-1',
      overrides: overrides([dueRow()]),
      surfaceSize: const Size(430, 1400),
    );
    await tester.tap(find.byKey(const Key('checkout-pay')));
    await settleGateway(tester);
    await tester.tap(find.byKey(const Key('checkout-scratch')));
    await tester.pumpAndSettle();

    final cover = find.byKey(const Key('scratch-cover'));
    final rect = tester.getRect(cover);
    // Six passes, one per cell row, left to right.
    for (var row = 0; row < 6; row++) {
      final y = rect.top + rect.height * (row + 0.5) / 6;
      await tester.timedDragFrom(
        Offset(rect.left + 10, y),
        Offset(rect.width - 20, 0),
        const Duration(milliseconds: 300),
        pointer: row + 1,
      );
      await tester.pump();
      if (container.read(walletDemoProvider).unscratchedCount == 0) break;
    }
    await tester.pumpAndSettle();
    expect(container.read(walletDemoProvider).unscratchedCount, 0);
    expect(find.byKey(const Key('scratch-done')), findsOneWidget);
  });

  testWidgets('wallet balance can cover a small bill in full', (tester) async {
    final container = await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(),
      initialLocation: '/pay/small',
      overrides: overrides([dueRow(id: 'small', payable: 30)]),
      surfaceSize: const Size(430, 1400),
    );
    expect(find.text('${money(42.50)} available'), findsOneWidget);
    await tester.tap(find.byKey(const Key('checkout-use-wallet')));
    await tester.pumpAndSettle();

    expect(find.text('Pay with wallet'), findsOneWidget);
    expect(find.byKey(const Key('checkout-method-card')), findsNothing);
    expect(
      find.text('Your wallet balance covers this payment in full.'),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('checkout-pay')));
    await settleGateway(tester);
    expect(find.byKey(const Key('checkout-success')), findsOneWidget);
    expect(find.text('Miftah Wallet'), findsOneWidget);
    expect(container.read(walletDemoProvider).balance, 12.50);
  });

  testWidgets('a new card needs a number, expiry and CVV before Pay enables', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(platform: TargetPlatform.android),
      initialLocation: '/pay/chq-1',
      overrides: overrides([dueRow()]),
      surfaceSize: const Size(430, 1500),
    );
    expect(find.text('Google Pay'), findsOneWidget);
    await tester.tap(find.byKey(const Key('checkout-method-newCard')));
    await tester.pumpAndSettle();

    MiftahGoldButtonProbe pay() =>
        MiftahGoldButtonProbe(tester, const Key('checkout-pay'));
    expect(pay().enabled, isFalse);

    await tester.enterText(
      find.byKey(const Key('checkout-card-number')),
      '4111111111111111',
    );
    await tester.enterText(
      find.byKey(const Key('checkout-card-expiry')),
      '1229',
    );
    await tester.pump();
    expect(pay().enabled, isFalse);
    await tester.enterText(find.byKey(const Key('checkout-card-cvv')), '123');
    await tester.pump();
    expect(pay().enabled, isTrue);

    await tester.tap(find.byKey(const Key('checkout-pay')));
    await settleGateway(tester);
    expect(find.text('Card •••• 1111'), findsOneWidget);
  });

  testWidgets('Wallet tab offers Pay online only on rows owed now', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(),
      initialLocation: '/payments',
      overrides: overrides([
        dueRow(id: 'owed'),
        {
          ...dueRow(id: 'cleared', instalment: 2),
          'status': 'CLEARED',
          'due': false,
          'payable': 0,
        },
      ]),
      surfaceSize: const Size(430, 1600),
    );
    expect(find.byKey(const Key('wallet-demo-card')), findsOneWidget);
    expect(find.byKey(const Key('pay-online-owed')), findsOneWidget);
    expect(find.byKey(const Key('pay-online-cleared')), findsNothing);
    expect(find.text(money(42.50)), findsOneWidget);

    await tester.tap(find.byKey(const Key('pay-online-owed')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('checkout-amount')), findsOneWidget);
  });

  testWidgets('Rewards screen lists the seeded voucher and history', (
    tester,
  ) async {
    final container = await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(),
      initialLocation: '/payments/rewards',
      overrides: overrides([dueRow()]),
      surfaceSize: const Size(430, 1200),
    );
    expect(find.byKey(const Key('rewards-balance')), findsOneWidget);
    expect(
      find.byType(RewardFace),
      findsNWidgets(2),
      reason: 'two seeded cards',
    );

    await tester.tap(find.byKey(const Key('rewards-tab-vouchers')));
    await tester.pumpAndSettle();
    expect(find.text('CITY CLEAN LAUNDRY'), findsOneWidget);
    expect(find.text('CLEAN15'), findsOneWidget);
    await tester.tap(find.byKey(const Key('voucher-use-seed-voucher')));
    await tester.pumpAndSettle();
    expect(find.text('USED'), findsOneWidget);
    expect(container.read(walletDemoProvider).availableVoucherCount, 0);

    await tester.tap(find.byKey(const Key('rewards-tab-history')));
    await tester.pumpAndSettle();
    expect(find.text('Welcome bonus'), findsOneWidget);
    expect(find.text('+ ${money(17.50)}'), findsOneWidget);
  });

  testWidgets('demo off: no wallet card, no Pay online', (tester) async {
    await pumpScreen(
      tester,
      theme: MiftahTheme.light,
      routes: routes(),
      initialLocation: '/payments',
      overrides: [
        ...overrides([dueRow()]),
        demoPaymentsEnabledProvider.overrideWithValue(false),
      ],
      surfaceSize: const Size(430, 1400),
    );
    expect(find.byKey(const Key('wallet-demo-card')), findsNothing);
    expect(find.byKey(const Key('pay-online-chq-1')), findsNothing);
  });
}

/// Reads whether a gold button is tappable: it draws an InkWell whose
/// `onTap` is null while disabled.
class MiftahGoldButtonProbe {
  MiftahGoldButtonProbe(this.tester, this.key);
  final WidgetTester tester;
  final Key key;

  bool get enabled {
    final ink = tester.widget<InkWell>(
      find.descendant(of: find.byKey(key), matching: find.byType(InkWell)),
    );
    return ink.onTap != null;
  }
}
