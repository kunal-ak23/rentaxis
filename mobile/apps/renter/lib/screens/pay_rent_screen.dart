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

class PayRentScreen extends ConsumerStatefulWidget {
  const PayRentScreen({super.key, this.paymentId});

  final String? paymentId;

  @override
  ConsumerState<PayRentScreen> createState() => _PayRentScreenState();
}

class _PayRentScreenState extends ConsumerState<PayRentScreen> {
  int _selectedMethod = 0;

  Map<String, dynamic>? _selectPayment(List<dynamic> payments) {
    if (payments.isEmpty) return null;
    if (widget.paymentId != null) {
      for (final p in payments) {
        if (p is Map<String, dynamic> && p['id']?.toString() == widget.paymentId) {
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
          error: (_, _) => _ErrorBody(onRetry: () => ref.invalidate(_myPaymentsProvider)),
          data: (payments) {
            final payment = _selectPayment(payments);
            if (payment == null) {
              return const _EmptyBody();
            }
            return _PayBody(
              payment: payment,
              selectedMethod: _selectedMethod,
              onMethodChange: (i) => setState(() => _selectedMethod = i),
              onContinue: () => _onContinue(payment),
            );
          },
        ),
      ),
    );
  }

  void _onContinue(Map<String, dynamic> payment) {
    // TODO: wire to Razorpay flow / record-payment API once method-specific
    // handlers are designed. Keeping a clear scaffold for product to plug in.
    final method = ['Cheque', 'Bank transfer', 'Card'][_selectedMethod];
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('$method payment for ${payment['id']} — coming soon'),
        backgroundColor: AppColors.primary,
      ),
    );
  }
}

class _PayBody extends StatelessWidget {
  const _PayBody({
    required this.payment,
    required this.selectedMethod,
    required this.onMethodChange,
    required this.onContinue,
  });

  final Map<String, dynamic> payment;
  final int selectedMethod;
  final ValueChanged<int> onMethodChange;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    final amount = (payment['amount'] ?? 0).toDouble();
    final lateFee = (payment['lateFee'] ?? 0).toDouble();
    final adjustments = (payment['adjustments'] ?? 0).toDouble();
    final total = amount + lateFee - adjustments;

    final installmentNum = payment['installmentNumber'];
    final dueDate = Formatters.date(payment['dueDate']?.toString());
    final subtitle = installmentNum != null
        ? 'Cheque $installmentNum · due $dueDate'
        : 'Due $dueDate';

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 40),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              IconButton(
                onPressed: () => context.pop(),
                icon: const Icon(Icons.arrow_back_ios_new, size: 18),
              ),
              Text(
                'Pay rent',
                style: GoogleFonts.inter(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          Text('AMOUNT DUE',
              style: GoogleFonts.inter(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textMuted)),
          const SizedBox(height: 8),
          Text(Formatters.currency(amount),
              style: GoogleFonts.sourceSerif4(
                  fontSize: 40,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary)),
          Text(subtitle,
              style: GoogleFonts.inter(fontSize: 12, color: AppColors.textMuted)),
          const SizedBox(height: 20),
          _MethodTile(
            index: 0,
            selected: selectedMethod == 0,
            icon: Icons.receipt_long_outlined,
            title: 'Submit cheque',
            subtitle: 'Drop off at office or schedule pickup',
            onTap: onMethodChange,
          ),
          _MethodTile(
            index: 1,
            selected: selectedMethod == 1,
            icon: Icons.swap_horiz_outlined,
            title: 'Bank transfer',
            subtitle: 'Direct to RentAxis · Emirates NBD',
            onTap: onMethodChange,
          ),
          _MethodTile(
            index: 2,
            selected: selectedMethod == 2,
            icon: Icons.credit_card_outlined,
            title: 'Card payment',
            subtitle: '2.5% processing fee',
            onTap: onMethodChange,
          ),
          const SizedBox(height: 20),
          Container(
            decoration: BoxDecoration(
              color: AppColors.surface2,
              border: Border.all(color: AppColors.border),
              borderRadius: BorderRadius.circular(14),
            ),
            padding: const EdgeInsets.all(14),
            child: Column(
              children: [
                _SummaryRow(label: 'Base rent', value: Formatters.currency(amount)),
                _SummaryRow(label: 'Late fee', value: Formatters.currency(lateFee)),
                _SummaryRow(
                    label: 'Adjustments',
                    value: '-${Formatters.currency(adjustments)}'),
                const Divider(height: 20),
                _SummaryRow(
                    label: 'Total', value: Formatters.currency(total), bold: true),
              ],
            ),
          ),
          const SizedBox(height: 20),
          SizedBox(
            height: 48,
            child: ElevatedButton(
              onPressed: onContinue,
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.accent,
                foregroundColor: AppColors.navyDark,
              ),
              child: Text('Continue · ${Formatters.currency(total)}'),
            ),
          ),
        ],
      ),
    );
  }
}

class _MethodTile extends StatelessWidget {
  const _MethodTile({
    required this.index,
    required this.selected,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final int index;
  final bool selected;
  final IconData icon;
  final String title;
  final String subtitle;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => onTap(index),
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(
              color: selected ? AppColors.primary : AppColors.border,
              width: selected ? 1.5 : 1),
          borderRadius: BorderRadius.circular(14),
        ),
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Icon(icon,
                color: selected ? AppColors.primary : AppColors.textSecondary),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: GoogleFonts.inter(
                          fontSize: 13.5, fontWeight: FontWeight.w600)),
                  Text(subtitle,
                      style: GoogleFonts.inter(
                          fontSize: 11.5, color: AppColors.textMuted)),
                ],
              ),
            ),
            Icon(
                selected ? Icons.check_circle : Icons.radio_button_unchecked,
                color: selected ? AppColors.primary : AppColors.textMuted,
                size: 18),
          ],
        ),
      ),
    );
  }
}

class _SummaryRow extends StatelessWidget {
  const _SummaryRow({required this.label, required this.value, this.bold = false});

  final String label;
  final String value;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label,
              style: GoogleFonts.inter(
                  fontSize: 13,
                  color: AppColors.textSecondary,
                  fontWeight: bold ? FontWeight.w600 : FontWeight.w400)),
          Text(value,
              style: GoogleFonts.jetBrainsMono(
                  fontSize: 13,
                  color: AppColors.textPrimary,
                  fontWeight: bold ? FontWeight.w700 : FontWeight.w500)),
        ],
      ),
    );
  }
}

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
          Text('No payments due',
              style: GoogleFonts.sourceSerif4(
                  fontSize: 22,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary)),
          const SizedBox(height: 6),
          Text("You're all caught up — nothing to pay right now.",
              textAlign: TextAlign.center,
              style: GoogleFonts.inter(
                  fontSize: 13, color: AppColors.textMuted)),
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
          Text('Could not load payments',
              style: GoogleFonts.sourceSerif4(
                  fontSize: 20,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary)),
          const SizedBox(height: 16),
          ElevatedButton(onPressed: onRetry, child: const Text('Retry')),
        ],
      ),
    );
  }
}
