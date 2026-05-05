import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

final _myPaymentsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final service = ref.watch(_paymentServiceProvider);
  return service.getMyPayments();
});

/// Renter Pay Rent screen — redesigned to the handoff
/// (mobile-renter.jsx · `RenterPay`).
///
/// Layout from top:
///   1. Compact header — back arrow + "Pay rent"
///   2. Centered amount — "AMOUNT DUE" eyebrow, big serif AED figure,
///      cheque/installment subtitle
///   3. Method picker — Submit cheque / Bank transfer / Card payment.
///      Selected tile gets sand-100 fill + filled radio.
///   4. Receipt summary — sand-inset card with base / late fee /
///      adjustments / divider / total
///   5. Full-width gold "Continue · AED X" button + "Secure" footnote
class PayRentScreen extends ConsumerStatefulWidget {
  const PayRentScreen({super.key, this.paymentId});

  final String? paymentId;

  @override
  ConsumerState<PayRentScreen> createState() => _PayRentScreenState();
}

enum _Method { cheque, bankTransfer, card }

class _PayRentScreenState extends ConsumerState<PayRentScreen> {
  _Method _selected = _Method.cheque;

  Map<String, dynamic>? _selectPayment(List<dynamic> payments) {
    if (payments.isEmpty) return null;
    if (widget.paymentId != null) {
      for (final p in payments) {
        if (p is Map<String, dynamic> &&
            p['id']?.toString() == widget.paymentId) {
          return p;
        }
      }
    }
    // Fall back to the next pending/overdue payment
    for (final p in payments) {
      if (p is Map<String, dynamic>) {
        final s = (p['status'] ?? '').toString();
        if (s == 'PENDING' || s == 'OVERDUE' || s == 'ONLINE_PENDING') {
          return p;
        }
      }
    }
    return payments.first is Map<String, dynamic>
        ? payments.first as Map<String, dynamic>
        : null;
  }

  @override
  Widget build(BuildContext context) {
    final paymentsAsync = ref.watch(_myPaymentsProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: paymentsAsync.when(
          loading: () => const Center(
            child: CircularProgressIndicator(color: AppColors.primary),
          ),
          error: (_, _) => _ErrorBody(
              onRetry: () => ref.invalidate(_myPaymentsProvider)),
          data: (payments) {
            final payment = _selectPayment(payments);
            if (payment == null) return const _EmptyBody();
            return _PayBody(
              payment: payment,
              selected: _selected,
              onMethodChange: (m) => setState(() => _selected = m),
              onContinue: () => _onContinue(payment),
            );
          },
        ),
      ),
    );
  }

  void _onContinue(Map<String, dynamic> payment) {
    final method = switch (_selected) {
      _Method.cheque => 'Cheque submission',
      _Method.bankTransfer => 'Bank transfer',
      _Method.card => 'Card payment',
    };
    // Online payment flows (Razorpay for card, instructions for transfer,
    // pickup scheduling for cheque) are out of scope here. Once those
    // handlers exist, dispatch on _selected and pass the resolved totals.
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$method — coming soon'),
        backgroundColor: AppColors.primary,
      ),
    );
  }
}

// ─── Body ───────────────────────────────────────────────────────────────────

class _PayBody extends StatelessWidget {
  const _PayBody({
    required this.payment,
    required this.selected,
    required this.onMethodChange,
    required this.onContinue,
  });

  final Map<String, dynamic> payment;
  final _Method selected;
  final ValueChanged<_Method> onMethodChange;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    final amount = ((payment['amount'] ?? 0) as num).toDouble();
    // PaymentScheduleDTO doesn't currently expose lateFee or adjustments.
    // Once the backend adds those fields, surface them as separate receipt
    // rows; until then the total equals amount so we keep the receipt
    // honest with just Base + Total.
    final total = amount;

    final installmentNum = payment['installmentNumber'];
    final dueLabel = Formatters.date(payment['dueDate']?.toString());
    final subtitle = installmentNum != null
        ? 'Cheque $installmentNum · due $dueLabel'
        : 'Due $dueLabel';

    return Stack(
      children: [
        SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(0, 12, 0, 130),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Compact header
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Row(
                  children: [
                    InkWell(
                      borderRadius: BorderRadius.circular(20),
                      onTap: () => context.pop(),
                      child: const Padding(
                        padding: EdgeInsets.all(10),
                        child: Icon(Icons.arrow_back,
                            size: 20, color: AppColors.textPrimary),
                      ),
                    ),
                    Text(
                      'Pay rent',
                      style: GoogleFonts.inter(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: AppColors.textPrimary,
                      ),
                    ),
                  ],
                ),
              ),
              // Centered amount
              const SizedBox(height: 20),
              _CenteredAmount(
                amount: amount,
                subtitle: subtitle,
              ),
              const SizedBox(height: 24),
              // Method picker
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: _SectionLabel(text: 'Pay with'),
              ),
              const SizedBox(height: 8),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Column(
                  children: [
                    _MethodTile(
                      method: _Method.cheque,
                      icon: Icons.receipt_long_outlined,
                      title: 'Submit cheque',
                      subtitle: 'Drop off at office or schedule pickup',
                      selected: selected == _Method.cheque,
                      onTap: onMethodChange,
                    ),
                    const SizedBox(height: 8),
                    _MethodTile(
                      method: _Method.bankTransfer,
                      icon: Icons.swap_horiz_outlined,
                      title: 'Bank transfer',
                      subtitle: 'Direct to RentAxis · Emirates NBD',
                      selected: selected == _Method.bankTransfer,
                      onTap: onMethodChange,
                    ),
                    const SizedBox(height: 8),
                    _MethodTile(
                      method: _Method.card,
                      icon: Icons.credit_card_outlined,
                      title: 'Card payment',
                      subtitle: '2.5% processing fee',
                      selected: selected == _Method.card,
                      onTap: onMethodChange,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 22),
              // Receipt summary — base + total only until the backend
              // exposes lateFee/adjustments on PaymentScheduleDTO.
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: _ReceiptSummary(amount: amount, total: total),
              ),
            ],
          ),
        ),
        // Bottom CTA pinned with secure footnote
        Positioned(
          left: 20,
          right: 20,
          bottom: 16,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: double.infinity,
                height: 50,
                child: ElevatedButton(
                  onPressed: onContinue,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.accent,
                    foregroundColor: AppColors.primary,
                    elevation: 0,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                  ),
                  child: Text(
                    'Continue · ${Formatters.currency(total)}',
                    style: GoogleFonts.inter(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Row(
                mainAxisSize: MainAxisSize.min,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.shield_outlined,
                      size: 12, color: AppColors.success),
                  const SizedBox(width: 6),
                  Text(
                    'Secure · Powered by RentAxis',
                    style: GoogleFonts.inter(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

}

class _CenteredAmount extends StatelessWidget {
  final double amount;
  final String subtitle;
  const _CenteredAmount({required this.amount, required this.subtitle});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Text(
            'AMOUNT DUE',
            style: GoogleFonts.inter(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.8,
              color: AppColors.textMuted,
            ),
          ),
          const SizedBox(height: 8),
          // Use the shared Formatters.currency so locale, symbol and
          // decimal precision stay consistent across the app.
          Text(
            Formatters.currency(amount),
            style: GoogleFonts.sourceSerif4(
              fontSize: 36,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.7,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            subtitle,
            style: GoogleFonts.inter(
              fontSize: 12,
              color: AppColors.textMuted,
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel({required this.text});

  @override
  Widget build(BuildContext context) {
    return Text(
      text.toUpperCase(),
      style: GoogleFonts.inter(
        fontSize: 12,
        fontWeight: FontWeight.w600,
        letterSpacing: 0.6,
        color: AppColors.textMuted,
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
    required this.onTap,
  });

  final _Method method;
  final bool selected;
  final IconData icon;
  final String title;
  final String subtitle;
  final ValueChanged<_Method> onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: () => onTap(method),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: selected ? AppColors.surface2 : AppColors.surface,
          border: Border.all(
            color: selected ? AppColors.primary : AppColors.border,
            width: selected ? 1.5 : 1,
          ),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: selected
                    ? AppColors.primary.withValues(alpha: 0.08)
                    : AppColors.background,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(
                icon,
                size: 18,
                color: selected ? AppColors.primary : AppColors.textSecondary,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: GoogleFonts.inter(
                      fontSize: 13.5,
                      fontWeight: FontWeight.w600,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 1),
                  Text(
                    subtitle,
                    style: GoogleFonts.inter(
                      fontSize: 11.5,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Container(
              width: 18,
              height: 18,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: selected ? AppColors.primary : Colors.transparent,
                border: Border.all(
                  color: selected
                      ? AppColors.primary
                      : AppColors.borderStrong,
                  width: 2,
                ),
              ),
              child: selected
                  ? const Icon(Icons.check,
                      size: 10, color: AppColors.accent)
                  : null,
            ),
          ],
        ),
      ),
    );
  }
}

class _ReceiptSummary extends StatelessWidget {
  final double amount;
  final double total;
  const _ReceiptSummary({required this.amount, required this.total});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface2,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        children: [
          _Row(label: 'Base rent', value: Formatters.currency(amount)),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Divider(height: 1, color: AppColors.border),
          ),
          _Row(
            label: 'Total',
            value: Formatters.currency(total),
            bold: true,
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  final String label;
  final String value;
  final bool bold;
  const _Row({
    required this.label,
    required this.value,
    this.bold = false,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            label,
            style: GoogleFonts.inter(
              fontSize: 13,
              fontWeight: bold ? FontWeight.w600 : FontWeight.w400,
              color: bold ? AppColors.textPrimary : AppColors.textSecondary,
            ),
          ),
          Text(
            value,
            style: GoogleFonts.jetBrainsMono(
              fontSize: 13,
              fontWeight: bold ? FontWeight.w700 : FontWeight.w500,
              color: AppColors.textPrimary,
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Empty / error states ───────────────────────────────────────────────────

class _EmptyBody extends StatelessWidget {
  const _EmptyBody();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.receipt_long_outlined,
              size: 56, color: AppColors.textMuted),
          const SizedBox(height: 14),
          Text(
            'No payments due',
            style: GoogleFonts.sourceSerif4(
              fontSize: 22,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            "You're all caught up — nothing to pay right now.",
            textAlign: TextAlign.center,
            style: GoogleFonts.inter(
                fontSize: 13, color: AppColors.textMuted),
          ),
          const SizedBox(height: 20),
          ElevatedButton(
            onPressed: () => context.pop(),
            child: const Text('Back to home'),
          ),
        ],
      ),
    );
  }
}

class _ErrorBody extends StatelessWidget {
  const _ErrorBody({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.error_outline,
              size: 56, color: AppColors.danger),
          const SizedBox(height: 14),
          Text(
            'Could not load payments',
            style: GoogleFonts.sourceSerif4(
              fontSize: 20,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 16),
          ElevatedButton(onPressed: onRetry, child: const Text('Retry')),
        ],
      ),
    );
  }
}
