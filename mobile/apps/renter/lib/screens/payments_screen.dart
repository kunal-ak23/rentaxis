import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../demo/demo_flags.dart';
import '../demo/wallet_demo.dart';
import '../providers/renter_data.dart';
import '../utils/renter_rules.dart';
import '../widgets/receipt_picker_bar.dart';
import '../widgets/renter_document_button.dart';
import 'next_payment.dart';
import 'wallet/wallet_card.dart';

enum _Filter { all, due, cleared, upcoming }

class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});
  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen> {
  _Filter _filter = _Filter.all;
  String? _leaseId;
  final Set<String> _ticked = {};
  bool _downloading = false;

  Future<void> _download(Rect origin, List<String> ids) async {
    final ar = context.isAr;
    final fetch = ref.read(combinedReceiptProvider);
    final share = ref.read(shareRenterDocumentProvider);
    setState(() => _downloading = true);
    try {
      final pdf = await fetch(ids);
      if (!mounted) return;
      await share(pdf.bytes, pdf.fileName, origin);
      if (mounted) setState(() => _ticked.removeAll(ids));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              errorMessage(
                e,
                ar
                    ? 'تعذر تحميل الإيصالات. حاول مجدداً.'
                    : 'Could not download the receipts. Please retry.',
              ),
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  Future<void> _refresh() async {
    ref.invalidate(renterPaymentsProvider);
    ref.invalidate(renterLeasesProvider);
    ref.invalidate(renterPenaltiesProvider);
    ref.invalidate(renterInvoicesProvider);
    await ref.read(renterPaymentsProvider.future);
  }

  /// Contracts to choose from: my-leases (as before) plus any lease that only
  /// shows up in the payment rows (e.g. one assigned away), so the dropdown is
  /// there whenever the rows span more than one lease.
  List<({String id, String label})> _contractOptions(
    List<Map<String, dynamic>> leases,
    List<Map<String, dynamic>> payments,
  ) {
    final out = <({String id, String label})>[
      for (final row in orderedRenterLeases(leases))
        (
          id: row['id'] as String,
          label:
              '${row['propertyName'] ?? ''} · ${row['unitIdentifier'] ?? ''} · ${row['displayContractNumber'] ?? row['startDate'] ?? ''}',
        ),
    ];
    final known = {for (final o in out) o.id};
    final byLease = <String, List<Map<String, dynamic>>>{};
    for (final r in payments) {
      final id = r['leaseId'];
      if (id is String && !known.contains(id)) {
        (byLease[id] ??= []).add(r);
      }
    }
    for (final e in byLease.entries) {
      final dates =
          e.value
              .map((r) => '${r['dueDate'] ?? ''}')
              .where((d) => d.isNotEmpty)
              .toList()
            ..sort();
      final span = dates.isEmpty
          ? ''
          : dates.first == dates.last
          ? dates.first
          : '${dates.first} – ${dates.last}';
      final first = e.value.first;
      final label = [
        '${first['propertyName'] ?? ''}',
        '${first['unitIdentifier'] ?? ''}',
        span,
      ].where((p) => p.isNotEmpty).join(' · ');
      out.add((id: e.key, label: label.isEmpty ? e.key : label));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final demo = ref.watch(demoPaymentsEnabledProvider);
    // Demo only: an instalment paid through the pseudo checkout this session
    // reads as CLEARED / ONLINE here without a server round trip.
    final wallet = demo ? ref.watch(walletDemoProvider) : null;
    final payments = ref
        .watch(renterPaymentsProvider)
        .whenData(
          (rows) => wallet == null ? rows : applyDemoPayments(rows, wallet),
        );
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
                  style: MiftahType.title(color: context.miftah.textPrimary),
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
                if (demo) ...[
                  const WalletDemoCard(),
                  const SizedBox(height: MiftahSpacing.gap),
                ],
                if (leases.hasError)
                  ErrorState(
                    message: ar
                        ? 'تعذر تحميل العقود'
                        : 'Could not load contracts',
                    onRetry: () => ref.invalidate(renterLeasesProvider),
                  ),
                if (_contractOptions(
                  leases.valueOrNull ?? const [],
                  payments.valueOrNull ?? const [],
                ) case final options when options.length > 1)
                  DropdownButtonFormField<String>(
                    key: ValueKey('wallet-lease-$_leaseId'),
                    initialValue: options.any((o) => o.id == _leaseId)
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
                      for (final o in options)
                        DropdownMenuItem(
                          value: o.id,
                          child: Text(o.label, overflow: TextOverflow.ellipsis),
                        ),
                    ],
                    onChanged: (id) => setState(() {
                      _leaseId = id;
                      _ticked.clear();
                    }),
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
                    // One download is one contract: decided from the rows
                    // themselves, so it holds while the leases list loads or fails.
                    final receiptLeases = rows
                        .where(isReceiptable)
                        .map((r) => r['leaseId'])
                        .toSet();
                    final needsPick = receiptLeases.length > 1;
                    final selectable = !needsPick;
                    final years = receiptYears(rows);
                    final shown = filtered
                        .where(isReceiptable)
                        .map((r) => '${r['id']}')
                        .toSet();
                    final effective = _ticked.intersection(shown).toList();
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
                          demo
                              ? (ar
                                    ? 'ادفع الأقساط المستحقة إلكترونياً بالبطاقة أو Apple Pay أو رصيد المحفظة واربح كاش باك. الشيكات والنقد والتحويلات تسجلها الإدارة.'
                                    : 'Pay due instalments online by card, Apple Pay or wallet balance and earn cashback. Cheques, cash and transfers are still recorded by management.')
                              : (ar
                                    ? 'تُسجّل الدفعات لدى الإدارة بشيك أو نقداً أو تحويل بنكي. الشيك المودع لم تتم تسويته بعد.'
                                    : 'Payments are recorded by management by cheque, cash or bank transfer. Deposited cheques are still awaiting clearance.'),
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
                        if (needsPick) ...[
                          Text(
                            ar
                                ? 'اختر عقداً لتحديد عدة إيصالات وتحميلها معاً.'
                                : 'Pick a contract to select several receipts and download them together.',
                          ),
                          const SizedBox(height: 12),
                        ] else if (selectable && rows.any(isReceiptable)) ...[
                          ReceiptPickerBar(
                            years: years,
                            selected: effective.length,
                            busy: _downloading,
                            onYear: (y) => setState(() {
                              // A due/upcoming view hides the cleared rows just ticked.
                              if (_filter == _Filter.due ||
                                  _filter == _Filter.upcoming) {
                                _filter = _Filter.all;
                              }
                              _ticked
                                ..clear()
                                ..addAll(idsInYear(rows, y));
                            }),
                            onDownload: (o) => _download(o, effective),
                          ),
                          const SizedBox(height: 12),
                        ],
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
                            child: _PaymentCard(
                              row: row,
                              selectable: selectable,
                              ticked: _ticked.contains('${row['id']}'),
                              onTick: (v) => setState(() {
                                v
                                    ? _ticked.add('${row['id']}')
                                    : _ticked.remove('${row['id']}');
                              }),
                            ),
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
  const _PaymentCard({
    required this.row,
    required this.selectable,
    required this.ticked,
    required this.onTick,
  });
  final Map<String, dynamic> row;
  final bool selectable;
  final bool ticked;
  final ValueChanged<bool> onTick;
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
                style: MiftahType.cardTitle(color: context.miftah.textPrimary),
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
            style: MiftahType.amount(
              size: 25,
              color: context.miftah.textPrimary,
            ),
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
          if (row['demoPaidReference'] != null)
            Text(
              '${ar ? 'دُفع إلكترونياً' : 'Paid online'} · ${row['demoPaidReference']}',
              key: ValueKey('paid-online-${row['id']}'),
              style: MiftahType.mono(size: 12, color: context.miftah.success),
            ),
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
          if (ref.watch(demoPaymentsEnabledProvider) && isOwedNow(row))
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: MiftahGoldButton(
                key: ValueKey('pay-online-${row['id']}'),
                label: ar ? 'ادفع إلكترونياً' : 'Pay online',
                icon: const Icon(Icons.bolt_rounded, size: 18),
                height: 46,
                onPressed: () => context.push('/pay/${row['id']}'),
              ),
            ),
          Wrap(
            spacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (selectable && isReceiptable(row))
                Checkbox(
                  key: ValueKey('receipt-pick-${row['id']}'),
                  value: ticked,
                  onChanged: (v) => onTick(v == true),
                ),
              if (isReceiptable(row))
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
                  style: MiftahType.cardTitle(
                    color: context.miftah.textPrimary,
                  ),
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
