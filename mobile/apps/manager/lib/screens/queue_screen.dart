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
  // Count each source independently.
  //
  // Awaiting all three in sequence meant one failing request — a flaky gate
  // pass fetch, a 403, a dropped connection — made the whole provider error.
  // The shell reads it as `.valueOrNull ?? 0`, so the badge silently showed
  // NOTHING WAITING while approvals were in fact queued, and the manager had no
  // signal to go and look. Hiding work is worse than showing a low number.
  //
  // QueueScreen itself already degrades this way: it renders the sources that
  // did load and a banner naming the one that did not. The badge now matches
  // that, rather than being the one place a single failure blanks everything.
  Future<int> countOf(Future<int> Function() read) async {
    try {
      return await read();
    } catch (_) {
      return 0;
    }
  }

  final passes = await countOf(
    () async => (await ref.watch(approvalsProvider.future)).length,
  );
  final bookings = await countOf(
    () async => (await ref.watch(
      bookingsProvider((propertyId: null, status: 'PENDING')).future,
    )).rows.length,
  );
  final leases = await countOf(() async {
    final rows = await ref.watch(_queueLeasesProvider.future);
    return rows
        .whereType<Map<String, dynamic>>()
        .where((l) => l['status'] == 'PENDING_SIGNATURE')
        .length;
  });

  return passes + bookings + leases;
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
              title: (b['resourceName'] ?? l.aBooking).toString(),
              subtitle: l.bookingSub(
                b['renterName']?.toString(),
                l.propertyName(b),
                b['unitNumber']?.toString(),
                b['preferredDate']?.toString(),
                b['preferredEndDate']?.toString(),
                b['preferredStartTime']?.toString(),
                b['preferredEndTime']?.toString(),
              ),
              route: Uri(
                path: '/bookings',
                queryParameters: {
                  if (b['propertyId'] != null)
                    'propertyId': '${b['propertyId']}',
                  if (b['id'] != null) 'bookingId': '${b['id']}',
                },
              ).toString(),
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
    final m = context.miftah;
    return Container(
      color: m.surface,
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Expanded(
            child: Text(
              l.title,
              style: l.ar
                  ? MiftahType.ar(
                      size: 21,
                      weight: FontWeight.w700,
                      color: m.textPrimary,
                    )
                  : MiftahType.title(color: m.textPrimary),
            ),
          ),
          Text(
            l.waitingCount(count),
            style: MiftahType.mono(size: 12.5, color: m.textMuted),
          ),
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
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: m.dangerBg,
        border: Border.all(color: m.danger),
        borderRadius: BorderRadius.circular(MiftahRadii.tile),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.error_outline_rounded, size: 19, color: m.danger),
          const SizedBox(width: 11),
          Expanded(
            child: Text(
              l.partialFailure(sources),
              style: l.ar
                  ? MiftahType.ar(size: 13, color: m.danger)
                  : MiftahType.body(size: 13, color: m.danger),
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
    final m = context.miftah;
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
                            ? MiftahType.ar(
                                size: 15,
                                weight: FontWeight.w700,
                                color: m.textPrimary,
                              )
                            : MiftahType.cardTitle(color: m.textPrimary),
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
                  style: l.ar
                      ? MiftahType.ar(size: 11.5, color: m.textSecondary)
                      : MiftahType.meta(color: m.textSecondary),
                ),
                if (item.when != null) ...[
                  const SizedBox(height: 5),
                  Text(
                    l.waitingSince(item.when!),
                    style: MiftahType.mono(size: 11, color: m.textMuted),
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

  String propertyName(Map<String, dynamic> booking) {
    final name =
        (ar ? booking['propertyNameAr'] : booking['propertyNameEn'])
            ?.toString() ??
        booking['propertyNameEn']?.toString() ??
        booking['propertyNameAr']?.toString();
    return name?.trim() ?? '';
  }

  String bookingSub(
    String? renter,
    String property,
    String? unit,
    String? preferredDate,
    String? preferredEndDate,
    String? preferredStartTime,
    String? preferredEndTime,
  ) {
    final when = _fmt(preferredDate);
    final parts = [
      if (property.isNotEmpty) property,
      if (unit != null && unit.isNotEmpty) ar ? 'وحدة $unit' : 'Unit $unit',
      if (renter != null && renter.isNotEmpty) renter,
      if (when != null)
        preferredEndDate == null ? when : '$when–${_fmt(preferredEndDate)}',
      if (preferredStartTime != null && preferredEndTime != null)
        '${preferredStartTime.substring(0, 5)}–${preferredEndTime.substring(0, 5)}',
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
