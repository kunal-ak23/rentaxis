import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/gate_pass_provider.dart';

/// One walk-in photo per pass id, fetched once and cached while any listener
/// remains. A `FutureBuilder` whose future is created in `build` would re-issue
/// GET /v1/gatepass/walk-in/{id}/photo — the full image bytes — every time the
/// enclosing card rebuilds (each decision flips `_busy` twice), flickering the
/// avatar back to the placeholder mid-decision. `autoDispose` drops the bytes
/// once the screen is left, matching the other gate providers.
final _walkInPhotoProvider = FutureProvider.autoDispose
    .family<Uint8List, String>((ref, passId) {
      return ref.watch(gatePassServiceProvider).walkInPhoto(passId);
    });

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'الزوار بانتظار الموافقة' : 'Visitors waiting';
  String get loadFailed =>
      ar ? 'تعذر تحميل طلبات الزوار.' : 'Could not load visitor requests.';
  String get noVisitors => ar
      ? 'لا يوجد زوار بانتظار الموافقة.'
      : 'No visitors are waiting for approval.';
  String get visitor => ar ? 'زائر' : 'Visitor';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get reject => ar ? 'رفض' : 'Reject';
  String get approve => ar ? 'موافقة' : 'Approve';
  String get saving => ar ? 'جارٍ الحفظ…' : 'Saving…';
  String get approved => ar ? 'تمت الموافقة على الزائر.' : 'Visitor approved.';
  String get rejected => ar ? 'تم رفض الزائر.' : 'Visitor rejected.';
  String get updateFailed =>
      ar ? 'تعذر تحديث هذا الطلب.' : 'Could not update this request.';

  String visitorType(String value) {
    switch (value.toUpperCase()) {
      case 'GUEST':
        return ar ? 'ضيف' : 'Guest';
      case 'DELIVERY':
        return ar ? 'توصيل' : 'Delivery';
      case 'MAID':
        return ar ? 'خادمة' : 'Maid';
      case 'MILK_VENDOR':
        return ar ? 'بائع الحليب' : 'Milk Vendor';
      case 'LAUNDRY_VENDOR':
        return ar ? 'بائع الغسيل' : 'Laundry Vendor';
      case 'SERVICE_VENDOR':
        return ar ? 'مزود خدمة' : 'Service Vendor';
      case 'OTHER':
      case '':
        return ar ? 'أخرى' : 'Other';
      default:
        return value.replaceAll('_', ' ');
    }
  }
}

class ResidentApprovalsScreen extends ConsumerWidget {
  const ResidentApprovalsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final requests = ref.watch(residentGateApprovalsProvider);
    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(title: Text(l.title)),
      body: RefreshIndicator(
        color: m.isDark ? AppColors.accent : AppColors.primary,
        onRefresh: () => ref.refresh(residentGateApprovalsProvider.future),
        child: requests.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, stack) => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: [
              const SizedBox(height: 180),
              Center(child: Text(l.loadFailed)),
            ],
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: [
                  const SizedBox(height: 180),
                  Icon(
                    Icons.verified_user_outlined,
                    size: 52,
                    color: m.textMuted,
                  ),
                  const SizedBox(height: 12),
                  Center(child: Text(l.noVisitors)),
                ],
              );
            }
            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              itemCount: rows.length,
              itemBuilder: (context, index) =>
                  _ApprovalCard(request: rows[index]),
            );
          },
        ),
      ),
    );
  }
}

class _ApprovalCard extends ConsumerStatefulWidget {
  const _ApprovalCard({required this.request});
  final Map<String, dynamic> request;

  @override
  ConsumerState<_ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends ConsumerState<_ApprovalCard> {
  bool _busy = false;

  Future<void> _decide(bool approved) async {
    final l = _L(context.isAr);
    final id = widget.request['id']?.toString();
    if (id == null || _busy) return;
    setState(() => _busy = true);
    try {
      await ref.read(gatePassServiceProvider).decideAsResident(id, approved);
      ref.invalidate(residentGateApprovalsProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(approved ? l.approved : l.rejected)),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.updateFailed)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final request = widget.request;
    final id = request['id']?.toString();
    final name = request['guestName']?.toString() ?? l.visitor;
    final type = l.visitorType(request['visitorType']?.toString() ?? '');
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _VisitorPhoto(passId: id),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        name,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        '$type · ${l.unit} ${request['unitNumber'] ?? ''}',
                        style: TextStyle(color: m.textSecondary),
                      ),
                      if (request['purpose'] != null)
                        Text(request['purpose'].toString()),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy ? null : () => _decide(false),
                    child: Text(l.reject),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton(
                    onPressed: _busy ? null : () => _decide(true),
                    child: Text(_busy ? l.saving : l.approve),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _VisitorPhoto extends ConsumerWidget {
  const _VisitorPhoto({required this.passId});
  final String? passId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final id = passId;
    if (id == null) {
      return const CircleAvatar(radius: 30, child: Icon(Icons.person));
    }
    final photo = ref.watch(_walkInPhotoProvider(id)).valueOrNull;
    // Empty bytes (the service's null-body fallback) are "no photo", not an
    // image — MemoryImage would throw trying to decode them.
    final hasPhoto = photo != null && photo.isNotEmpty;
    return CircleAvatar(
      radius: 30,
      backgroundImage: hasPhoto ? MemoryImage(photo) : null,
      child: hasPhoto ? null : const Icon(Icons.person),
    );
  }
}
