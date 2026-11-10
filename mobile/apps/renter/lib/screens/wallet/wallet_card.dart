import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../demo/wallet_demo.dart';

/// The ink wallet strip at the top of the Wallet tab (demo): cashback
/// balance on the left, a Rewards button on the right that carries a badge
/// while scratch cards are waiting.
class WalletDemoCard extends ConsumerWidget {
  const WalletDemoCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    final wallet = ref.watch(walletDemoProvider);
    final newCards = wallet.unscratchedCount;
    final vouchers = wallet.availableVoucherCount;
    final muted = Colors.white.withValues(alpha: 0.7);

    return Container(
      key: const Key('wallet-demo-card'),
      padding: const EdgeInsets.fromLTRB(18, 16, 14, 16),
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
                  ar ? 'محفظة مفتاح' : 'MIFTAH WALLET',
                  style: MiftahType.sectionLabel(color: MiftahColors.brassPale),
                ),
                const SizedBox(height: 4),
                Text(
                  Formatters.currency(wallet.balance),
                  key: const Key('wallet-demo-balance'),
                  style: MiftahType.amount(size: 26, color: Colors.white),
                ),
                const SizedBox(height: 2),
                Text(
                  _summary(ar, newCards, vouchers),
                  style: ar
                      ? MiftahType.ar(size: 12, color: muted)
                      : MiftahType.meta(color: muted),
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Stack(
            clipBehavior: Clip.none,
            children: [
              FilledButton.icon(
                key: const Key('wallet-demo-rewards'),
                onPressed: () => context.push('/payments/rewards'),
                style: FilledButton.styleFrom(
                  backgroundColor: MiftahColors.brass,
                  foregroundColor: MiftahColors.ink,
                  // The app theme gives filled buttons an infinite minimum
                  // width; beside an Expanded in a Row that is an invalid
                  // constraint, so this one sizes to its label.
                  minimumSize: const Size(0, 44),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 12,
                  ),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                icon: const Icon(Icons.redeem_rounded, size: 18),
                label: Text(
                  ar ? 'المكافآت' : 'Rewards',
                  style: ar
                      ? MiftahType.ar(size: 13, weight: FontWeight.w700)
                      : MiftahType.button(size: 13),
                ),
              ),
              if (newCards > 0)
                PositionedDirectional(
                  top: -8,
                  end: -6,
                  child: Container(
                    width: 22,
                    height: 22,
                    alignment: Alignment.center,
                    decoration: const BoxDecoration(
                      shape: BoxShape.circle,
                      color: MiftahColors.dangerBright,
                    ),
                    child: Text(
                      '$newCards',
                      key: const Key('wallet-demo-new-badge'),
                      style: MiftahType.badge(color: Colors.white),
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  static String _summary(bool ar, int cards, int vouchers) {
    final parts = <String>[
      if (cards > 0)
        ar
            ? (cards == 1 ? 'بطاقة كشط واحدة' : '$cards بطاقات كشط')
            : '$cards scratch card${cards == 1 ? '' : 's'} to open',
      if (vouchers > 0)
        ar
            ? (vouchers == 1 ? 'قسيمة واحدة' : '$vouchers قسائم')
            : '$vouchers voucher${vouchers == 1 ? '' : 's'}',
    ];
    if (parts.isEmpty) {
      return ar
          ? 'كاش باك على كل دفعة إلكترونية'
          : 'Cashback on every online payment';
    }
    return parts.join(' · ');
  }
}
