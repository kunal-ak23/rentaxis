import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../demo/wallet_demo.dart';
import 'scratch_card.dart';

/// Rewards under the Wallet tab (demo): balance, scratch cards, vouchers and
/// the cashback history — the Paytm / Google Pay rewards drawer.
class RewardsScreen extends ConsumerWidget {
  const RewardsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    final l = _L(ar);
    final wallet = ref.watch(walletDemoProvider);

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(title: Text(l.title)),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: _BalanceCard(wallet: wallet),
            ),
            TabBar(
              labelStyle: MiftahType.button(size: 13),
              tabs: [
                Tab(
                  key: const Key('rewards-tab-cards'),
                  text: l.scratchCards(wallet.unscratchedCount),
                ),
                Tab(
                  key: const Key('rewards-tab-vouchers'),
                  text: l.vouchers(wallet.availableVoucherCount),
                ),
                Tab(key: const Key('rewards-tab-history'), text: l.history),
              ],
            ),
            Expanded(
              child: TabBarView(
                children: [
                  _ScratchCards(rewards: wallet.rewards),
                  _Vouchers(vouchers: wallet.vouchers),
                  _History(txns: wallet.txns),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BalanceCard extends StatelessWidget {
  const _BalanceCard({required this.wallet});
  final WalletDemoState wallet;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: MiftahColors.ink,
        borderRadius: BorderRadius.circular(MiftahRadii.hero),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.balanceLabel,
                  style: MiftahType.sectionLabel(color: MiftahColors.brassPale),
                ),
                const SizedBox(height: 4),
                Text(
                  Formatters.currency(wallet.balance),
                  key: const Key('rewards-balance'),
                  style: MiftahType.amount(size: 30, color: Colors.white),
                ),
                Text(
                  l.balanceSub,
                  style: ar
                      ? MiftahType.ar(
                          size: 12,
                          color: Colors.white.withValues(alpha: 0.7),
                        )
                      : MiftahType.meta(
                          color: Colors.white.withValues(alpha: 0.7),
                        ),
                ),
              ],
            ),
          ),
          Container(
            width: 52,
            height: 52,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              gradient: MiftahGradients.gold,
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Icon(
              Icons.account_balance_wallet_rounded,
              color: MiftahColors.ink,
            ),
          ),
        ],
      ),
    );
  }
}

class _ScratchCards extends StatelessWidget {
  const _ScratchCards({required this.rewards});
  final List<DemoReward> rewards;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    if (rewards.isEmpty) {
      return EmptyState(
        icon: Icons.card_giftcard_rounded,
        title: l.noCards,
        subtitle: l.noCardsSub,
      );
    }
    return GridView.builder(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: MiftahSpacing.gap,
        crossAxisSpacing: MiftahSpacing.gap,
        childAspectRatio: 0.92,
      ),
      itemCount: rewards.length,
      itemBuilder: (context, i) => _ScratchTile(reward: rewards[i]),
    );
  }
}

class _ScratchTile extends StatelessWidget {
  const _ScratchTile({required this.reward});
  final DemoReward reward;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    return MiftahCard(
      key: Key('reward-tile-${reward.id}'),
      padding: EdgeInsets.zero,
      onTap: () => ScratchCardSheet.show(context, reward.id),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: ClipRRect(
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(MiftahRadii.card),
              ),
              child: reward.scratched
                  ? RewardFace(reward: reward, compact: true)
                  : Container(
                      decoration: const BoxDecoration(
                        gradient: MiftahGradients.gold,
                      ),
                      alignment: Alignment.center,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Text(
                            '✦',
                            style: TextStyle(
                              fontSize: 30,
                              color: MiftahColors.ink,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            l.tapToScratch,
                            style: MiftahType.sectionLabel(
                              color: MiftahColors.ink,
                            ),
                          ),
                        ],
                      ),
                    ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        reward.earnedFor(ar),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: ar
                            ? MiftahType.ar(size: 12, color: m.textSecondary)
                            : MiftahType.meta(color: m.textSecondary),
                      ),
                    ),
                    if (!reward.scratched)
                      MiftahBadge(l.newBadge, tone: MiftahTone.brass),
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  Formatters.date(reward.earnedAt.toIso8601String(), ar: ar),
                  style: MiftahType.mono(size: 11, color: m.textMuted),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Vouchers extends ConsumerWidget {
  const _Vouchers({required this.vouchers});
  final List<DemoReward> vouchers;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    if (vouchers.isEmpty) {
      return EmptyState(
        icon: Icons.confirmation_number_outlined,
        title: l.noVouchers,
        subtitle: l.noVouchersSub,
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      itemCount: vouchers.length,
      separatorBuilder: (_, _) => const SizedBox(height: MiftahSpacing.gap),
      itemBuilder: (context, i) {
        final r = vouchers[i];
        final v = r.voucher!;
        return MiftahCard(
          key: Key('voucher-${r.id}'),
          color: r.isUsed ? m.surfaceDim : null,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      v.brand(ar).toUpperCase(),
                      style: MiftahType.sectionLabel(
                        color: r.isUsed ? m.textMuted : MiftahColors.brassDeep,
                      ),
                    ),
                  ),
                  MiftahBadge(
                    r.isUsed ? l.used : l.active,
                    tone: r.isUsed ? MiftahTone.neutral : MiftahTone.success,
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                v.title(ar),
                style: ar
                    ? MiftahType.ar(size: 15, weight: FontWeight.w700)
                    : MiftahType.cardTitle(color: m.textPrimary),
              ),
              const SizedBox(height: 4),
              Text(
                v.terms(ar),
                style: ar
                    ? MiftahType.ar(size: 12, color: m.textSecondary)
                    : MiftahType.body(color: m.textSecondary),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: GestureDetector(
                      onTap: () async {
                        await Clipboard.setData(ClipboardData(text: v.code));
                        if (!context.mounted) return;
                        ScaffoldMessenger.of(
                          context,
                        ).showSnackBar(SnackBar(content: Text(l.copied)));
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 10,
                        ),
                        decoration: BoxDecoration(
                          color: MiftahColors.brassTint,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(
                            color: MiftahColors.brassTintBorder,
                          ),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                v.code,
                                style: MiftahType.mono(
                                  size: 14,
                                  color: MiftahColors.ink,
                                ),
                              ),
                            ),
                            const Icon(
                              Icons.copy_rounded,
                              size: 16,
                              color: MiftahColors.brassDeep,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  if (!r.isUsed)
                    TextButton(
                      key: Key('voucher-use-${r.id}'),
                      onPressed: () => ref
                          .read(walletDemoProvider.notifier)
                          .useVoucher(r.id),
                      child: Text(l.markUsed),
                    ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                l.expires(
                  Formatters.date(r.expiresAt.toIso8601String(), ar: ar),
                ),
                style: MiftahType.mono(size: 11, color: m.textMuted),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _History extends StatelessWidget {
  const _History({required this.txns});
  final List<DemoTxn> txns;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    if (txns.isEmpty) {
      return EmptyState(icon: Icons.history_rounded, title: l.noHistory);
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      itemCount: txns.length,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (context, i) {
        final t = txns[i];
        final credit = t.amount > 0;
        final (icon, tone) = switch (t.kind) {
          DemoTxnKind.cashback => (Icons.redeem_rounded, MiftahTone.success),
          DemoTxnKind.bonus => (Icons.auto_awesome_rounded, MiftahTone.brass),
          DemoTxnKind.walletSpent => (
            Icons.account_balance_wallet_rounded,
            MiftahTone.neutral,
          ),
          DemoTxnKind.payment => (Icons.receipt_long_rounded, MiftahTone.info),
        };
        return MiftahCard(
          key: Key('txn-${t.id}'),
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              MiftahIconTile(icon: icon, tone: tone, size: 40),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      t.title(ar),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: ar
                          ? MiftahType.ar(size: 13, weight: FontWeight.w700)
                          : MiftahType.body(
                              color: m.textPrimary,
                            ).copyWith(fontWeight: FontWeight.w600),
                    ),
                    Text(
                      [
                        Formatters.date(t.at.toIso8601String(), ar: ar),
                        if (t.reference != null) t.reference!,
                      ].join(' · '),
                      style: MiftahType.mono(size: 11, color: m.textMuted),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '${credit ? '+' : '−'} ${Formatters.currency(t.amount.abs())}',
                style: MiftahType.amount(
                  size: 15,
                  color: credit
                      ? m.success
                      : t.movesBalance
                      ? m.textPrimary
                      : m.textMuted,
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المكافآت' : 'Rewards';
  String get balanceLabel => ar ? 'رصيد المحفظة' : 'WALLET BALANCE';
  String get balanceSub => ar
      ? 'كاش باك يُخصم من دفعتك القادمة'
      : 'Cashback you can spend on your next payment';
  String scratchCards(int n) => ar
      ? (n > 0 ? 'بطاقات ($n)' : 'البطاقات')
      : (n > 0 ? 'Cards ($n)' : 'Cards');
  String vouchers(int n) => ar
      ? (n > 0 ? 'قسائم ($n)' : 'القسائم')
      : (n > 0 ? 'Vouchers ($n)' : 'Vouchers');
  String get history => ar ? 'السجل' : 'History';
  String get noCards => ar ? 'لا توجد بطاقات كشط بعد' : 'No scratch cards yet';
  String get noCardsSub => ar
      ? 'ادفع قسطاً إلكترونياً لتربح بطاقة'
      : 'Pay an instalment online to earn one';
  String get tapToScratch => ar ? 'اضغط للكشط' : 'TAP TO SCRATCH';
  String get newBadge => ar ? 'جديد' : 'NEW';
  String get noVouchers => ar ? 'لا توجد قسائم' : 'No vouchers';
  String get noVouchersSub =>
      ar ? 'القسائم التي تربحها تظهر هنا' : 'Vouchers you win show up here';
  String get used => ar ? 'مستخدمة' : 'USED';
  String get active => ar ? 'فعّالة' : 'ACTIVE';
  String get copied => ar ? 'تم نسخ الرمز' : 'Code copied';
  String get markUsed => ar ? 'تم استخدامها' : 'Mark used';
  String expires(String date) => ar ? 'تنتهي $date' : 'Expires $date';
  String get noHistory => ar ? 'لا توجد حركات بعد' : 'No activity yet';
}
