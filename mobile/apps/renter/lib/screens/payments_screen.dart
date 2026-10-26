import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../providers/renter_data.dart';
import '../utils/renter_rules.dart';
import '../widgets/renter_document_button.dart';
import 'next_payment.dart';

enum _Filter { all, due, cleared, upcoming }

class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});
  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen> {
  _Filter _filter = _Filter.all;
  String? _leaseId;

  Future<void> _refresh() async {
    ref.invalidate(renterPaymentsProvider);
    ref.invalidate(renterLeasesProvider);
    ref.invalidate(renterPenaltiesProvider);
    ref.invalidate(renterInvoicesProvider);
    await ref.read(renterPaymentsProvider.future);
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final payments = ref.watch(renterPaymentsProvider);
    final leases = ref.watch(renterLeasesProvider);
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  ar ? 'الدفعات' : 'Payments',
                  style: MiftahType.title(),
                ),
              ),
              TextButton(
                onPressed: () => context.push('/penalties'),
                child: Text(ar ? 'الغرامات والرسوم' : 'Penalties & charges'),
              ),
            ],
          ),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: _refresh,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
              children: [
                if (leases.hasError)
                  ErrorState(
                    message: ar
                        ? 'تعذر تحميل العقود'
                        : 'Could not load contracts',
                    onRetry: () => ref.invalidate(renterLeasesProvider),
                  ),
                if (leases.valueOrNull case final rows? when rows.length > 1)
                  DropdownButtonFormField<String>(
                    key: ValueKey('wallet-lease-$_leaseId'),
                    initialValue: rows.any((r) => r['id'] == _leaseId)
                        ? _leaseId
                        : null,
                    isExpanded: true,
                    decoration: InputDecoration(
                      labelText: ar ? 'العقد' : 'Contract',
                    ),
                    items: [
                      DropdownMenuItem(
                        value: null,
                        child: Text(ar ? 'كل العقود' : 'All contracts'),
                      ),
                      for (final row in orderedRenterLeases(rows))
                        DropdownMenuItem(
                          value: row['id'] as String,
                          child: Text(
                            '${row['propertyName'] ?? ''} · ${row['unitIdentifier'] ?? ''} · ${row['displayContractNumber'] ?? row['startDate'] ?? ''}',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (id) => setState(() => _leaseId = id),
                  ),
                const SizedBox(height: 12),
                payments.when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (e, _) => ErrorState(
                    message: ar
                        ? 'تعذر تحميل الدفعات'
                        : 'Could not load payments',
                    onRetry: _refresh,
                  ),
                  data: (all) {
                    final rows =
                        all
                            .where(
                              (r) =>
                                  _leaseId == null || r['leaseId'] == _leaseId,
                            )
                            .toList()
                          ..sort((a, b) {
                            final date = '${a['dueDate'] ?? ''}'.compareTo(
                              '${b['dueDate'] ?? ''}',
                            );
                            return date != 0
                                ? date
                                : ((a['installmentNumber'] as num?) ?? 0)
                                      .compareTo(
                                        (b['installmentNumber'] as num?) ?? 0,
                                      );
                          });
                    final live = rows.where(isLiveInstrument);
                    final total = live.fold<num>(
                      0,
                      (s, r) => s + ((r['amount'] as num?) ?? 0),
                    );
                    final cleared = live
                        .where((r) => r['status'] == 'CLEARED')
                        .fold<num>(
                          0,
                          (s, r) => s + ((r['amount'] as num?) ?? 0),
                        );
                    final due = rows
                        .where(isOwedNow)
                        .fold<num>(
                          0,
                          (s, r) => s + ((r['payable'] as num?) ?? 0),
                        );
                    final filtered = rows
                        .where(
                          (r) => switch (_filter) {
                            _Filter.all => true,
                            _Filter.due => isOwedNow(r),
                            _Filter.cleared => r['status'] == 'CLEARED',
                            _Filter.upcoming =>
                              r['status'] == 'REGISTERED' && !isOwedNow(r),
                          },
                        )
                        .toList();
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        MiftahGoldCard(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                ar ? 'المستحق الآن' : 'Due now',
                                style: MiftahType.cardTitle(),
                              ),
                              Text(
                                Formatters.currency(due),
                                key: const Key('wallet-due'),
                                style: MiftahType.amount(size: 32),
                              ),
                              const SizedBox(height: 14),
                              MiftahProgress(
                                value: total > 0
                                    ? (cleared / total).clamp(0.0, 1.0)
                                    : 0,
                                onGold: true,
                              ),
                              const SizedBox(height: 8),
                              Text(
                                '${ar ? 'المسدد' : 'Cleared'}: ${Formatters.currency(cleared)} / ${Formatters.currency(total)}',
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          ar
                              ? 'تُسجّل الدفعات لدى الإدارة بشيك أو نقداً أو تحويل بنكي. الشيك المودع لم تتم تسويته بعد.'
                              : 'Payments are recorded by management by cheque, cash or bank transfer. Deposited cheques are still awaiting clearance.',
                        ),
                        const SizedBox(height: 12),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            for (final f in _Filter.values)
                              ChoiceChip(
                                label: Text(switch (f) {
                                  _Filter.all => ar ? 'الكل' : 'All',
                                  _Filter.due =>
                                    ar ? 'المستحق الآن' : 'Due now',
                                  _Filter.cleared => ar ? 'المسدد' : 'Cleared',
                                  _Filter.upcoming =>
                                    ar ? 'القادم' : 'Upcoming',
                                }),
                                selected: _filter == f,
                                onSelected: (_) => setState(() => _filter = f),
                              ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        if (filtered.isEmpty)
                          EmptyState(
                            icon: Icons.receipt_long_outlined,
                            title: ar
                                ? 'لا توجد دفعات في هذا العرض'
                                : 'No payments in this view',
                          ),
                        for (final row in filtered)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: _PaymentCard(row: row),
                          ),
                      ],
                    );
                  },
                ),
                _TaxInvoices(leaseId: _leaseId),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _PaymentCard extends ConsumerWidget {
  const _PaymentCard({required this.row});
  final Map<String, dynamic> row;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    final status = renterPaymentStatus(row);
    final raw = row['status'];
    final penalty = row['penaltyAssessmentId'] != null;
    final mode = switch (row['mode']) {
      'CASH' => ar ? 'نقداً' : 'Cash',
      'TRANSFER' => ar ? 'تحويل بنكي' : 'Bank transfer',
      'ONLINE' => ar ? 'إلكتروني' : 'Online',
      _ => ar ? 'شيك' : 'Cheque',
    };
    return MiftahCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 10,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                penalty
                    ? (ar ? 'غرامة / رسوم' : 'Penalty / charge')
                    : '${ar ? 'قسط' : 'Instalment'} ${row['installmentNumber'] ?? ''}',
                style: MiftahType.cardTitle(),
              ),
              MiftahBadge(
                paymentStatusLabel(status, ar),
                tone: status == 'CLEARED'
                    ? MiftahTone.success
                    : const {'BOUNCED', 'OVERDUE'}.contains(status)
                    ? MiftahTone.danger
                    : MiftahTone.neutral,
              ),
            ],
          ),
          Text('${row['propertyName'] ?? ''} · ${row['unitIdentifier'] ?? ''}'),
          const SizedBox(height: 10),
          Text(
            Formatters.currency((row['amount'] as num?) ?? 0),
            style: MiftahType.amount(size: 25),
          ),
          Text('$mode · ${Formatters.date(row['dueDate'] as String?, ar: ar)}'),
          if (isOwedNow(row))
            Text(
              '${ar ? 'المتبقي المستحق' : 'Remaining payable'}: ${Formatters.currency(row['payable'] as num)}',
              key: ValueKey('payable-${row['id']}'),
            ),
          if (row['overdue'] == true && isOwedNow(row))
            Text(
              '${row['daysOverdue'] ?? 0} ${ar ? 'أيام تأخير' : 'days overdue'}',
            ),
          if (row['chequeNumber'] != null)
            Text(
              '${ar ? 'رقم الشيك' : 'Cheque number'}: ${row['chequeNumber']}',
            ),
          if (row['bankName'] != null) Text('${row['bankName']}'),
          if (row['narration'] != null) Text('${row['narration']}'),
          if (row['depositedAt'] != null)
            Text(
              '${ar ? 'أودع في' : 'Deposited on'} ${Formatters.date(row['depositedAt'] as String?, ar: ar)}',
            ),
          if (raw == 'CLEARED' && row['clearedAt'] != null)
            Text(
              '${ar ? 'سدد في' : 'Cleared on'} ${Formatters.date(row['clearedAt'] as String?, ar: ar)}',
            ),
          if (raw == 'BOUNCED' && row['failureReason'] != null)
            Text(
              '${ar ? 'سبب الإرجاع' : 'Return reason'}: ${chequeFailureReasonLabel('${row['failureReason']}', ar)}',
            ),
          Wrap(
            spacing: 8,
            children: [
              if (raw == 'CLEARED')
                RenterDocumentButton(
                  path: '/v1/cheques/${row['id']}/receipt',
                  filename: 'receipt-${row['id']}.pdf',
                  label: ar ? 'الإيصال' : 'Receipt',
                ),
              if (row['hasImage'] == true)
                TextButton.icon(
                  icon: const Icon(Icons.image_outlined),
                  label: Text(ar ? 'صورة الشيك' : 'Cheque scan'),
                  onPressed: () async {
                    try {
                      final bytes = await ref.read(renterDocumentProvider)(
                        '/v1/cheques/${row['id']}/image',
                      );
                      if (!context.mounted) return;
                      await showDialog<void>(
                        context: context,
                        builder: (context) => Dialog(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Flexible(
                                child: InteractiveViewer(
                                  child: Image.memory(
                                    Uint8List.fromList(bytes),
                                    errorBuilder: (_, _, _) => Text(
                                      ar
                                          ? 'تعذر عرض الصورة'
                                          : 'Could not display this image',
                                    ),
                                  ),
                                ),
                              ),
                              TextButton(
                                onPressed: () => Navigator.pop(context),
                                child: Text(ar ? 'إغلاق' : 'Close'),
                              ),
                            ],
                          ),
                        ),
                      );
                    } catch (e) {
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text(
                              errorMessage(
                                e,
                                ar
                                    ? 'تعذر تحميل الصورة'
                                    : 'Could not load cheque scan',
                              ),
                            ),
                          ),
                        );
                      }
                    }
                  },
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _TaxInvoices extends ConsumerWidget {
  const _TaxInvoices({this.leaseId});
  final String? leaseId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    return ref
        .watch(renterInvoicesProvider)
        .when(
          loading: () => const LinearProgressIndicator(),
          error: (_, _) => ErrorState(
            message: ar
                ? 'تعذر تحميل الفواتير الضريبية'
                : 'Could not load tax invoices',
            onRetry: () => ref.invalidate(renterInvoicesProvider),
          ),
          data: (all) {
            final rows = all
                .where((r) => leaseId == null || r['leaseId'] == leaseId)
                .toList();
            if (rows.isEmpty) return const SizedBox.shrink();
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  ar
                      ? 'الفواتير الضريبية والإشعارات الدائنة'
                      : 'Tax invoices & credit notes',
                  style: MiftahType.cardTitle(),
                ),
                for (final row in rows)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text('${row['invoiceNumber']}'),
                    subtitle: Text(
                      '${Formatters.date(row['issueDate'] as String?, ar: ar)} · ${Formatters.currency((row['totalAmount'] as num?) ?? 0)}',
                    ),
                    trailing: RenterDocumentButton(
                      path: '/v1/tax-invoices/${row['id']}/pdf',
                      filename: 'invoice-${row['id']}.pdf',
                      label: ar ? 'تحميل' : 'Download',
                    ),
                  ),
              ],
            );
          },
        );
  }
}
