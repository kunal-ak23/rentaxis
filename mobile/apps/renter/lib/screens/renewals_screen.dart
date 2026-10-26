import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../providers/renter_data.dart';

class RenewalsScreen extends ConsumerStatefulWidget {
  const RenewalsScreen({super.key});
  @override
  ConsumerState<RenewalsScreen> createState() => _RenewalsScreenState();
}

class _RenewalsScreenState extends ConsumerState<RenewalsScreen> {
  bool _busy = false;
  String? _error;
  Future<void> _refresh() async {
    ref.invalidate(renterRenewalsProvider);
    await ref.read(renterRenewalsProvider.future);
  }

  Future<void> _save(String id, String intent) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('/v1/me/renewals/$id/intent', data: {'intent': intent});
    } catch (e) {
      if (mounted) {
        setState(
          () => _error = errorMessage(
            e,
            context.isAr ? 'تعذر حفظ اختيارك' : 'Could not save your choice',
          ),
        );
      }
    }
    try {
      await _refresh();
    } catch (_) {
      /* The provider renders the retry. */
    }
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    String choice(String i) => switch (i) {
      'RENEW' => ar ? 'تجديد' : 'Renew',
      'MOVE_OUT' => ar ? 'إخلاء' : 'Move out',
      _ => ar ? 'مناقشة' : 'Discuss',
    };
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'تجديد العقود' : 'Contract renewals')),
      body: ref
          .watch(renterRenewalsProvider)
          .when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => ErrorState(
              message: ar ? 'تعذر تحميل التجديدات' : 'Could not load renewals',
              onRetry: _refresh,
            ),
            data: (rows) => RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(20),
                children: [
                  if (_error != null)
                    Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  if (rows.isEmpty)
                    EmptyState(
                      icon: Icons.event_repeat,
                      title: ar ? 'لا توجد تجديدات' : 'No renewals yet',
                    ),
                  for (final row in rows)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: MiftahCard(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '${row['propertyNameEn'] ?? ''} · ${row['unitNumber'] ?? ''}',
                              style: MiftahType.cardTitle(),
                            ),
                            Text(
                              '${ar ? 'نهاية العقد' : 'Contract ends'}: ${Formatters.date(row['endDate'] as String?, ar: ar)}',
                            ),
                            if (row['ended'] == true)
                              Text(
                                ar
                                    ? 'انتهى العقد. تواصل مع الإدارة.'
                                    : 'This contract has ended. Contact management.',
                              )
                            else ...[
                              Text(
                                '${row['daysRemaining'] ?? 0} ${ar ? 'أيام متبقية' : 'days remaining'}',
                              ),
                              if (row['intent'] != null)
                                Text(
                                  '${ar ? 'اختيارك' : 'Your choice'}: ${choice(row['intent'] as String)}',
                                ),
                              if (row['opportunityId'] == null)
                                Text(
                                  ar
                                      ? 'ستظهر خيارات التجديد عند فتح نافذة التجديد.'
                                      : 'Renewal choices appear when the renewal window opens.',
                                )
                              else
                                Wrap(
                                  spacing: 8,
                                  children: [
                                    for (final intent in [
                                      'RENEW',
                                      'MOVE_OUT',
                                      'DISCUSS',
                                    ])
                                      OutlinedButton(
                                        onPressed:
                                            _busy || row['intent'] == intent
                                            ? null
                                            : () => _save(
                                                row['opportunityId'] as String,
                                                intent,
                                              ),
                                        child: Text(choice(intent)),
                                      ),
                                  ],
                                ),
                            ],
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
    );
  }
}
