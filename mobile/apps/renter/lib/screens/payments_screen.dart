import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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
        'razorpayPaymentId': response.paymentId,
        'razorpayOrderId': response.orderId,
        'razorpaySignature': response.signature,
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

  void _handlePaymentError(PaymentFailureResponse response) {
    setState(() => _processingPaymentId = null);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Payment failed: ${response.message ?? 'Unknown error'}'),
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
        'key': order['razorpayKeyId'],
        'amount': order['amountInPaise'] ?? ((order['amount'] ?? 0) * 100).toInt(),
        'order_id': order['razorpayOrderId'] ?? order['orderId'],
        'name': 'RentAxis',
        'description': 'Rent Payment - ${payment['installmentLabel'] ?? ''}',
        'prefill': {
          'email': ref.read(authProvider).email ?? '',
        },
        'currency': 'AED',
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

    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppColors.navyDark,
        title: const Text('Payments'),
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: AppColors.accent,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white54,
          tabs: const [
            Tab(text: 'Upcoming'),
            Tab(text: 'History'),
          ],
        ),
      ),
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

          // Sort upcoming: overdue first, then by due date
          upcoming.sort((a, b) {
            final aOverdue = a['status'] == 'OVERDUE' ? 0 : 1;
            final bOverdue = b['status'] == 'OVERDUE' ? 0 : 1;
            if (aOverdue != bOverdue) return aOverdue.compareTo(bOverdue);
            return (a['dueDate'] ?? '').compareTo(b['dueDate'] ?? '');
          });

          // Sort history: most recent first
          history.sort(
              (a, b) => (b['dueDate'] ?? '').compareTo(a['dueDate'] ?? ''));

          // Calculate total due
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
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
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
              padding: const EdgeInsets.all(16),
              children: [
                // Summary card
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [AppColors.navyDark, Color(0xFF1A3352)],
                    ),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Total Due',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.7),
                          fontSize: 13,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        Formatters.currency(totalDue),
                        style: const TextStyle(
                          color: AppColors.accent,
                          fontSize: 28,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '${upcoming.length} payment${upcoming.length == 1 ? '' : 's'} pending',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.5),
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),

                // Payment cards
                ...upcoming.map((p) => _buildUpcomingCard(p)),
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
    final label = payment['installmentLabel'] ?? payment['label'] ?? '';
    final propertyName = payment['property']?['name'] ??
        payment['propertyName'] ??
        '';
    final unitNumber = payment['unit']?['unitNumber'] ??
        payment['unitNumber'] ??
        '';

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: isOverdue
              ? AppColors.danger.withValues(alpha: 0.4)
              : AppColors.border,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
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
                  color: StatusHelper.getPaymentStatusColor(status),
                ),
              ],
            ),
            if (propertyName.isNotEmpty || unitNumber.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(
                [propertyName, if (unitNumber.isNotEmpty) 'Unit $unitNumber']
                    .join(' - '),
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: AppColors.textSecondary),
              ),
            ],
            const SizedBox(height: 12),
            Row(
              children: [
                _PaymentDetail(label: 'Due Date', value: dueDate),
                _PaymentDetail(
                    label: 'Amount', value: Formatters.currency(amount)),
                if ((penalty as num) > 0)
                  _PaymentDetail(
                    label: 'Penalty',
                    value: Formatters.currency(penalty),
                    valueColor: AppColors.danger,
                  ),
              ],
            ),
            const SizedBox(height: 12),
            const Divider(height: 1),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Total Payable',
                        style: Theme.of(context).textTheme.labelSmall),
                    Text(
                      Formatters.currency(totalPayable),
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                        color: AppColors.primary,
                      ),
                    ),
                  ],
                ),
                if (!isProcessing)
                  ElevatedButton(
                    onPressed: () => _initiatePayment(payment),
                    child: const Text('Pay Now'),
                  )
                else
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                    decoration: BoxDecoration(
                      color: AppColors.statusPending.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: const Row(
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
                        SizedBox(width: 8),
                        Text(
                          'Processing...',
                          style: TextStyle(
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
              padding: const EdgeInsets.all(16),
              itemCount: history.length,
              itemBuilder: (context, index) {
                final payment = history[index];
                return _buildHistoryCard(payment);
              },
            ),
    );
  }

  Widget _buildHistoryCard(Map<String, dynamic> payment) {
    final amount = payment['totalPayable'] ?? payment['amount'] ?? 0;
    final dueDate = Formatters.date(payment['dueDate']);
    final label = payment['installmentLabel'] ?? payment['label'] ?? '';
    final status = payment['status'] ?? '';

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: AppColors.success.withValues(alpha: 0.1),
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
        subtitle: Text(
          '$dueDate  |  $status',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        trailing: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              Formatters.currency(amount),
              style: const TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 14,
                color: AppColors.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            GestureDetector(
              onTap: () => _downloadReceipt(payment['id']),
              child: const Text(
                'Download Receipt',
                style: TextStyle(
                  fontSize: 11,
                  color: AppColors.primary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
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
          const SizedBox(height: 2),
          Text(
            value,
            style: TextStyle(
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
