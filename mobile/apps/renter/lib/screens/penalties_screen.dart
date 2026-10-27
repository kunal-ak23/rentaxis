import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../providers/renter_data.dart';
import '../utils/renter_rules.dart';

final _penaltiesProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, status) async {
      final rows = await ref.watch(renterPenaltiesProvider.future);
      return rows
          .where(
            (r) =>
                status == 'all' ||
                (r['collectionStatus'] == 'CLEARED') == (status == 'cleared'),
          )
          .toList();
    });

class PenaltiesScreen extends StatelessWidget {
  const PenaltiesScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Text(ar ? 'الغرامات والرسوم' : 'Penalties & charges'),
          bottom: TabBar(
            tabs: [
              Tab(text: ar ? 'غير المسدد' : 'Uncleared'),
              Tab(text: ar ? 'المسدد' : 'Cleared'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            for (final status in ['open', 'cleared'])
              _PenaltyList(status: status),
          ],
        ),
      ),
    );
  }
}

class _PenaltyList extends ConsumerWidget {
  const _PenaltyList({required this.status});
  final String status;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    Future<void> refresh() async {
      ref.invalidate(renterPenaltiesProvider);
      await ref.read(renterPenaltiesProvider.future);
    }

    return ref
        .watch(_penaltiesProvider(status))
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => ErrorState(
            message: ar ? 'تعذر تحميل الرسوم' : 'Could not load charges',
            onRetry: refresh,
          ),
          data: (rows) => RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(20),
              children: [
                Text(
                  ar
                      ? 'تظهر هنا الرسوم المعتمدة فقط. تعرض صفحة الدفعات المبالغ المستحقة وحالة التحصيل.'
                      : 'Only approved charges appear here. Payments shows the amount due and collection status.',
                ),
                TextButton(
                  onPressed: () => context.go('/payments'),
                  child: Text(ar ? 'عرض الدفعات' : 'View payments'),
                ),
                if (rows.isEmpty)
                  EmptyState(
                    icon: Icons.check_circle_outline,
                    title: ar
                        ? 'لا توجد رسوم في هذا العرض'
                        : 'No charges in this view',
                  ),
                for (final row in rows)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: MiftahCard(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _reason(row['reason']?.toString(), ar),
                            style: MiftahType.cardTitle(),
                          ),
                          if (row['propertyName'] != null)
                            Text('${row['propertyName']}'),
                          if (row['description'] != null)
                            Text('${row['description']}'),
                          const SizedBox(height: 10),
                          Text(
                            Formatters.currency(
                              ((row['amount'] as num?) ?? 0) +
                                  ((row['vatAmount'] as num?) ?? 0),
                            ),
                            style: MiftahType.amount(size: 26),
                          ),
                          if (((row['vatAmount'] as num?) ?? 0) > 0)
                            Text(
                              '${ar ? 'يشمل ضريبة القيمة المضافة' : 'Includes VAT'}: ${Formatters.currency(row['vatAmount'] as num)}',
                            ),
                          if (row['proposedAmount'] != null &&
                              row['proposedAmount'] != row['amount'])
                            Text(
                              '${ar ? 'تم التخفيض من' : 'Reduced from'} ${Formatters.currency(row['proposedAmount'] as num)}',
                            ),
                          Text(
                            '${ar ? 'اعتمد في' : 'Approved on'} ${Formatters.date(row['approvedAt'] as String?, ar: ar)}',
                          ),
                          if (row['collectionStatus'] != null)
                            Text(
                              '${ar ? 'التحصيل' : 'Collection'}: ${paymentStatusLabel(row['collectionStatus'] as String, ar)}',
                            ),
                          if (row['chequeNumber'] != null)
                            Text(
                              '${ar ? 'رقم الشيك' : 'Cheque number'}: ${row['chequeNumber']}',
                            ),
                          if (row['resolutionNote'] != null)
                            Text('${row['resolutionNote']}'),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        );
  }
}

String _reason(String? reason, bool ar) => switch (reason) {
  'CHEQUE_RETURN' => ar ? 'شيك مرتجع' : 'Cheque return',
  'LATE_PAYMENT' => ar ? 'تأخر الدفع' : 'Late payment',
  'SERVICE_RECHARGE' => ar ? 'رسوم خدمة' : 'Service recharge',
  'ADMIN_FEE' => ar ? 'رسوم إدارية' : 'Administration fee',
  'DAMAGE' => ar ? 'أضرار / تنظيف' : 'Damage / cleaning',
  'MAINTENANCE_RECHARGE' => ar ? 'رسوم صيانة' : 'Maintenance recharge',
  'BOOKING_FEE' => ar ? 'رسوم حجز' : 'Booking fee',
  _ => ar ? 'رسوم أخرى' : 'Other charge',
};
