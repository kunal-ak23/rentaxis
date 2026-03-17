import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:razorpay_flutter/razorpay_flutter.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

final _myPaymentsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final service = ref.watch(_paymentServiceProvider);
  return service.getMyPayments();
});

class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});

  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  late Razorpay _razorpay;
  String? _processingPaymentId;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _razorpay = Razorpay();
    _razorpay.on(Razorpay.EVENT_PAYMENT_SUCCESS, _handlePaymentSuccess);
    _razorpay.on(Razorpay.EVENT_PAYMENT_ERROR, _handlePaymentError);
    _razorpay.on(Razorpay.EVENT_EXTERNAL_WALLET, _handleExternalWallet);
  }

  @override
  void dispose() {
    _tabController.dispose();
    _razorpay.clear();
    super.dispose();
  }

  void _handlePaymentSuccess(PaymentSuccessResponse response) async {
    try {
      await ref.read(_paymentServiceProvider).verifyPayment({
        'gatewayPaymentId': response.paymentId,
        'gatewayOrderId': response.orderId,
        'gatewaySignature': response.signature,
      });
      if (mounted) {
        ref.invalidate(_myPaymentsProvider);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Payment successful!'),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Payment verification failed. Contact support.'),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
    setState(() => _processingPaymentId = null);
  }

  void _handlePaymentError(PaymentFailureResponse response) async {
    // Cancel the ONLINE_PENDING state so payment reverts to PENDING
    if (_processingPaymentId != null) {
      try {
        await ref.read(_paymentServiceProvider).cancelPayment(_processingPaymentId!);
      } catch (_) {}
    }
    setState(() => _processingPaymentId = null);
    ref.invalidate(_myPaymentsProvider);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content:
              Text('Payment failed: ${response.message ?? 'Unknown error'}'),
          backgroundColor: AppColors.danger,
        ),
      );
    }
  }

  void _handleExternalWallet(ExternalWalletResponse response) {
    setState(() => _processingPaymentId = null);
  }

  Future<void> _initiatePayment(Map<String, dynamic> payment) async {
    final paymentId = payment['id'];
    setState(() => _processingPaymentId = paymentId);

    try {
      final order =
          await ref.read(_paymentServiceProvider).createOrder(paymentId);

      final options = {
        'key': order['gatewayKey'],
        'amount': ((order['amount'] ?? 0) * 100).toInt(), // Convert to paise
        'order_id': order['orderId'],
        'name': 'RentAxis',
        'description':
            'Rent Payment - ${payment['installmentLabel'] ?? payment['label'] ?? ''}',
        'prefill': {
          'email': order['renterEmail'] ?? ref.read(authProvider).email ?? '',
          'contact': '',
        },
        'currency': order['currency'] ?? 'INR',
      };

      _razorpay.open(options);
    } catch (e) {
      setState(() => _processingPaymentId = null);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Failed to create payment order'),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _downloadReceipt(String paymentId) async {
    try {
      final bytes =
          await ref.read(_paymentServiceProvider).downloadReceipt(paymentId);
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/receipt_$paymentId.pdf');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Failed to download receipt'),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final paymentsAsync = ref.watch(_myPaymentsProvider);

    return Column(
      children: [
        // Page title + tab bar
        Container(
          color: AppColors.surface,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
                child: Text(
                  'Payments',
                  style: GoogleFonts.cinzel(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textPrimary,
                  ),
                ),
              ),
              Container(
                margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                padding: const EdgeInsets.all(3),
                decoration: BoxDecoration(
                  color: AppColors.background,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: TabBar(
                  controller: _tabController,
                  indicator: BoxDecoration(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(8),
                    boxShadow: AppShadows.soft,
                  ),
                  indicatorSize: TabBarIndicatorSize.tab,
                  dividerColor: Colors.transparent,
                  labelColor: AppColors.primary,
                  unselectedLabelColor: AppColors.textMuted,
                  labelStyle: GoogleFonts.josefinSans(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                  unselectedLabelStyle: GoogleFonts.josefinSans(
                    fontSize: 13,
                    fontWeight: FontWeight.w400,
                  ),
                  labelPadding: EdgeInsets.zero,
                  tabs: const [
                    Tab(text: 'Upcoming', height: 36),
                    Tab(text: 'History', height: 36),
                  ],
                ),
              ),
            ],
          ),
        ),
        // Body
        Expanded(child: _buildBody(paymentsAsync)),
      ],
    );
  }

  Widget _buildBody(AsyncValue<List<dynamic>> paymentsAsync) {
    return Scaffold(
      body: paymentsAsync.when(
        data: (payments) {
          final upcoming = payments.where((p) {
            final status = p['status'] ?? '';
            return status == 'PENDING' ||
                status == 'OVERDUE' ||
                status == 'ONLINE_PENDING';
          }).toList();

          final history = payments.where((p) {
            final status = p['status'] ?? '';
            return status == 'CLEARED' ||
                status == 'COLLECTED' ||
                status == 'DEPOSITED';
          }).toList();

          upcoming.sort((a, b) {
            final aOverdue = a['status'] == 'OVERDUE' ? 0 : 1;
            final bOverdue = b['status'] == 'OVERDUE' ? 0 : 1;
            if (aOverdue != bOverdue) return aOverdue.compareTo(bOverdue);
            return (a['dueDate'] ?? '').compareTo(b['dueDate'] ?? '');
          });

          history.sort(
              (a, b) => (b['dueDate'] ?? '').compareTo(a['dueDate'] ?? ''));

          num totalDue = 0;
          for (final p in upcoming) {
            totalDue += (p['totalPayable'] ?? p['amount'] ?? 0) as num;
          }

          return TabBarView(
            controller: _tabController,
            children: [
              _buildUpcomingTab(upcoming, totalDue),
              _buildHistoryTab(history),
            ],
          );
        },
        loading: () => Padding(
          padding: const EdgeInsets.all(20),
          child: ListShimmer(itemCount: 3),
        ),
        error: (err, _) => ErrorState(
          message: 'Failed to load payments',
          onRetry: () => ref.invalidate(_myPaymentsProvider),
        ),
      ),
    );
  }

  Widget _buildUpcomingTab(List<dynamic> upcoming, num totalDue) {
    return RefreshIndicator(
      color: AppColors.primary,
      onRefresh: () async => ref.invalidate(_myPaymentsProvider),
      child: upcoming.isEmpty
          ? ListView(
              children: const [
                SizedBox(height: 80),
                EmptyState(
                  icon: Icons.check_circle_outline,
                  title: 'All Caught Up!',
                  subtitle: 'No pending payments',
                ),
              ],
            )
          : ListView(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 150),
              children: [
                // Summary card with glass-morphism
                AnimatedListItem(
                  index: 0,
                  child: Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [AppColors.navyDark, Color(0xFF163048)],
                      ),
                      borderRadius: BorderRadius.circular(16),
                      boxShadow: AppShadows.medium,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Total Due',
                          style: GoogleFonts.josefinSans(
                            color: Colors.white.withValues(alpha: 0.6),
                            fontSize: 13,
                            letterSpacing: 0.5,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          Formatters.currency(totalDue),
                          style: GoogleFonts.josefinSans(
                            color: AppColors.accent,
                            fontSize: 32,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          '${upcoming.length} payment${upcoming.length == 1 ? '' : 's'} pending',
                          style: GoogleFonts.josefinSans(
                            color: Colors.white.withValues(alpha: 0.4),
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 18),

                // Payment cards
                ...upcoming.asMap().entries.map((entry) {
                  return AnimatedListItem(
                    index: entry.key + 1,
                    child: _buildUpcomingCard(entry.value),
                  );
                }),
              ],
            ),
    );
  }

  Widget _buildUpcomingCard(Map<String, dynamic> payment) {
    final status = payment['status'] ?? 'PENDING';
    final isOverdue = status == 'OVERDUE';
    final isProcessing = status == 'ONLINE_PENDING' ||
        _processingPaymentId == payment['id'];
    final amount = payment['amount'] ?? 0;
    final penalty = payment['penaltyAmount'] ?? 0;
    final totalPayable = payment['totalPayable'] ?? amount;
    final dueDate = Formatters.date(payment['dueDate']);
    final installmentNum = payment['installmentNumber'] ?? '';
    final label = installmentNum != '' ? 'Installment #$installmentNum' : (payment['installmentLabel'] ?? payment['label'] ?? 'Payment');
    final propertyName = payment['propertyName'] ?? '';
    final unitNumber = payment['unitIdentifier'] ?? '';

    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: AppShadows.soft,
            border: isOverdue
                ? Border.all(
                    color: AppColors.danger.withValues(alpha: 0.2),
                  )
                : null,
          ),
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Left accent border — gold for upcoming, red for overdue
              Container(
                width: 5,
                color: isOverdue ? AppColors.danger : AppColors.accent,
              ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: Text(
                            label.isNotEmpty ? label : 'Payment',
                            style: Theme.of(context).textTheme.titleMedium,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        StatusBadge(
                          label: status,
                          color:
                              StatusHelper.getPaymentStatusColor(status),
                        ),
                      ],
                    ),
                    if (propertyName.isNotEmpty ||
                        unitNumber.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        [
                          propertyName,
                          if (unitNumber.isNotEmpty) 'Unit $unitNumber'
                        ].join(' - '),
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: AppColors.textMuted),
                      ),
                    ],
                    const SizedBox(height: 14),
                    Row(
                      children: [
                        _PaymentDetail(
                            label: 'Due Date', value: dueDate),
                        _PaymentDetail(
                            label: 'Amount',
                            value: Formatters.currency(amount)),
                        if ((penalty as num) > 0)
                          _PaymentDetail(
                            label: 'Penalty',
                            value: Formatters.currency(penalty),
                            valueColor: AppColors.danger,
                          ),
                      ],
                    ),
                    const SizedBox(height: 14),
                    Divider(
                      height: 1,
                      color: AppColors.border.withValues(alpha: 0.5),
                    ),
                    const SizedBox(height: 14),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Total Payable',
                                style: Theme.of(context)
                                    .textTheme
                                    .labelSmall),
                            Text(
                              Formatters.currency(totalPayable),
                              style: GoogleFonts.josefinSans(
                                fontSize: 20,
                                fontWeight: FontWeight.w700,
                                color: AppColors.primary,
                              ),
                            ),
                          ],
                        ),
                        if (!isProcessing)
                          _PayNowButton(
                            onPressed: () => _initiatePayment(payment),
                          )
                        else
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 20, vertical: 12),
                            decoration: BoxDecoration(
                              color: AppColors.statusPending
                                  .withValues(alpha: 0.08),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 14,
                                  height: 14,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: AppColors.statusPending,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  'Processing...',
                                  style: GoogleFonts.josefinSans(
                                    color: AppColors.statusPending,
                                    fontWeight: FontWeight.w600,
                                    fontSize: 13,
                                  ),
                                ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHistoryTab(List<dynamic> history) {
    return RefreshIndicator(
      color: AppColors.primary,
      onRefresh: () async => ref.invalidate(_myPaymentsProvider),
      child: history.isEmpty
          ? ListView(
              children: const [
                SizedBox(height: 80),
                EmptyState(
                  icon: Icons.history,
                  title: 'No Payment History',
                  subtitle: 'Completed payments will appear here',
                ),
              ],
            )
          : ListView.builder(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 150),
              itemCount: history.length,
              itemBuilder: (context, index) {
                final payment = history[index];
                return AnimatedListItem(
                  index: index,
                  child: _buildHistoryCard(payment),
                );
              },
            ),
    );
  }

  Widget _buildHistoryCard(Map<String, dynamic> payment) {
    final amount = payment['totalPayable'] ?? payment['amount'] ?? 0;
    final dueDate = Formatters.date(payment['dueDate']);
    final installmentNum = payment['installmentNumber'] ?? '';
    final label = installmentNum != '' ? 'Installment #$installmentNum' : (payment['installmentLabel'] ?? payment['label'] ?? 'Payment');
    final propertyName = payment['propertyName'] ?? '';
    final unitId = payment['unitIdentifier'] ?? '';
    final subtitle = [propertyName, if (unitId.isNotEmpty) unitId].where((s) => s.isNotEmpty).join(' - ');
    final status = payment['status'] ?? '';

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(16),
            boxShadow: AppShadows.soft,
          ),
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Green accent
              Container(
                width: 5,
                color: AppColors.success,
              ),
            Expanded(
              child: ListTile(
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                leading: Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: AppColors.success.withValues(alpha: 0.08),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.check_circle,
                    color: AppColors.success,
                    size: 22,
                  ),
                ),
                title: Text(
                  label.isNotEmpty ? label : 'Payment',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                subtitle: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (subtitle.isNotEmpty)
                      Text(
                        subtitle,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(color: AppColors.textMuted),
                      ),
                    Text(
                      dueDate,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
                trailing: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      Formatters.currency(amount),
                      style: GoogleFonts.josefinSans(
                        fontWeight: FontWeight.w700,
                        fontSize: 14,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    GestureDetector(
                      onTap: () => _downloadReceipt(payment['id']),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.download_rounded,
                              size: 14, color: AppColors.primary),
                          const SizedBox(width: 3),
                          Text(
                            'Receipt',
                            style: GoogleFonts.josefinSans(
                              fontSize: 11,
                              color: AppColors.primary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PayNowButton extends StatefulWidget {
  final VoidCallback onPressed;

  const _PayNowButton({required this.onPressed});

  @override
  State<_PayNowButton> createState() => _PayNowButtonState();
}

class _PayNowButtonState extends State<_PayNowButton> {
  double _scale = 1.0;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (_) => setState(() => _scale = 0.95),
      onTapUp: (_) {
        setState(() => _scale = 1.0);
        widget.onPressed();
      },
      onTapCancel: () => setState(() => _scale = 1.0),
      child: AnimatedScale(
        scale: _scale,
        duration: const Duration(milliseconds: 150),
        curve: Curves.easeOut,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              colors: [AppColors.primary, AppColors.primaryLight],
            ),
            borderRadius: BorderRadius.circular(12),
            boxShadow: [
              BoxShadow(
                color: AppColors.primary.withValues(alpha: 0.25),
                blurRadius: 8,
                offset: const Offset(0, 3),
              ),
            ],
          ),
          child: Text(
            'Pay Now',
            style: GoogleFonts.josefinSans(
              color: Colors.white,
              fontWeight: FontWeight.w600,
              fontSize: 14,
            ),
          ),
        ),
      ),
    );
  }
}

class _PaymentDetail extends StatelessWidget {
  final String label;
  final String value;
  final Color? valueColor;

  const _PaymentDetail({
    required this.label,
    required this.value,
    this.valueColor,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 3),
          Text(
            value,
            style: GoogleFonts.josefinSans(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: valueColor ?? AppColors.textPrimary,
            ),
          ),
        ],
      ),
    );
  }
}
