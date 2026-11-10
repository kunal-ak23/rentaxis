/// In-memory wallet for the demo payment flow (`DEMO_PAYMENTS=true`).
///
/// Holds everything the pseudo gateway and rewards screens show — which
/// instalments were "paid online", the cashback balance, scratch cards,
/// vouchers and the transaction history. It is seeded with a little history
/// so the Wallet tab does not open empty, and it never talks to the backend.
/// Restart the app to reset it.
library;

import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';

enum DemoPayMethod { card, newCard, applePay, googlePay, netBanking, wallet }

enum DemoRewardKind { cashback, voucher, betterLuck }

enum DemoTxnKind { cashback, bonus, walletSpent, payment }

class DemoVoucher {
  const DemoVoucher({
    required this.brandEn,
    required this.brandAr,
    required this.titleEn,
    required this.titleAr,
    required this.code,
    required this.termsEn,
    required this.termsAr,
  });

  final String brandEn;
  final String brandAr;
  final String titleEn;
  final String titleAr;
  final String code;
  final String termsEn;
  final String termsAr;

  String brand(bool ar) => ar ? brandAr : brandEn;
  String title(bool ar) => ar ? titleAr : titleEn;
  String terms(bool ar) => ar ? termsAr : termsEn;
}

class DemoReward {
  const DemoReward({
    required this.id,
    required this.kind,
    required this.earnedAt,
    required this.earnedForEn,
    required this.earnedForAr,
    required this.expiresAt,
    this.amount,
    this.voucher,
    this.scratched = false,
    this.usedAt,
  });

  final String id;
  final DemoRewardKind kind;
  final DateTime earnedAt;
  final String earnedForEn;
  final String earnedForAr;
  final DateTime expiresAt;

  /// Cashback value, for [DemoRewardKind.cashback].
  final num? amount;

  /// The voucher, for [DemoRewardKind.voucher].
  final DemoVoucher? voucher;
  final bool scratched;
  final DateTime? usedAt;

  String earnedFor(bool ar) => ar ? earnedForAr : earnedForEn;
  bool get isUsed => usedAt != null;

  DemoReward copyWith({bool? scratched, DateTime? usedAt}) => DemoReward(
    id: id,
    kind: kind,
    earnedAt: earnedAt,
    earnedForEn: earnedForEn,
    earnedForAr: earnedForAr,
    expiresAt: expiresAt,
    amount: amount,
    voucher: voucher,
    scratched: scratched ?? this.scratched,
    usedAt: usedAt ?? this.usedAt,
  );
}

class DemoTxn {
  const DemoTxn({
    required this.id,
    required this.kind,
    required this.titleEn,
    required this.titleAr,
    required this.amount,
    required this.at,
    this.reference,
  });

  final String id;
  final DemoTxnKind kind;
  final String titleEn;
  final String titleAr;

  /// Positive credits the wallet, negative spends it. A [DemoTxnKind.payment]
  /// row is the gateway charge itself and does not move the balance.
  final num amount;
  final DateTime at;
  final String? reference;

  String title(bool ar) => ar ? titleAr : titleEn;
  bool get movesBalance => kind != DemoTxnKind.payment;
}

class DemoPayment {
  const DemoPayment({
    required this.chequeId,
    required this.amount,
    required this.walletUsed,
    required this.method,
    required this.reference,
    required this.at,
    required this.rewardId,
  });

  final String chequeId;

  /// What the instalment owed.
  final num amount;

  /// The slice covered from the cashback balance.
  final num walletUsed;
  final DemoPayMethod method;
  final String reference;
  final DateTime at;

  /// The scratch card this payment minted.
  final String rewardId;

  /// What went through the gateway.
  num get charged => amount - walletUsed;
}

class WalletDemoState {
  const WalletDemoState({
    required this.balance,
    required this.payments,
    required this.rewards,
    required this.txns,
  });

  final num balance;

  /// Keyed by cheque id.
  final Map<String, DemoPayment> payments;

  /// Newest first.
  final List<DemoReward> rewards;

  /// Newest first.
  final List<DemoTxn> txns;

  int get unscratchedCount => rewards.where((r) => !r.scratched).length;

  List<DemoReward> get vouchers => rewards
      .where((r) => r.scratched && r.kind == DemoRewardKind.voucher)
      .toList();

  int get availableVoucherCount => vouchers.where((r) => !r.isUsed).length;

  DemoReward? reward(String id) => rewards.where((r) => r.id == id).firstOrNull;

  WalletDemoState copyWith({
    num? balance,
    Map<String, DemoPayment>? payments,
    List<DemoReward>? rewards,
    List<DemoTxn>? txns,
  }) => WalletDemoState(
    balance: balance ?? this.balance,
    payments: payments ?? this.payments,
    rewards: rewards ?? this.rewards,
    txns: txns ?? this.txns,
  );
}

const _voucherPool = <DemoVoucher>[
  DemoVoucher(
    brandEn: 'Green Mart',
    brandAr: 'غرين مارت',
    titleEn: 'AED 50 off groceries over AED 200',
    titleAr: 'خصم 50 درهماً على مشتريات البقالة فوق 200 درهم',
    code: 'MIFTAH50',
    termsEn: 'One use per customer. Valid in store and on the app.',
    termsAr: 'استخدام واحد لكل عميل. صالح في المتجر وعلى التطبيق.',
  ),
  DemoVoucher(
    brandEn: 'Palm Fitness',
    brandAr: 'بالم فيتنس',
    titleEn: '2 weeks free, then 20% off your first month',
    titleAr: 'أسبوعان مجاناً ثم خصم 20% على الشهر الأول',
    code: 'PALMFIT20',
    termsEn: 'New members only. Show the code at reception.',
    termsAr: 'للأعضاء الجدد فقط. أظهر الرمز عند الاستقبال.',
  ),
  DemoVoucher(
    brandEn: 'City Clean Laundry',
    brandAr: 'سيتي كلين للغسيل',
    titleEn: 'Free pickup and 15% off your next order',
    titleAr: 'استلام مجاني وخصم 15% على طلبك القادم',
    code: 'CLEAN15',
    termsEn: 'Orders over AED 60. Expires with the card.',
    termsAr: 'للطلبات فوق 60 درهماً. ينتهي مع البطاقة.',
  ),
];

/// Cashback for a payment: 1.5% of the amount, at least AED 5, at most
/// AED 100, in whole dirhams — the kind of number a cashback app shows.
num demoCashbackFor(num amount) =>
    (amount * 0.015).round().clamp(5, 100).toInt();

class WalletDemoNotifier extends Notifier<WalletDemoState> {
  WalletDemoNotifier({DateTime Function()? clock, Random? random})
    : _clock = clock ?? DateTime.now,
      _random = random ?? Random();

  final DateTime Function() _clock;
  final Random _random;

  @override
  WalletDemoState build() => _seed(_clock());

  /// A little history so the Wallet tab has something to show before the
  /// first payment of the demo: one cashback already scratched and credited,
  /// a welcome bonus, and one voucher still unused.
  static WalletDemoState _seed(DateTime now) {
    final lastMonth = now.subtract(const Duration(days: 31));
    final onboarding = now.subtract(const Duration(days: 62));
    return WalletDemoState(
      balance: 42.50,
      payments: const {},
      rewards: [
        DemoReward(
          id: 'seed-voucher',
          kind: DemoRewardKind.voucher,
          earnedAt: lastMonth,
          earnedForEn: 'Instalment paid online',
          earnedForAr: 'قسط مدفوع إلكترونياً',
          expiresAt: lastMonth.add(const Duration(days: 90)),
          voucher: _voucherPool[2],
          scratched: true,
        ),
        DemoReward(
          id: 'seed-cashback',
          kind: DemoRewardKind.cashback,
          earnedAt: lastMonth,
          earnedForEn: 'Instalment paid online',
          earnedForAr: 'قسط مدفوع إلكترونياً',
          expiresAt: lastMonth.add(const Duration(days: 90)),
          amount: 25,
          scratched: true,
        ),
      ],
      txns: [
        DemoTxn(
          id: 'seed-txn-cashback',
          kind: DemoTxnKind.cashback,
          titleEn: 'Cashback · instalment paid online',
          titleAr: 'كاش باك · قسط مدفوع إلكترونياً',
          amount: 25,
          at: lastMonth,
        ),
        DemoTxn(
          id: 'seed-txn-bonus',
          kind: DemoTxnKind.bonus,
          titleEn: 'Welcome bonus',
          titleAr: 'مكافأة ترحيبية',
          amount: 17.50,
          at: onboarding,
        ),
      ],
    );
  }

  String _ref(String prefix) {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    final tail = List.generate(
      10,
      (_) => alphabet[_random.nextInt(alphabet.length)],
    ).join();
    return '${prefix}_$tail';
  }

  /// Which card the next payment mints. Rotates so a demo that pays three
  /// instalments shows a cashback, then a voucher, then a cashback again;
  /// "better luck next time" only turns up on the fourth.
  DemoRewardKind _nextKind() => switch (state.payments.length % 4) {
    0 || 2 => DemoRewardKind.cashback,
    1 => DemoRewardKind.voucher,
    _ => DemoRewardKind.betterLuck,
  };

  /// Records an online payment of [row] (a `RenterChequeDTO` map) and mints
  /// its scratch card. [useWallet] spends as much of the cashback balance as
  /// the instalment allows; the rest is what the gateway "charged".
  DemoPayment pay({
    required Map<String, dynamic> row,
    required DemoPayMethod method,
    bool useWallet = false,
  }) {
    final chequeId = row['id'].toString();
    final existing = state.payments[chequeId];
    if (existing != null) return existing;

    final amount = (row['payable'] as num?) ?? (row['amount'] as num?) ?? 0;
    final walletUsed = useWallet ? min(state.balance, amount) : 0;
    final now = _clock();
    final instalment = row['installmentNumber'];
    final forEn = instalment == null
        ? 'Payment to ${row['propertyName'] ?? 'your landlord'}'
        : 'Instalment $instalment · ${row['propertyName'] ?? ''}';
    final forAr = instalment == null
        ? 'دفعة إلى ${row['propertyName'] ?? 'المالك'}'
        : 'قسط $instalment · ${row['propertyName'] ?? ''}';

    final kind = _nextKind();
    final reward = DemoReward(
      id: _ref('rwd'),
      kind: kind,
      earnedAt: now,
      earnedForEn: forEn,
      earnedForAr: forAr,
      expiresAt: now.add(const Duration(days: 90)),
      amount: kind == DemoRewardKind.cashback ? demoCashbackFor(amount) : null,
      voucher: kind == DemoRewardKind.voucher
          ? _voucherPool[state.payments.length % _voucherPool.length]
          : null,
    );
    final payment = DemoPayment(
      chequeId: chequeId,
      amount: amount,
      walletUsed: walletUsed,
      method: walletUsed >= amount ? DemoPayMethod.wallet : method,
      reference: _ref('pay'),
      at: now,
      rewardId: reward.id,
    );

    state = state.copyWith(
      balance: state.balance - walletUsed,
      payments: {...state.payments, chequeId: payment},
      rewards: [reward, ...state.rewards],
      txns: [
        if (walletUsed > 0)
          DemoTxn(
            id: _ref('txn'),
            kind: DemoTxnKind.walletSpent,
            titleEn: 'Wallet balance used · $forEn',
            titleAr: 'استخدام رصيد المحفظة · $forAr',
            amount: -walletUsed,
            at: now,
            reference: payment.reference,
          ),
        DemoTxn(
          id: _ref('txn'),
          kind: DemoTxnKind.payment,
          titleEn: forEn,
          titleAr: forAr,
          amount: -payment.charged,
          at: now,
          reference: payment.reference,
        ),
        ...state.txns,
      ],
    );
    return payment;
  }

  /// Reveals a scratch card. A cashback card credits the balance at once.
  DemoReward? scratch(String rewardId) {
    final reward = state.reward(rewardId);
    if (reward == null || reward.scratched) return reward;
    final revealed = reward.copyWith(scratched: true);
    final credit = revealed.kind == DemoRewardKind.cashback
        ? (revealed.amount ?? 0)
        : 0;
    state = state.copyWith(
      balance: state.balance + credit,
      rewards: [for (final r in state.rewards) r.id == rewardId ? revealed : r],
      txns: [
        if (credit > 0)
          DemoTxn(
            id: _ref('txn'),
            kind: DemoTxnKind.cashback,
            titleEn: 'Cashback · ${revealed.earnedForEn}',
            titleAr: 'كاش باك · ${revealed.earnedForAr}',
            amount: credit,
            at: _clock(),
          ),
        ...state.txns,
      ],
    );
    return revealed;
  }

  /// Marks a voucher as used (the renter showed the code at the business).
  void useVoucher(String rewardId) {
    final reward = state.reward(rewardId);
    if (reward == null || reward.kind != DemoRewardKind.voucher) return;
    if (reward.isUsed) return;
    state = state.copyWith(
      rewards: [
        for (final r in state.rewards)
          r.id == rewardId ? r.copyWith(usedAt: _clock()) : r,
      ],
    );
  }
}

final walletDemoProvider =
    NotifierProvider<WalletDemoNotifier, WalletDemoState>(
      WalletDemoNotifier.new,
    );

/// The payments list as the demo sees it: an instalment paid in this session
/// comes back CLEARED, ONLINE, with nothing left to pay, so the Wallet list,
/// the home hero and "Due now" all move without a server round trip.
List<Map<String, dynamic>> applyDemoPayments(
  List<Map<String, dynamic>> rows,
  WalletDemoState wallet,
) {
  if (wallet.payments.isEmpty) return rows;
  return [
    for (final row in rows)
      switch (wallet.payments[row['id']?.toString()]) {
        null => row,
        final paid => {
          ...row,
          'status': 'CLEARED',
          'mode': 'ONLINE',
          'due': false,
          'overdue': false,
          'payable': 0,
          'clearedAt': paid.at.toIso8601String(),
          'demoPaidReference': paid.reference,
        },
      },
  ];
}
