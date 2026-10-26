import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../providers/renter_data.dart';
import '../providers/gate_pass_provider.dart';
import '../utils/renter_rules.dart';
import '../widgets/renter_document_button.dart';

class ContractsScreen extends ConsumerWidget {
  const ContractsScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;
    Future<void> refresh() async {
      ref.invalidate(renterLeasesProvider);
      await ref.read(renterLeasesProvider.future);
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(ar ? 'عقودي' : 'My contracts'),
        actions: [
          TextButton(
            onPressed: () => context.push('/renewals'),
            child: Text(ar ? 'التجديد' : 'Renewals'),
          ),
        ],
      ),
      body: ref
          .watch(renterLeasesProvider)
          .when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => ErrorState(
              message: ar ? 'تعذر تحميل العقود' : 'Could not load contracts',
              onRetry: refresh,
            ),
            data: (rows) => RefreshIndicator(
              onRefresh: refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(20),
                children: [
                  if (rows.isEmpty)
                    EmptyState(
                      icon: Icons.description_outlined,
                      title: ar ? 'لا توجد عقود' : 'No contracts yet',
                    ),
                  for (final row in orderedRenterLeases(rows))
                    Padding(
                      padding: const EdgeInsets.only(bottom: 14),
                      child: _ContractCard(
                        key: ValueKey(
                          '${row['id']}-${row['contractDocumentId']}-${row['renterAcceptedAt']}',
                        ),
                        lease: row,
                      ),
                    ),
                ],
              ),
            ),
          ),
    );
  }
}

class _ContractCard extends ConsumerStatefulWidget {
  const _ContractCard({super.key, required this.lease});
  final Map<String, dynamic> lease;
  @override
  ConsumerState<_ContractCard> createState() => _ContractCardState();
}

class _ContractCardState extends ConsumerState<_ContractCard> {
  bool _busy = false;
  bool _reviewed = false;
  String? _error;

  Future<void> _decide(bool accept) async {
    if (_busy) return;
    final ar = context.isAr;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(
          accept
              ? (ar ? 'قبول العقد؟' : 'Accept this contract?')
              : (ar ? 'رفض العقد؟' : 'Reject this contract?'),
        ),
        content: Text(
          accept
              ? (ar
                    ? 'أؤكد أنني راجعت نسخة العقد الحالية وأوافق عليها.'
                    : 'I have reviewed and agree to the current contract document.')
              : (ar
                    ? 'سيتم إبلاغ الإدارة بقرارك.'
                    : 'Management will be informed of your decision.'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(ar ? 'إلغاء' : 'Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(ar ? 'تأكيد' : 'Confirm'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final id = widget.lease['id'];
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            '/v1/leases/$id/${accept ? 'accept' : 'reject'}',
            queryParameters: accept
                ? {'documentId': widget.lease['contractDocumentId']}
                : null,
          );
      // The renter may have left the screen while the PUT was in flight; the
      // decision is saved either way, and a disposed ref throws.
      if (!mounted) return;
      ref.invalidate(renterLeasesProvider);
      ref.invalidate(activeLeasesProvider);
    } catch (e) {
      if (mounted) {
        setState(
          () => _error = errorMessage(
            e,
            ar
                ? 'تعذر حفظ القرار. حدّث العقد وراجعه مجدداً.'
                : 'Could not save your decision. Refresh and review the current contract.',
          ),
        );
      }
      if (mounted && _error != null) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_error!)));
      }
      // A regenerated contract must be reviewed before another acceptance.
      _reviewed = false;
      if (mounted) ref.invalidate(renterLeasesProvider);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final l = widget.lease;
    final status = l['status']?.toString() ?? '';
    final pending =
        status == 'PENDING_SIGNATURE' && l['yourAccessEndedOn'] == null;
    final accepted = l['renterAcceptedAt'] != null;
    final canDownload =
        l['hasContract'] == true ||
        const {'ACTIVE', 'NOTICE_GIVEN'}.contains(status) ||
        isCurrentRenterLease(l);
    final statusLabel = switch (status) {
      'ACTIVE' => ar ? 'ساري' : 'Active',
      'NOTICE_GIVEN' => ar ? 'تم تقديم إشعار' : 'Under notice',
      'PENDING_SIGNATURE' => ar ? 'بانتظار التوقيع' : 'Awaiting signature',
      'RENEWED' => ar ? 'مجدد' : 'Renewed',
      'TERMINATED' => ar ? 'منتهي' : 'Terminated',
      'EXPIRED' => ar ? 'منتهي المدة' : 'Expired',
      'CLOSED' => ar ? 'مغلق' : 'Closed',
      _ => ar ? 'مسودة' : 'Draft',
    };
    return MiftahCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${l['propertyName'] ?? ''} · ${l['unitIdentifier'] ?? ''}',
            style: MiftahType.cardTitle(),
          ),
          Text(
            '${ar ? 'العقد' : 'Contract'} ${l['displayContractNumber'] ?? l['contractNumber'] ?? ''}',
          ),
          MiftahBadge(
            statusLabel,
            tone: isCurrentRenterLease(l)
                ? MiftahTone.success
                : MiftahTone.neutral,
          ),
          const SizedBox(height: 12),
          Text(
            '${Formatters.date(l['startDate'] as String?, ar: ar)} – ${Formatters.date(l['endDate'] as String?, ar: ar)}',
          ),
          Text(
            '${ar ? 'الإيجار الحالي' : 'Current rent'}: ${Formatters.currency((l['currentRentAmount'] ?? l['rentAmount'] ?? 0) as num)}',
          ),
          Text(
            '${ar ? 'التأمين' : 'Deposit'}: ${Formatters.currency((l['depositAmount'] ?? 0) as num)}',
          ),
          if (l['currentEjari'] != null || l['ejariNumber'] != null)
            Text(
              '${ar ? 'إيجاري' : 'Ejari'}: ${l['currentEjari'] ?? l['ejariNumber']}',
            ),
          if (l['paymentTerms'] != null)
            Text('${ar ? 'عدد الأقساط' : 'Instalments'}: ${l['paymentTerms']}'),
          if (l['paymentMethod'] != null)
            Text(
              '${ar ? 'طريقة الدفع' : 'Payment method'}: ${paymentMethodLabel('${l['paymentMethod']}', ar)}',
            ),
          if (l['intendedMoveOutDate'] != null)
            Text(
              '${ar ? 'تاريخ الإخلاء' : 'Move-out date'}: ${Formatters.date(l['intendedMoveOutDate'] as String?, ar: ar)}',
            ),
          if (l['assignedToYouOn'] != null)
            Text(
              '${ar ? 'انتقل إليك في' : 'Assigned to you on'} ${Formatters.date(l['assignedToYouOn'] as String?, ar: ar)}\n${ar ? 'الرصيد الافتتاحي' : 'Opening balance'}: ${Formatters.currency((l['openingReceivable'] ?? 0) as num)} · ${ar ? 'التأمين المنقول' : 'Deposit transferred'}: ${Formatters.currency((l['openingDeposit'] ?? 0) as num)}',
            ),
          if (l['yourAccessEndedOn'] != null)
            Text(
              '${ar ? 'انتهت إقامتك في' : 'Your occupancy ended on'} ${Formatters.date(l['yourAccessEndedOn'] as String?, ar: ar)}',
            ),
          if (l['transferredToUnit'] != null)
            Text(
              '${ar ? 'تم النقل إلى الوحدة' : 'Transferred to unit'} ${l['transferredToUnit']}',
            ),
          if (canDownload)
            RenterDocumentButton(
              // Review the exact stored document whose id is sent with acceptance.
              path: pending && l['contractDocumentId'] != null
                  ? '/v1/leases/documents/${l['contractDocumentId']}/download'
                  : '/v1/leases/${l['id']}/contract',
              filename: 'contract-${l['id']}.pdf',
              label: ar ? 'عرض / حفظ العقد' : 'View / save contract',
              onDownloaded: () => setState(() => _reviewed = true),
            ),
          if (pending && accepted)
            Text(
              ar
                  ? 'تم قبول العقد. بانتظار اعتماد الإدارة.'
                  : 'Contract accepted. Awaiting management posting.',
            ),
          if (pending &&
              !accepted &&
              l['hasContract'] == true &&
              l['contractDocumentId'] != null) ...[
            if (!_reviewed)
              Text(
                ar
                    ? 'افتح العقد الحالي وراجعه قبل القبول.'
                    : 'Open and review the current contract before accepting.',
              ),
            Wrap(
              spacing: 8,
              children: [
                FilledButton(
                  onPressed: _busy || !_reviewed ? null : () => _decide(true),
                  child: Text(ar ? 'قبول' : 'Accept'),
                ),
                TextButton(
                  onPressed: _busy ? null : () => _decide(false),
                  child: Text(ar ? 'رفض' : 'Reject'),
                ),
              ],
            ),
          ],
          if (_error != null)
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
        ],
      ),
    );
  }
}
