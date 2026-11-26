import 'dart:async';

import 'package:flutter/foundation.dart'
    show TargetPlatform, defaultTargetPlatform;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../demo/wallet_demo.dart';
import '../../providers/renter_data.dart';
import 'scratch_card.dart';

enum _Stage { review, processing, success }

/// Demo checkout for one due instalment — the shape of a Razorpay-style
/// hosted checkout (order summary, saved card, new card, Apple / Google Pay,
/// net banking, wallet balance), then a processing screen and a success
/// screen that hands the renter a scratch card.
///
/// No gateway is contacted. The "payment" is recorded in
/// [walletDemoProvider] and the row reads as paid everywhere the app lists
/// instalments (see `applyDemoPayments`). Self-fetching by cheque id, like
/// the other pushed screens: the router rebuilds on `authProvider` and would
/// discard an `extra` payload.
class CheckoutScreen extends ConsumerStatefulWidget {
  const CheckoutScreen({super.key, required this.chequeId, this.platform});

  final String chequeId;

  /// Which wallet button to offer (Apple Pay on iOS, Google Pay elsewhere);
  /// defaults to the phone's own. A seam for tests.
  final TargetPlatform? platform;

  @override
  ConsumerState<CheckoutScreen> createState() => _CheckoutScreenState();
}

class _CheckoutScreenState extends ConsumerState<CheckoutScreen> {
  _Stage _stage = _Stage.review;
  DemoPayMethod _method = DemoPayMethod.card;
  bool _useWallet = false;
  String? _bank;
  int _step = 0;
  DemoPayment? _payment;
  final _cardNumber = TextEditingController();
  final _cardExpiry = TextEditingController();
  final _cardCvv = TextEditingController();
  Timer? _timer;

  static const _banks = [
    'Emirates NBD',
    'ADCB',
    'FAB',
    'Mashreq',
    'RAKBANK',
    'Dubai Islamic Bank',
  ];

  @override
  void dispose() {
    _timer?.cancel();
    _cardNumber.dispose();
    _cardExpiry.dispose();
    _cardCvv.dispose();
    super.dispose();
  }

  bool get _isApple =>
      (widget.platform ?? defaultTargetPlatform) == TargetPlatform.iOS;

  DemoPayMethod get _nativeWallet =>
      _isApple ? DemoPayMethod.applePay : DemoPayMethod.googlePay;

  /// The gateway walks three steps, 700 ms apart, then the payment lands.
  void _startPayment(Map<String, dynamic> row) {
    setState(() {
      _stage = _Stage.processing;
      _step = 0;
    });
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 700), (t) {
      if (!mounted) {
        t.cancel();
        return;
      }
      if (_step < 2) {
        setState(() => _step++);
        return;
      }
      t.cancel();
      final payment = ref
          .read(walletDemoProvider.notifier)
          .pay(row: row, method: _method, useWallet: _useWallet);
      HapticFeedback.heavyImpact();
      setState(() {
        _payment = payment;
        _stage = _Stage.success;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    final payments = ref.watch(renterPaymentsProvider);
    final wallet = ref.watch(walletDemoProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: payments.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => SafeArea(
          child: ErrorState(
            message: l.loadFailed,
            onRetry: () => ref.invalidate(renterPaymentsProvider),
          ),
        ),
        data: (rows) {
          final row = rows
              .where((r) => r['id']?.toString() == widget.chequeId)
              .firstOrNull;
          if (row == null) {
            return SafeArea(
              child: EmptyState(
                icon: Icons.receipt_long_outlined,
                title: l.notFound,
              ),
            );
          }
          // Opened again for a row paid earlier in this session: straight to
          // the receipt, with its card.
          final paidBefore = wallet.payments[widget.chequeId];
          if (paidBefore != null && _payment == null) {
            _payment = paidBefore;
            _stage = _Stage.success;
          }
          return switch (_stage) {
            _Stage.review => _review(context, row, wallet),
            _Stage.processing => _processing(context),
            _Stage.success => _success(context, row, wallet),
          };
        },
      ),
    );
  }

  // ─── Review ───────────────────────────────────────────────────────────────

  Widget _review(
    BuildContext context,
    Map<String, dynamic> row,
    WalletDemoState wallet,
  ) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    final amount = (row['payable'] as num?) ?? (row['amount'] as num?) ?? 0;
    final walletApplied = _useWallet
        ? (wallet.balance < amount ? wallet.balance : amount)
        : 0;
    final toPay = amount - walletApplied;
    final walletCoversAll = _useWallet && toPay <= 0;
    final merchant = '${row['propertyName'] ?? 'Miftah'}';
    final instalment = row['penaltyAssessmentId'] != null
        ? l.penaltyCharge
        : l.instalment(row['installmentNumber']);

    final canPay =
        walletCoversAll ||
        switch (_method) {
          DemoPayMethod.newCard =>
            _cardNumber.text.replaceAll(' ', '').length >= 12 &&
                _cardExpiry.text.length >= 4 &&
                _cardCvv.text.length >= 3,
          DemoPayMethod.netBanking => _bank != null,
          _ => true,
        };

    return Column(
      children: [
        _Header(
          merchant: merchant,
          amount: toPay,
          subtitle: '$instalment · ${row['unitIdentifier'] ?? ''}',
          onClose: () => context.pop(),
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
            children: [
              MiftahSectionLabel(l.orderSummary),
              const SizedBox(height: 8),
              MiftahCard(
                child: Column(
                  children: [
                    _line(context, instalment, merchant),
                    _line(
                      context,
                      l.dueDate,
                      Formatters.date(row['dueDate'] as String?, ar: ar),
                    ),
                    _line(context, l.amount, Formatters.currency(amount)),
                    _line(
                      context,
                      l.convenienceFee,
                      l.waived,
                      valueColor: m.success,
                    ),
                    if (walletApplied > 0)
                      _line(
                        context,
                        l.walletApplied,
                        '− ${Formatters.currency(walletApplied)}',
                        valueColor: m.success,
                      ),
                    Divider(height: 18, color: m.divider),
                    _line(
                      context,
                      l.toPay,
                      Formatters.currency(toPay),
                      bold: true,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: MiftahSpacing.gap),
              // Wallet balance — the Paytm move: spend cashback on the bill.
              MiftahCard(
                padding: const EdgeInsets.fromLTRB(14, 6, 6, 6),
                child: Row(
                  children: [
                    const MiftahIconTile(
                      icon: Icons.account_balance_wallet_rounded,
                      size: 38,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            l.useWallet,
                            style: ar
                                ? MiftahType.ar(
                                    size: 14,
                                    weight: FontWeight.w700,
                                  )
                                : MiftahType.cardTitle(color: m.textPrimary),
                          ),
                          Text(
                            l.walletAvailable(wallet.balance),
                            style: ar
                                ? MiftahType.ar(size: 12, color: m.textMuted)
                                : MiftahType.meta(color: m.textMuted),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      key: const Key('checkout-use-wallet'),
                      value: _useWallet,
                      onChanged: wallet.balance <= 0
                          ? null
                          : (v) => setState(() => _useWallet = v),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 18),
              if (walletCoversAll)
                MiftahCard(
                  color: m.isDark ? m.successBg : MiftahColors.successTint,
                  borderColor: Colors.transparent,
                  child: Row(
                    children: [
                      Icon(Icons.check_circle_rounded, color: m.success),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          l.coveredByWallet,
                          style: ar
                              ? MiftahType.ar(size: 13, color: m.textPrimary)
                              : MiftahType.body(color: m.textPrimary),
                        ),
                      ),
                    ],
                  ),
                )
              else ...[
                MiftahSectionLabel(l.payWith),
                const SizedBox(height: 8),
                _MethodTile(
                  method: DemoPayMethod.card,
                  selected: _method,
                  icon: Icons.credit_card_rounded,
                  title: 'Visa •••• 4242',
                  subtitle: l.savedCard,
                  onSelect: _select,
                ),
                _MethodTile(
                  method: DemoPayMethod.newCard,
                  selected: _method,
                  icon: Icons.add_card_rounded,
                  title: l.newCard,
                  subtitle: l.newCardSub,
                  onSelect: _select,
                  expanded: _newCardForm(context),
                ),
                _MethodTile(
                  method: _nativeWallet,
                  selected: _method,
                  icon: _isApple ? Icons.apple : Icons.g_mobiledata_rounded,
                  title: _isApple ? 'Apple Pay' : 'Google Pay',
                  subtitle: l.nativeWalletSub,
                  onSelect: _select,
                ),
                _MethodTile(
                  method: DemoPayMethod.netBanking,
                  selected: _method,
                  icon: Icons.account_balance_rounded,
                  title: l.netBanking,
                  subtitle: _bank ?? l.netBankingSub,
                  onSelect: _select,
                  expanded: Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final bank in _banks)
                        ChoiceChip(
                          key: Key('checkout-bank-$bank'),
                          label: Text(bank),
                          selected: _bank == bank,
                          onSelected: (_) => setState(() => _bank = bank),
                        ),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 18),
              Row(
                children: [
                  Icon(Icons.lock_rounded, size: 14, color: m.textMuted),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      l.secureNote,
                      style: ar
                          ? MiftahType.ar(size: 11, color: m.textMuted)
                          : MiftahType.meta(color: m.textMuted),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        _BottomBar(
          child: MiftahGoldButton(
            key: const Key('checkout-pay'),
            label: walletCoversAll
                ? l.payWithWallet
                : l.pay(Formatters.currency(toPay)),
            icon: const Icon(Icons.lock_rounded, size: 18),
            onPressed: canPay ? () => _startPayment(row) : null,
          ),
        ),
      ],
    );
  }

  void _select(DemoPayMethod method) => setState(() => _method = method);

  Widget _newCardForm(BuildContext context) {
    final l = _L(context.isAr);
    return Column(
      children: [
        TextField(
          key: const Key('checkout-card-number'),
          controller: _cardNumber,
          keyboardType: TextInputType.number,
          inputFormatters: [
            FilteringTextInputFormatter.digitsOnly,
            LengthLimitingTextInputFormatter(16),
          ],
          onChanged: (_) => setState(() {}),
          decoration: InputDecoration(
            labelText: l.cardNumber,
            hintText: '4111 1111 1111 1111',
            prefixIcon: const Icon(Icons.credit_card_rounded),
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: TextField(
                key: const Key('checkout-card-expiry'),
                controller: _cardExpiry,
                keyboardType: TextInputType.number,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(4),
                ],
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: l.expiry,
                  hintText: 'MMYY',
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: TextField(
                key: const Key('checkout-card-cvv'),
                controller: _cardCvv,
                keyboardType: TextInputType.number,
                obscureText: true,
                inputFormatters: [
                  FilteringTextInputFormatter.digitsOnly,
                  LengthLimitingTextInputFormatter(4),
                ],
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  labelText: 'CVV',
                  hintText: '•••',
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _line(
    BuildContext context,
    String label,
    String value, {
    Color? valueColor,
    bool bold = false,
  }) {
    final ar = context.isAr;
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: ar
                  ? MiftahType.ar(
                      size: 13,
                      weight: bold ? FontWeight.w700 : FontWeight.w400,
                      color: bold ? m.textPrimary : m.textSecondary,
                    )
                  : (bold
                        ? MiftahType.cardTitle(color: m.textPrimary)
                        : MiftahType.body(color: m.textSecondary)),
            ),
          ),
          Text(
            value,
            style: bold
                ? MiftahType.amount(size: 18, color: m.textPrimary)
                : MiftahType.body(
                    color: valueColor ?? m.textPrimary,
                  ).copyWith(fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }

  // ─── Processing ───────────────────────────────────────────────────────────

  Widget _processing(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    final steps = [l.stepBank, l.stepAuthorise, l.stepConfirm];
    return SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(
                width: 56,
                height: 56,
                child: CircularProgressIndicator(
                  strokeWidth: 4,
                  color: MiftahColors.brass,
                ),
              ),
              const SizedBox(height: 24),
              Text(
                l.processingTitle,
                key: const Key('checkout-processing'),
                style: ar
                    ? MiftahType.ar(size: 18, weight: FontWeight.w700)
                    : MiftahType.title(color: m.textPrimary),
              ),
              const SizedBox(height: 6),
              Text(
                l.processingSub,
                textAlign: TextAlign.center,
                style: ar
                    ? MiftahType.ar(size: 13, color: m.textMuted)
                    : MiftahType.body(color: m.textMuted),
              ),
              const SizedBox(height: 28),
              for (var i = 0; i < steps.length; i++)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      AnimatedSwitcher(
                        duration: const Duration(milliseconds: 250),
                        child: i < _step
                            ? Icon(
                                Icons.check_circle_rounded,
                                key: ValueKey('done-$i'),
                                color: m.success,
                                size: 22,
                              )
                            : i == _step
                            ? const SizedBox(
                                key: ValueKey('active'),
                                width: 22,
                                height: 22,
                                child: Padding(
                                  padding: EdgeInsets.all(3),
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2.5,
                                    color: MiftahColors.brass,
                                  ),
                                ),
                              )
                            : Icon(
                                Icons.radio_button_unchecked_rounded,
                                key: ValueKey('todo-$i'),
                                color: m.border,
                                size: 22,
                              ),
                      ),
                      const SizedBox(width: 12),
                      Text(
                        steps[i],
                        style: ar
                            ? MiftahType.ar(
                                size: 14,
                                color: i <= _step ? m.textPrimary : m.textMuted,
                              )
                            : MiftahType.body(
                                size: 14,
                                color: i <= _step ? m.textPrimary : m.textMuted,
                              ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  // ─── Success ──────────────────────────────────────────────────────────────

  Widget _success(
    BuildContext context,
    Map<String, dynamic> row,
    WalletDemoState wallet,
  ) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    final payment = _payment!;
    final reward = wallet.reward(payment.rewardId);
    final merchant = '${row['propertyName'] ?? 'Miftah'}';

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 48, 20, 24),
            children: [
              Center(
                child: TweenAnimationBuilder<double>(
                  tween: Tween(begin: 0.6, end: 1),
                  duration: const Duration(milliseconds: 500),
                  curve: Curves.elasticOut,
                  builder: (_, scale, child) =>
                      Transform.scale(scale: scale, child: child),
                  child: Container(
                    width: 84,
                    height: 84,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: m.isDark ? m.successBg : MiftahColors.successTint,
                    ),
                    child: Icon(
                      Icons.check_rounded,
                      size: 48,
                      color: m.success,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              Text(
                l.successTitle,
                key: const Key('checkout-success'),
                textAlign: TextAlign.center,
                style: ar
                    ? MiftahType.ar(size: 20, weight: FontWeight.w700)
                    : MiftahType.title(color: m.textPrimary),
              ),
              const SizedBox(height: 6),
              Text(
                Formatters.currency(payment.amount),
                textAlign: TextAlign.center,
                style: MiftahType.amount(size: 36, color: m.textPrimary),
              ),
              Text(
                l.paidTo(merchant),
                textAlign: TextAlign.center,
                style: ar
                    ? MiftahType.ar(size: 13, color: m.textMuted)
                    : MiftahType.body(color: m.textMuted),
              ),
              const SizedBox(height: 22),
              MiftahCard(
                child: Column(
                  children: [
                    _line(context, l.reference, payment.reference),
                    _line(context, l.method, _methodLabel(payment, l)),
                    if (payment.walletUsed > 0)
                      _line(
                        context,
                        l.walletApplied,
                        '− ${Formatters.currency(payment.walletUsed)}',
                        valueColor: m.success,
                      ),
                    _line(
                      context,
                      l.date,
                      Formatters.date(payment.at.toIso8601String(), ar: ar),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: MiftahSpacing.gap),
              if (reward != null)
                reward.scratched
                    ? _RewardCollected(reward: reward)
                    : _ScratchPrompt(
                        onScratch: () =>
                            ScratchCardSheet.show(context, reward.id),
                      ),
              const SizedBox(height: 10),
              Center(
                child: TextButton.icon(
                  onPressed: () => ScaffoldMessenger.of(
                    context,
                  ).showSnackBar(SnackBar(content: Text(l.receiptSent))),
                  icon: const Icon(Icons.receipt_long_outlined, size: 18),
                  label: Text(l.receipt),
                ),
              ),
            ],
          ),
        ),
        _BottomBar(
          child: MiftahButton.primary(
            key: const Key('checkout-done'),
            label: l.done,
            onPressed: () => context.go('/payments'),
          ),
        ),
      ],
    );
  }

  String _methodLabel(DemoPayment p, _L l) => switch (p.method) {
    DemoPayMethod.card => 'Visa •••• 4242',
    DemoPayMethod.newCard =>
      '${l.cardWord} •••• ${_cardNumber.text.length >= 4 ? _cardNumber.text.substring(_cardNumber.text.length - 4) : '0000'}',
    DemoPayMethod.applePay => 'Apple Pay',
    DemoPayMethod.googlePay => 'Google Pay',
    DemoPayMethod.netBanking => _bank ?? l.netBanking,
    DemoPayMethod.wallet => l.miftahWallet,
  };
}

// ─── Pieces ─────────────────────────────────────────────────────────────────

/// Ink header with the merchant and the amount — the hosted-checkout look.
class _Header extends StatelessWidget {
  const _Header({
    required this.merchant,
    required this.amount,
    required this.subtitle,
    required this.onClose,
  });

  final String merchant;
  final num amount;
  final String subtitle;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    return Container(
      color: MiftahColors.ink,
      padding: EdgeInsets.fromLTRB(
        12,
        MediaQuery.paddingOf(context).top + 6,
        20,
        22,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              IconButton(
                key: const Key('checkout-close'),
                onPressed: onClose,
                icon: const Icon(Icons.close_rounded, color: Colors.white),
              ),
              const Icon(
                Icons.lock_rounded,
                size: 14,
                color: MiftahColors.brassPale,
              ),
              const SizedBox(width: 6),
              Text(
                l.secureCheckout,
                style: MiftahType.sectionLabel(color: MiftahColors.brassPale),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Padding(
            padding: const EdgeInsets.only(left: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  merchant,
                  style: ar
                      ? MiftahType.ar(
                          size: 15,
                          weight: FontWeight.w700,
                          color: Colors.white,
                        )
                      : MiftahType.cardTitle(color: Colors.white),
                ),
                const SizedBox(height: 4),
                Text(
                  Formatters.currency(amount),
                  key: const Key('checkout-amount'),
                  style: MiftahType.amount(
                    size: 34,
                    color: MiftahColors.brassPale,
                  ),
                ),
                Text(
                  subtitle,
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
        ],
      ),
    );
  }
}

class _MethodTile extends StatelessWidget {
  const _MethodTile({
    required this.method,
    required this.selected,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onSelect,
    this.expanded,
  });

  final DemoPayMethod method;
  final DemoPayMethod selected;
  final IconData icon;
  final String title;
  final String subtitle;
  final ValueChanged<DemoPayMethod> onSelect;

  /// Shown under the row while this method is selected.
  final Widget? expanded;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final m = context.miftah;
    final isSelected = method == selected;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: MiftahCard(
        key: Key('checkout-method-${method.name}'),
        padding: const EdgeInsets.fromLTRB(14, 10, 10, 10),
        borderColor: isSelected ? MiftahColors.brass : null,
        onTap: () => onSelect(method),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                MiftahIconTile(
                  icon: icon,
                  size: 38,
                  tone: isSelected ? MiftahTone.brass : MiftahTone.neutral,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: ar
                            ? MiftahType.ar(size: 14, weight: FontWeight.w700)
                            : MiftahType.cardTitle(color: m.textPrimary),
                      ),
                      Text(
                        subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: ar
                            ? MiftahType.ar(size: 12, color: m.textMuted)
                            : MiftahType.meta(color: m.textMuted),
                      ),
                    ],
                  ),
                ),
                Icon(
                  isSelected
                      ? Icons.radio_button_checked_rounded
                      : Icons.radio_button_unchecked_rounded,
                  color: isSelected ? MiftahColors.brass : m.border,
                ),
              ],
            ),
            if (isSelected && expanded != null) ...[
              const SizedBox(height: 12),
              expanded!,
              const SizedBox(height: 4),
            ],
          ],
        ),
      ),
    );
  }
}

class _BottomBar extends StatelessWidget {
  const _BottomBar({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border(top: BorderSide(color: m.border)),
      ),
      padding: EdgeInsets.fromLTRB(
        20,
        12,
        20,
        MediaQuery.paddingOf(context).bottom + 12,
      ),
      child: child,
    );
  }
}

/// Gold card on the receipt: "You've earned a scratch card".
class _ScratchPrompt extends StatelessWidget {
  const _ScratchPrompt({required this.onScratch});
  final VoidCallback onScratch;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    return MiftahGoldCard(
      child: Row(
        children: [
          Container(
            width: 52,
            height: 52,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: MiftahColors.ink,
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Text(
              '✦',
              style: TextStyle(fontSize: 26, color: MiftahColors.brassPale),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.earnedCard,
                  style: ar
                      ? MiftahType.ar(
                          size: 14,
                          weight: FontWeight.w700,
                          color: MiftahColors.ink,
                        )
                      : MiftahType.cardTitle(color: MiftahColors.ink),
                ),
                const SizedBox(height: 2),
                Text(
                  l.earnedCardSub,
                  style: ar
                      ? MiftahType.ar(
                          size: 12,
                          color: MiftahColors.ink.withValues(alpha: 0.7),
                        )
                      : MiftahType.meta(
                          color: MiftahColors.ink.withValues(alpha: 0.7),
                        ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          FilledButton(
            key: const Key('checkout-scratch'),
            onPressed: onScratch,
            style: FilledButton.styleFrom(
              backgroundColor: MiftahColors.ink,
              foregroundColor: MiftahColors.brassPale,
              // Same reason as the wallet strip's Rewards button: the theme's
              // infinite minimum width is invalid beside an Expanded.
              minimumSize: const Size(0, 44),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            child: Text(l.scratch),
          ),
        ],
      ),
    );
  }
}

class _RewardCollected extends StatelessWidget {
  const _RewardCollected({required this.reward});
  final DemoReward reward;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = _L(ar);
    final m = context.miftah;
    return MiftahCard(
      key: const Key('checkout-reward-collected'),
      padding: EdgeInsets.zero,
      child: Row(
        children: [
          SizedBox(
            width: 128,
            height: 96,
            child: ClipRRect(
              borderRadius: const BorderRadiusDirectional.horizontal(
                start: Radius.circular(MiftahRadii.card),
              ),
              child: RewardFace(reward: reward, compact: true),
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    switch (reward.kind) {
                      DemoRewardKind.cashback => l.cashbackCollected,
                      DemoRewardKind.voucher => l.voucherSaved,
                      DemoRewardKind.betterLuck => l.noPrize,
                    },
                    style: ar
                        ? MiftahType.ar(size: 14, weight: FontWeight.w700)
                        : MiftahType.cardTitle(color: m.textPrimary),
                  ),
                  const SizedBox(height: 4),
                  GestureDetector(
                    onTap: () => context.go('/payments/rewards'),
                    child: Text(
                      l.viewInWallet,
                      style: ar
                          ? MiftahType.ar(
                              size: 12,
                              weight: FontWeight.w700,
                              color: MiftahColors.brassDeep,
                            )
                          : MiftahType.button(
                              size: 12.5,
                              color: MiftahColors.brassDeep,
                            ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _L {
  _L(this.ar);
  final bool ar;

  String get loadFailed =>
      ar ? 'تعذر تحميل الدفعات' : 'Could not load payments';
  String get notFound =>
      ar ? 'لم يتم العثور على هذه الدفعة' : 'This payment was not found';
  String get secureCheckout => ar ? 'دفع آمن' : 'SECURE CHECKOUT';
  String get orderSummary => ar ? 'ملخص الطلب' : 'ORDER SUMMARY';
  String get penaltyCharge => ar ? 'غرامة / رسوم' : 'Penalty / charge';
  String instalment(dynamic n) => n == null
      ? (ar ? 'قسط' : 'Instalment')
      : (ar ? 'قسط $n' : 'Instalment $n');
  String get dueDate => ar ? 'تاريخ الاستحقاق' : 'Due date';
  String get amount => ar ? 'المبلغ' : 'Amount';
  String get convenienceFee => ar ? 'رسوم الخدمة' : 'Convenience fee';
  String get waived => ar ? 'معفاة' : 'Waived';
  String get walletApplied => ar ? 'رصيد المحفظة' : 'Wallet balance';
  String get toPay => ar ? 'المطلوب دفعه' : 'To pay';
  String get useWallet => ar ? 'استخدم رصيد المحفظة' : 'Use wallet balance';
  String walletAvailable(num b) => ar
      ? '${Formatters.currency(b)} متاح'
      : '${Formatters.currency(b)} available';
  String get coveredByWallet => ar
      ? 'رصيد محفظتك يغطي هذه الدفعة بالكامل.'
      : 'Your wallet balance covers this payment in full.';
  String get payWith => ar ? 'ادفع بواسطة' : 'PAY WITH';
  String get savedCard => ar ? 'بطاقة محفوظة' : 'Saved card';
  String get newCard => ar ? 'بطاقة جديدة' : 'New card';
  String get newCardSub =>
      ar ? 'فيزا، ماستركارد، أمريكان إكسبريس' : 'Visa, Mastercard, Amex';
  String get nativeWalletSub =>
      ar ? 'ادفع ببصمة الإصبع أو الوجه' : 'Pay with Touch ID or Face ID';
  String get netBanking => ar ? 'الخدمات المصرفية عبر الإنترنت' : 'Net banking';
  String get netBankingSub => ar ? 'اختر بنكك' : 'Choose your bank';
  String get cardNumber => ar ? 'رقم البطاقة' : 'Card number';
  String get expiry => ar ? 'الانتهاء' : 'Expiry';
  String get secureNote => ar
      ? 'تُعالج الدفعات بأمان. لا يحتفظ مفتاح ببيانات بطاقتك.'
      : 'Payments are processed securely. Miftah never stores your card details.';
  String pay(String amount) => ar ? 'ادفع $amount' : 'Pay $amount';
  String get payWithWallet => ar ? 'ادفع من المحفظة' : 'Pay with wallet';
  String get processingTitle => ar ? 'جارٍ معالجة الدفع' : 'Processing payment';
  String get processingSub =>
      ar ? 'لا تغلق هذه الشاشة' : "Don't close this screen";
  String get stepBank => ar ? 'الاتصال بالبنك' : 'Contacting your bank';
  String get stepAuthorise => ar ? 'تفويض الدفع' : 'Authorising payment';
  String get stepConfirm => ar ? 'التأكيد مع مفتاح' : 'Confirming with Miftah';
  String get successTitle => ar ? 'تم الدفع بنجاح' : 'Payment successful';
  String paidTo(String merchant) => ar ? 'إلى $merchant' : 'to $merchant';
  String get reference => ar ? 'المرجع' : 'Reference';
  String get method => ar ? 'طريقة الدفع' : 'Method';
  String get date => ar ? 'التاريخ' : 'Date';
  String get cardWord => ar ? 'بطاقة' : 'Card';
  String get miftahWallet => ar ? 'محفظة مفتاح' : 'Miftah Wallet';
  String get earnedCard =>
      ar ? 'ربحت بطاقة كشط!' : "You've earned a scratch card!";
  String get earnedCardSub =>
      ar ? 'كاش باك أو قسيمة في انتظارك' : 'Cashback or a voucher is waiting';
  String get scratch => ar ? 'اكشط' : 'Scratch';
  String get cashbackCollected =>
      ar ? 'أُضيف الكاش باك إلى محفظتك' : 'Cashback added to your wallet';
  String get voucherSaved =>
      ar ? 'حُفظت القسيمة في محفظتك' : 'Voucher saved to your wallet';
  String get noPrize => ar ? 'لا جائزة هذه المرة' : 'No prize this time';
  String get viewInWallet => ar ? 'عرض في المحفظة' : 'View in wallet';
  String get receipt => ar ? 'الإيصال' : 'Receipt';
  String get receiptSent => ar
      ? 'سيصلك الإيصال بالبريد الإلكتروني'
      : 'Your receipt is on its way by email';
  String get done => ar ? 'تم' : 'Done';
}
