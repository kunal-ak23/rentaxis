import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/gate_pass_provider.dart' show approvalsProvider;
import '../providers/facility_provider.dart' show bookingsProvider;

/// Everything waiting on a manager, in one place — design screen 08.
///
/// The redesign collapses three scattered approval surfaces (gate-pass
/// approvals, booking approvals, leases pending signature) into a single
/// Queue tab. This is a **client-side merge of existing endpoints**; the
/// handoff is explicit that no new backend surface is required.
enum _QueueKind { gatePass, booking, lease }

class _QueueItem {
  const _QueueItem({
    required this.kind,
    required this.title,
    required this.subtitle,
    required this.route,
    this.when,
  });

  final _QueueKind kind;
  final String title;
  final String subtitle;
  final String route;
  final DateTime? when;
}

final _queueLeasesProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio).getAllLeases();
});

/// The badge count the shell shows. Kept separate so the bar can render it
/// without building the screen.
final managerQueueCountProvider = FutureProvider.autoDispose<int>((ref) async {
  final passes = await ref.watch(approvalsProvider.future);
  final bookings = await ref.watch(
    bookingsProvider((propertyId: null, status: 'PENDING')).future,
  );
  final leases = await ref.watch(_queueLeasesProvider.future);
  final pendingLeases = leases
      .whereType<Map<String, dynamic>>()
      .where((l) => l['status'] == 'PENDING_SIGNATURE')
      .length;
  return passes.length + bookings.rows.length + pendingLeases;
});

class QueueScreen extends ConsumerWidget {
  const QueueScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = _L(context.isAr);
    final passes = ref.watch(approvalsProvider);
    final bookings = ref.watch(
      bookingsProvider((propertyId: null, status: 'PENDING')),
    );
    final leases = ref.watch(_queueLeasesProvider);

    Future<void> refresh() async {
      ref.invalidate(approvalsProvider);
      ref.invalidate(bookingsProvider);
      ref.invalidate(_queueLeasesProvider);
      ref.invalidate(managerQueueCountProvider);
      await ref.read(approvalsProvider.future);
    }

    final items =
        <_QueueItem>[
          for (final p in passes.valueOrNull ?? const <Map<String, dynamic>>[])
            _QueueItem(
              kind: _QueueKind.gatePass,
              title: (p['guestName'] ?? l.aVisitor).toString(),
              subtitle: l.gatePassSub(
                p['unitNumber']?.toString(),
                p['purpose']?.toString(),
              ),
              route: '/gate-passes/approvals',
              when: DateTime.tryParse(p['createdAt']?.toString() ?? ''),
            ),
          for (final b
              in bookings.valueOrNull?.rows ?? const <Map<String, dynamic>>[])
            _QueueItem(
              kind: _QueueKind.booking,
              title: (b['amenityName'] ?? b['facilityName'] ?? l.aBooking)
                  .toString(),
              subtitle: l.bookingSub(
                b['renterName']?.toString(),
                b['slotStart']?.toString() ?? b['startTime']?.toString(),
              ),
              route: '/bookings',
              when: DateTime.tryParse(b['createdAt']?.toString() ?? ''),
            ),
          for (final lease
              in (leases.valueOrNull ?? const [])
                  .whereType<Map<String, dynamic>>())
            if (lease['status'] == 'PENDING_SIGNATURE')
              _QueueItem(
                kind: _QueueKind.lease,
                title: (lease['renterName'] ?? l.aLease).toString(),
                subtitle: l.leaseSub(
                  lease['propertyName']?.toString(),
                  lease['unitNumber']?.toString(),
                ),
                route: '/leases',
                when: DateTime.tryParse(lease['createdAt']?.toString() ?? ''),
              ),
        ]..sort((a, b) {
          // Oldest first: the thing that has waited longest is the thing to do.
          final ad = a.when ?? DateTime(2100);
          final bd = b.when ?? DateTime(2100);
          return ad.compareTo(bd);
        });

    final loading = passes.isLoading || bookings.isLoading || leases.isLoading;
    // One source failing must not blank the other two — say so inline instead.
    final failed = [
      if (passes.hasError) l.sourceGatePasses,
      if (bookings.hasError) l.sourceBookings,
      if (leases.hasError) l.sourceLeases,
    ];

    return Column(
      children: [
        _QueueHeader(count: items.length, l: l),
        Expanded(
          child: RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 6, 20, 24),
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                if (failed.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: MiftahSpacing.gap),
                    child: _PartialFailure(sources: failed, l: l),
                  ),
                if (loading && items.isEmpty)
                  const Column(
                    children: [
                      ShimmerLoading(
                        height: 84,
                        borderRadius: MiftahRadii.card,
                      ),
                      SizedBox(height: MiftahSpacing.gap),
                      ShimmerLoading(
                        height: 84,
                        borderRadius: MiftahRadii.card,
                      ),
                      SizedBox(height: MiftahSpacing.gap),
                      ShimmerLoading(
                        height: 84,
                        borderRadius: MiftahRadii.card,
                      ),
                    ],
                  )
                else if (items.isEmpty && failed.isEmpty)
                  MiftahEmptyState(
                    icon: Icons.inbox_rounded,
                    title: l.emptyTitle,
                    subtitle: l.emptySub,
                  )
                else
                  for (var i = 0; i < items.length; i++)
                    Padding(
                      padding: EdgeInsets.only(
                        bottom: i == items.length - 1 ? 0 : MiftahSpacing.gap,
                      ),
                      child: AnimatedListItem(
                        index: i,
                        // The oldest item is the one to act on — the design
                        // allows exactly one emphasised card per screen.
                        child: _QueueCard(
                          item: items[i],
                          emphasised: i == 0,
                          l: l,
                        ),
                      ),
                    ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _QueueHeader extends StatelessWidget {
  const _QueueHeader({required this.count, required this.l});

  final int count;
  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context).colorScheme.surface,
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Expanded(
            child: Text(
              l.title,
              style: l.ar
                  ? MiftahType.ar(size: 21, weight: FontWeight.w700)
                  : MiftahType.title(),
            ),
          ),
          Text(l.waitingCount(count), style: MiftahType.mono(size: 12.5)),
        ],
      ),
    );
  }
}

class _PartialFailure extends StatelessWidget {
  const _PartialFailure({required this.sources, required this.l});

  final List<String> sources;
  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: MiftahColors.dangerTint,
        border: Border.all(color: MiftahColors.dangerTintBorder),
        borderRadius: BorderRadius.circular(MiftahRadii.tile),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.error_outline_rounded,
            size: 19,
            color: MiftahColors.danger,
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Text(
              l.partialFailure(sources),
              style: l.ar
                  ? MiftahType.ar(size: 13, color: MiftahColors.danger)
                  : MiftahType.body(size: 13, color: MiftahColors.danger),
            ),
          ),
        ],
      ),
    );
  }
}

class _QueueCard extends StatelessWidget {
  const _QueueCard({
    required this.item,
    required this.emphasised,
    required this.l,
  });

  final _QueueItem item;
  final bool emphasised;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final ({IconData icon, MiftahTone tone, String label}) spec =
        switch (item.kind) {
          _QueueKind.gatePass => (
            icon: Icons.qr_code_2_rounded,
            tone: MiftahTone.brass,
            label: l.kindGatePass,
          ),
          _QueueKind.booking => (
            icon: Icons.pool_rounded,
            tone: MiftahTone.info,
            label: l.kindBooking,
          ),
          _QueueKind.lease => (
            icon: Icons.draw_rounded,
            tone: MiftahTone.warning,
            label: l.kindLease,
          ),
        };

    return MiftahCard(
      emphasised: emphasised,
      onTap: () => context.push(item.route),
      child: Row(
        children: [
          MiftahIconTile(icon: spec.icon, tone: spec.tone),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        item.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: l.ar
                            ? MiftahType.ar(size: 15, weight: FontWeight.w700)
                            : MiftahType.cardTitle(),
                      ),
                    ),
                    const SizedBox(width: 8),
                    MiftahBadge(spec.label, tone: spec.tone),
                  ],
                ),
                const SizedBox(height: 3),
                Text(
                  item.subtitle,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: l.ar ? MiftahType.ar(size: 11.5) : MiftahType.meta(),
                ),
                if (item.when != null) ...[
                  const SizedBox(height: 5),
                  Text(
                    l.waitingSince(item.when!),
                    style: MiftahType.mono(size: 11),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Strings (EN/AR) ────────────────────────────────────────────────────────

class _L {
  _L(this.ar);

  final bool ar;

  String get title => ar ? 'قائمة العمل' : 'Queue';

  String waitingCount(int n) => ar ? '$n بانتظارك' : '$n waiting';

  String get emptyTitle => ar ? 'لا شيء بانتظارك' : 'Nothing waiting';
  String get emptySub => ar
      ? 'ستظهر هنا طلبات الدخول وحجوزات المرافق والعقود التي تنتظر التوقيع.'
      : 'Gate passes, amenity bookings and leases awaiting signature land '
            'here.';

  String get kindGatePass => ar ? 'تصريح' : 'GATE PASS';
  String get kindBooking => ar ? 'حجز' : 'BOOKING';
  String get kindLease => ar ? 'عقد' : 'LEASE';

  String get aVisitor => ar ? 'زائر' : 'A visitor';
  String get aBooking => ar ? 'حجز مرفق' : 'Amenity booking';
  String get aLease => ar ? 'عقد إيجار' : 'Lease';

  String gatePassSub(String? unit, String? purpose) {
    final parts = [
      if (unit != null && unit.isNotEmpty) ar ? 'وحدة $unit' : 'Unit $unit',
      if (purpose != null && purpose.isNotEmpty) purpose,
    ];
    if (parts.isEmpty) return ar ? 'بانتظار موافقتك' : 'Awaiting your approval';
    return parts.join(' · ');
  }

  String bookingSub(String? renter, String? startIso) {
    final when = _fmt(startIso);
    final parts = [
      if (renter != null && renter.isNotEmpty) renter,
      ?when,
    ];
    if (parts.isEmpty) return ar ? 'طلب حجز' : 'Booking request';
    return parts.join(' · ');
  }

  String leaseSub(String? property, String? unit) {
    final parts = [
      if (property != null && property.isNotEmpty) property,
      if (unit != null && unit.isNotEmpty) ar ? 'وحدة $unit' : 'Unit $unit',
    ];
    if (parts.isEmpty) return ar ? 'بانتظار التوقيع' : 'Awaiting signature';
    return parts.join(' · ');
  }

  String waitingSince(DateTime when) {
    final days = DateTime.now().difference(when).inDays;
    if (days <= 0) return ar ? 'اليوم' : 'today';
    if (days == 1) return ar ? 'منذ يوم' : '1 day waiting';
    return ar ? 'منذ $days أيام' : '$days days waiting';
  }

  String get sourceGatePasses => ar ? 'تصاريح الدخول' : 'gate passes';
  String get sourceBookings => ar ? 'الحجوزات' : 'bookings';
  String get sourceLeases => ar ? 'العقود' : 'leases';

  /// One failing source must not hide the others — name what is missing.
  String partialFailure(List<String> sources) {
    final list = sources.join(ar ? ' و' : ', ');
    return ar
        ? 'تعذّر تحميل $list. القائمة أدناه غير مكتملة.'
        : 'Could not load $list. This list is incomplete.';
  }

  String? _fmt(String? iso) {
    if (iso == null || iso.isEmpty) return null;
    try {
      return DateFormat(
        'd MMM, HH:mm',
        ar ? 'ar' : 'en',
      ).format(DateTime.parse(iso).toLocal());
    } catch (_) {
      return null;
    }
  }
}
