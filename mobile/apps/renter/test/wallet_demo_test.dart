import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:renter/demo/wallet_demo.dart';

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
};

ProviderContainer container() {
  final c = ProviderContainer(
    overrides: [
      walletDemoProvider.overrideWith(
        () => WalletDemoNotifier(
          clock: () => DateTime(2026, 11, 10, 9),
          random: Random(7),
        ),
      ),
    ],
  );
  addTearDown(c.dispose);
  return c;
}

void main() {
  test('opens with a little history: balance, one voucher, no new cards', () {
    final c = container();
    final s = c.read(walletDemoProvider);
    expect(s.balance, 42.50);
    expect(s.payments, isEmpty);
    expect(s.unscratchedCount, 0);
    expect(s.availableVoucherCount, 1);
    expect(s.txns, hasLength(2));
  });

  test(
    'a payment records the row, mints an unscratched card, charges in full',
    () {
      final c = container();
      final n = c.read(walletDemoProvider.notifier);
      final p = n.pay(row: dueRow(), method: DemoPayMethod.applePay);
      final s = c.read(walletDemoProvider);

      expect(p.amount, 7000);
      expect(p.walletUsed, 0);
      expect(p.charged, 7000);
      expect(p.method, DemoPayMethod.applePay);
      expect(p.reference, startsWith('pay_'));
      expect(s.payments['chq-1'], same(p));
      expect(s.balance, 42.50, reason: 'nothing spent, nothing credited yet');
      expect(s.unscratchedCount, 1);
      final card = s.reward(p.rewardId)!;
      expect(card.kind, DemoRewardKind.cashback);
      expect(card.amount, demoCashbackFor(7000));
      expect(card.earnedForEn, 'Instalment 3 · Palm Ridge');
      expect(s.txns.first.kind, DemoTxnKind.payment);
      expect(s.txns.first.amount, -7000);
    },
  );

  test('paying with the wallet spends the balance first', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    final p = n.pay(row: dueRow(), method: DemoPayMethod.card, useWallet: true);
    final s = c.read(walletDemoProvider);
    expect(p.walletUsed, 42.50);
    expect(p.charged, 6957.50);
    expect(p.method, DemoPayMethod.card);
    expect(s.balance, 0);
    expect(s.txns.first.kind, DemoTxnKind.walletSpent);
    expect(s.txns.first.amount, -42.50);
  });

  test('a bill the wallet covers in full is paid by wallet alone', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    final p = n.pay(
      row: dueRow(payable: 30),
      method: DemoPayMethod.card,
      useWallet: true,
    );
    expect(p.walletUsed, 30);
    expect(p.charged, 0);
    expect(p.method, DemoPayMethod.wallet);
    expect(c.read(walletDemoProvider).balance, 12.50);
  });

  test('paying the same row twice returns the first payment', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    final a = n.pay(row: dueRow(), method: DemoPayMethod.card);
    final b = n.pay(row: dueRow(), method: DemoPayMethod.netBanking);
    expect(b, same(a));
    expect(c.read(walletDemoProvider).unscratchedCount, 1);
  });

  test('cards rotate cashback, voucher, cashback, better luck', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    final kinds = <DemoRewardKind>[];
    for (var i = 0; i < 4; i++) {
      final p = n.pay(
        row: dueRow(id: 'chq-$i'),
        method: DemoPayMethod.card,
      );
      kinds.add(c.read(walletDemoProvider).reward(p.rewardId)!.kind);
    }
    expect(kinds, [
      DemoRewardKind.cashback,
      DemoRewardKind.voucher,
      DemoRewardKind.cashback,
      DemoRewardKind.betterLuck,
    ]);
  });

  test('scratching a cashback card credits the balance once', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    final p = n.pay(row: dueRow(), method: DemoPayMethod.card);
    final before = c.read(walletDemoProvider).balance;

    final revealed = n.scratch(p.rewardId)!;
    expect(revealed.scratched, isTrue);
    expect(c.read(walletDemoProvider).balance, before + revealed.amount!);
    expect(c.read(walletDemoProvider).txns.first.kind, DemoTxnKind.cashback);

    n.scratch(p.rewardId);
    expect(
      c.read(walletDemoProvider).balance,
      before + revealed.amount!,
      reason: 'a second scratch is a no-op',
    );
    expect(c.read(walletDemoProvider).unscratchedCount, 0);
  });

  test('scratching a voucher card lists it under vouchers until used', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    n.pay(
      row: dueRow(id: 'a'),
      method: DemoPayMethod.card,
    );
    final p = n.pay(
      row: dueRow(id: 'b'),
      method: DemoPayMethod.card,
    );
    final before = c.read(walletDemoProvider).balance;

    n.scratch(p.rewardId);
    var s = c.read(walletDemoProvider);
    expect(s.balance, before, reason: 'a voucher moves no money');
    expect(s.availableVoucherCount, 2);
    expect(s.vouchers.map((r) => r.id), contains(p.rewardId));

    n.useVoucher(p.rewardId);
    s = c.read(walletDemoProvider);
    expect(s.availableVoucherCount, 1);
    expect(s.reward(p.rewardId)!.isUsed, isTrue);
  });

  test('cashback is 1.5%, whole dirhams, between 5 and 100', () {
    expect(demoCashbackFor(100), 5);
    expect(demoCashbackFor(7000), 105.clamp(5, 100));
    expect(demoCashbackFor(2000), 30);
    expect(demoCashbackFor(3333), 50);
  });

  test('applyDemoPayments turns a paid row into CLEARED / ONLINE', () {
    final c = container();
    final n = c.read(walletDemoProvider.notifier);
    final rows = [dueRow(id: 'paid'), dueRow(id: 'open', instalment: 4)];
    final p = n.pay(row: rows[0], method: DemoPayMethod.card);

    final seen = applyDemoPayments(rows, c.read(walletDemoProvider));
    final paid = seen[0];
    expect(paid['status'], 'CLEARED');
    expect(paid['mode'], 'ONLINE');
    expect(paid['due'], false);
    expect(paid['payable'], 0);
    expect(paid['demoPaidReference'], p.reference);
    expect(paid['clearedAt'], startsWith('2026-11-10'));
    expect(seen[1], same(rows[1]), reason: 'untouched rows pass through');
    expect(rows[0]['status'], 'REGISTERED', reason: 'input is not mutated');
  });

  test('applyDemoPayments is a pass-through before any payment', () {
    final c = container();
    final rows = [dueRow()];
    expect(applyDemoPayments(rows, c.read(walletDemoProvider)), same(rows));
  });
}
