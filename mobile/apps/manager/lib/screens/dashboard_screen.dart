import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _dashboardServiceProvider = Provider<DashboardService>((ref) {
  final client = ref.watch(apiClientProvider);
  return DashboardService(client.dio);
});

final _dashboardDataProvider = FutureProvider.autoDispose<Map<String, dynamic>>(
  (ref) async {
    final service = ref.watch(_dashboardServiceProvider);
    return service.getSummary();
  },
);

/// Manager Today / Home screen.
///
/// Restyled to the Miftah admin design (mock 1a light / 2a dark): overline
/// greeting strip, a 2x2 KPI grid with Cinzel numbers + tracked uppercase
/// Josefin labels, a "needs attention" list with semantic left accents, and
/// a quick-actions row. Layout from top:
///   1. Greeting strip — date + "Hi, {name}" + notifications bell
///   2. KPI grid — occupancy, collected, overdue, renewals due
///   3. Needs attention — task feed derived from overdue / pending /
///      expiring / draft counts (no new backend endpoints)
///   4. Quick actions — 4-tile row; first (Scan cheque) is gold-filled
///   5. Portfolio glance — surface card with monthly revenue + occupancy +
///      progress bar
class DashboardScreen extends ConsumerStatefulWidget {
  const DashboardScreen({super.key});

  @override
  ConsumerState<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends ConsumerState<DashboardScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_dashboardDataProvider);
    ref.read(notificationProvider.notifier).fetchUnreadCount();
  }

  @override
  Widget build(BuildContext context) {
    final dashboardAsync = ref.watch(_dashboardDataProvider);
    final notifications = ref.watch(notificationProvider);
    final auth = ref.watch(authProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return RefreshIndicator(
      onRefresh: _refresh,
      color: AppColors.accent,
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsetsDirectional.fromSTEB(
          20,
          12,
          20,
          AppInsets.bottomNav(context),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Header(
              userName: _firstName(auth.name),
              unreadNotifications: notifications.unreadCount,
              l: l,
            ),
            const SizedBox(height: 18),
            dashboardAsync.when(
              loading: () => const _DashboardShimmer(),
              error: (e, _) =>
                  ErrorState(message: l.loadError, onRetry: _refresh),
              data: (data) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _KpiGrid(data: data, l: l),
                  const SizedBox(height: 20),
                  _NeedsAttention(data: data, l: l, m: m),
                  const SizedBox(height: 20),
                  _QuickActions(l: l),
                  const SizedBox(height: 22),
                  _PortfolioGlance(data: data, l: l, m: m),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _firstName(String? fullName) {
    if (fullName == null || fullName.trim().isEmpty) return 'there';
    return fullName.trim().split(RegExp(r'\s+')).first;
  }
}

// ─── Greeting strip ─────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  final String userName;
  final int unreadNotifications;
  final _L l;
  const _Header({
    required this.userName,
    required this.unreadNotifications,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    // Arabic uses "،" not "·": the middot is visually identical to the
    // Arabic-Indic zero (٠), so "الأحد · ٢ أغسطس" reads as "20 August".
    final dateLine = l.ar
        ? DateFormat('EEE، d MMMM', 'ar').format(DateTime.now())
        : DateFormat('EEE · dd MMM').format(DateTime.now());
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                dateLine,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 11.5,
                        color: m.textMuted,
                      )
                    : LegacyMiftahType.overline(
                        fontSize: 9,
                        letterSpacing: 2.4,
                        color: AppColors.accentDark,
                      ),
              ),
              const SizedBox(height: 4),
              Text(
                l.greeting(userName),
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 20,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
              ),
            ],
          ),
        ),
        _IconBtn(
          icon: Icons.notifications_outlined,
          badge: unreadNotifications > 0 ? unreadNotifications : null,
          onTap: () => context.push('/notifications'),
        ),
      ],
    );
  }
}

class _IconBtn extends StatelessWidget {
  final IconData icon;
  final int? badge;
  final VoidCallback onTap;
  const _IconBtn({required this.icon, required this.onTap, this.badge});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: m.surface,
              border: Border.all(color: m.border),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(icon, size: 16, color: m.textPrimary),
          ),
          if (badge != null)
            PositionedDirectional(
              top: -3,
              end: -3,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
                decoration: BoxDecoration(
                  color: m.danger,
                  shape: BoxShape.rectangle,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: m.background, width: 2),
                ),
                child: Center(
                  child: Text(
                    '$badge',
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: Colors.white,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ─── KPI grid ───────────────────────────────────────────────────────────────

class _KpiGrid extends StatelessWidget {
  final Map<String, dynamic> data;
  final _L l;
  const _KpiGrid({required this.data, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final occupancy = ((data['occupancyRate'] ?? 0) as num).toDouble();
    final collected = ((data['collectedAmount'] ?? 0) as num).toDouble();
    final pending = ((data['pendingAmount'] ?? 0) as num).toDouble();
    final overdue = ((data['overdueAmount'] ?? 0) as num).toDouble();
    final dueThisPeriod = collected + pending + overdue;
    final expiring = (data['expiringLeases'] ?? 0) as num;
    final drafts = (data['draftLeases'] ?? 0) as num;

    return GridView.count(
      // Nested in a scroll view: without this the sliver auto-pads
      // with MediaQuery.padding, which under extendBody carries the
      // floating nav height and opens a gap below the content.
      padding: EdgeInsets.zero,
      crossAxisCount: 2,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: 10,
      mainAxisSpacing: 10,
      childAspectRatio: 1.55,
      children: [
        _KpiCell(
          label: l.occupancy,
          value: occupancy.toStringAsFixed(0),
          suffix: '%',
          m: m,
          l: l,
          progress: (occupancy / 100).clamp(0.0, 1.0),
        ),
        _KpiCell(
          label: l.collected,
          value: Formatters.currencyCompact(collected),
          sub: l.ofDue(Formatters.currencyCompact(dueThisPeriod)),
          m: m,
          l: l,
        ),
        _KpiCell(
          label: l.overdue,
          value: Formatters.currencyCompact(overdue),
          m: m,
          l: l,
          tone: overdue > 0 ? m.danger : null,
        ),
        _KpiCell(
          label: l.renewals,
          value: '${expiring.toInt()}',
          sub: drafts.toInt() > 0 ? l.draftsPending(drafts.toInt()) : null,
          subTone: drafts.toInt() > 0 ? AppColors.warning : null,
          m: m,
          l: l,
        ),
      ],
    );
  }
}

class _KpiCell extends StatelessWidget {
  final String label;
  final String value;
  final String? suffix;
  final String? sub;
  final Color? subTone;
  final Color? tone;
  final double? progress;
  final LegacyMiftahColors m;
  final _L l;

  const _KpiCell({
    required this.label,
    required this.value,
    required this.m,
    required this.l,
    this.suffix,
    this.sub,
    this.subTone,
    this.tone,
    this.progress,
  });

  @override
  Widget build(BuildContext context) {
    final valueColor = tone ?? m.textPrimary;
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(
          color: tone != null ? tone!.withValues(alpha: 0.28) : m.border,
        ),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.ar ? label : label.toUpperCase(),
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 11,
                    color: tone ?? m.textMuted,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 9,
                    letterSpacing: 2.0,
                    color: tone ?? m.textMuted,
                  ),
          ),
          const SizedBox(height: 5),
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                value,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 25,
                  fontWeight: FontWeight.w600,
                  color: valueColor,
                ),
              ),
              if (suffix != null)
                Text(
                  suffix!,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    color: AppColors.accentDark,
                  ),
                ),
            ],
          ),
          if (progress != null) ...[
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(999),
              child: LinearProgressIndicator(
                value: progress,
                minHeight: 5,
                backgroundColor: m.surfaceAlt,
                valueColor: AlwaysStoppedAnimation(AppColors.accent),
              ),
            ),
          ] else if (sub != null) ...[
            const SizedBox(height: 5),
            Text(
              sub!,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 11,
                      color: subTone ?? m.textSecondary,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 10.5,
                      color: subTone ?? m.textSecondary,
                    ),
            ),
          ],
        ],
      ),
    );
  }
}

// ─── Needs attention ────────────────────────────────────────────────────────

enum _Tone { gold, amber, red, teal }

class _Task {
  final _Tone tone;
  final String tag;
  final String title;
  final String sub;
  final String action;
  final String route;
  _Task({
    required this.tone,
    required this.tag,
    required this.title,
    required this.sub,
    required this.action,
    required this.route,
  });
}

class _NeedsAttention extends StatelessWidget {
  final Map<String, dynamic> data;
  final _L l;
  final LegacyMiftahColors m;
  const _NeedsAttention({required this.data, required this.l, required this.m});

  List<_Task> _buildTasks() {
    final tasks = <_Task>[];
    final overdueAmount = ((data['overdueAmount'] ?? 0) as num).toDouble();
    final pendingAmount = ((data['pendingAmount'] ?? 0) as num).toDouble();
    final expiring = (data['expiringLeases'] ?? 0) as num;
    final drafts = (data['draftLeases'] ?? 0) as num;

    if (overdueAmount > 0) {
      tasks.add(
        _Task(
          tone: _Tone.red,
          tag: l.tagOverdue,
          title: l.overduePayments,
          sub: Formatters.currencyCompact(overdueAmount),
          action: l.actCall,
          route: '/payments',
        ),
      );
    }
    if (pendingAmount > 0) {
      tasks.add(
        _Task(
          tone: _Tone.gold,
          tag: l.tagCollect,
          title: l.pendingPayments,
          sub: Formatters.currencyCompact(pendingAmount),
          action: l.actToday,
          route: '/payments',
        ),
      );
    }
    if (expiring.toInt() > 0) {
      tasks.add(
        _Task(
          tone: _Tone.amber,
          tag: l.tagRenewal,
          title: l.leasesExpiring,
          sub: l.withinDays(expiring.toInt()),
          action: l.actConfirm,
          route: '/leases',
        ),
      );
    }
    if (drafts.toInt() > 0) {
      tasks.add(
        _Task(
          tone: _Tone.teal,
          tag: l.tagDraft,
          title: l.draftsToActivate,
          sub: l.pendingCount(drafts.toInt()),
          action: l.actReview,
          route: '/leases',
        ),
      );
    }
    return tasks;
  }

  @override
  Widget build(BuildContext context) {
    final tasks = _buildTasks();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          crossAxisAlignment: CrossAxisAlignment.baseline,
          textBaseline: TextBaseline.alphabetic,
          children: [
            Text(
              l.ar ? l.needsAttention : l.needsAttention.toUpperCase(),
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: AppColors.accentDark,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 10,
                      letterSpacing: 2.4,
                      color: AppColors.accentDark,
                    ),
            ),
            InkWell(
              onTap: () => context.go('/payments'),
              child: Text(
                l.itemsCount(tasks.length),
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 11,
                        color: AppColors.accentDark,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 11,
                        color: AppColors.accentDark,
                      ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        if (tasks.isEmpty)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 18),
            decoration: BoxDecoration(
              color: m.surface,
              border: Border.all(color: m.border),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Row(
              children: [
                Icon(Icons.check_circle_outline, color: m.success, size: 18),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    l.allCaughtUp,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13,
                            color: m.textSecondary,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 12.5,
                            color: m.textSecondary,
                          ),
                  ),
                ),
              ],
            ),
          )
        else
          ...tasks.map(
            (t) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: _TaskRow(task: t, l: l, m: m),
            ),
          ),
      ],
    );
  }
}

class _TaskRow extends StatelessWidget {
  final _Task task;
  final _L l;
  final LegacyMiftahColors m;
  const _TaskRow({required this.task, required this.l, required this.m});

  @override
  Widget build(BuildContext context) {
    final fg = switch (task.tone) {
      _Tone.gold => AppColors.accentDark,
      _Tone.amber => AppColors.warning,
      _Tone.red => m.danger,
      _Tone.teal => AppColors.info,
    };
    // A rounded border can't mix per-side colors, so the accent is an inner
    // clipped strip rather than a left BorderSide (see renter _TicketCard).
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(14),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          // Task routes (/payments, /leases) are bottom-nav tabs — go() so
          // the bottom nav stays consistent and we don't accumulate stack entries.
          onTap: () => context.go(task.route),
          child: Stack(
            children: [
              PositionedDirectional(
                start: 0,
                top: 0,
                bottom: 0,
                child: Container(width: 3, color: fg),
              ),
              Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(15, 12, 13, 12),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            task.title,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 14,
                                    color: m.textPrimary,
                                  )
                                : GoogleFonts.plusJakartaSans(
                                    fontSize: 13.5,
                                    color: m.textPrimary,
                                  ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            task.sub,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 11.5,
                                    color: m.textMuted,
                                  )
                                : GoogleFonts.plusJakartaSans(
                                    fontSize: 11.5,
                                    color: m.textMuted,
                                  ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 9,
                        vertical: 3,
                      ),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(999),
                        color: fg.withValues(alpha: 0.1),
                        border: Border.all(color: fg.withValues(alpha: 0.28)),
                      ),
                      child: Text(
                        l.ar ? task.action : task.action.toUpperCase(),
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 10.5,
                                fontWeight: FontWeight.w600,
                                color: fg,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 9,
                                letterSpacing: 1.2,
                                fontWeight: FontWeight.w600,
                                color: fg,
                              ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─── Quick actions ───────────────────────────────────────────────────────────

class _QuickActions extends StatelessWidget {
  final _L l;
  const _QuickActions({required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final actions = [
      (
        icon: Icons.qr_code_scanner_outlined,
        label: l.scanCheque,
        primary: true,
        route: '/scan',
      ),
      (
        icon: Icons.note_add_outlined,
        label: l.newLease,
        primary: false,
        route: '/leases',
      ),
      (
        icon: Icons.payments_outlined,
        label: l.recordPay,
        primary: false,
        route: '/payments',
      ),
      (
        icon: Icons.build_outlined,
        label: l.maintenance,
        primary: false,
        route: '/tickets',
      ),
    ];
    return GridView.count(
      // Nested in a scroll view: without this the sliver auto-pads
      // with MediaQuery.padding, which under extendBody carries the
      // floating nav height and opens a gap below the content.
      padding: EdgeInsets.zero,
      crossAxisCount: 4,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: 8,
      mainAxisSpacing: 8,
      childAspectRatio: 0.95,
      children: actions
          .map(
            (a) => InkWell(
              onTap: () => a.primary
                  // Scan is a sub-route outside the bottom-nav shell;
                  // push it so the user returns to the dashboard on close.
                  ? context.push(a.route)
                  // Other quick actions are bottom-nav tabs; use go() so
                  // we don't accumulate back-stack entries.
                  : context.go(a.route),
              borderRadius: BorderRadius.circular(12),
              child: Container(
                decoration: BoxDecoration(
                  gradient: a.primary ? LegacyMiftahGradients.gold : null,
                  color: a.primary ? null : m.surface,
                  border: Border.all(
                    color: a.primary ? Colors.transparent : m.borderStrong,
                  ),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      a.icon,
                      size: 18,
                      color: a.primary
                          ? AppColors.primary
                          : AppColors.accentDark,
                    ),
                    const SizedBox(height: 6),
                    // A label may be one long word ("MAINTENANCE"), which no
                    // amount of wrapping can break on a word boundary — it
                    // would split mid-word. Two lines for the labels that can
                    // wrap, and scale-down for the ones that cannot.
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        l.ar ? a.label : a.label.toUpperCase(),
                        textAlign: TextAlign.center,
                        maxLines: 2,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 11,
                                height: 1.25,
                                fontWeight: FontWeight.w600,
                                color: a.primary
                                    ? AppColors.primary
                                    : m.textSecondary,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 9.5,
                                height: 1.25,
                                letterSpacing: 0.8,
                                fontWeight: FontWeight.w600,
                                color: a.primary
                                    ? AppColors.primary
                                    : m.textSecondary,
                              ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          )
          .toList(),
    );
  }
}

// ─── Portfolio glance ───────────────────────────────────────────────────────

class _PortfolioGlance extends StatelessWidget {
  final Map<String, dynamic> data;
  final _L l;
  final LegacyMiftahColors m;
  const _PortfolioGlance({
    required this.data,
    required this.l,
    required this.m,
  });

  @override
  Widget build(BuildContext context) {
    final totalRevenue = ((data['totalRentRevenue'] ?? 0) as num).toDouble();
    final collected = ((data['collectedAmount'] ?? 0) as num).toDouble();
    final pending = ((data['pendingAmount'] ?? 0) as num).toDouble();
    final overdue = ((data['overdueAmount'] ?? 0) as num).toDouble();
    final occupancy = data['occupancyRate'] != null
        ? ((data['occupancyRate'] as num).toDouble())
        : 0.0;
    // Progress = collected / total-due-this-period. totalRentRevenue is
    // the annual rent roll, which is the wrong denominator for "% collected
    // so far" — use collected + pending + overdue instead.
    final dueThisPeriod = collected + pending + overdue;
    final progress = dueThisPeriod > 0
        ? (collected / dueThisPeriod).clamp(0.0, 1.0)
        : 0.0;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          l.ar ? l.portfolio : l.portfolio.toUpperCase(),
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 13,
                  color: AppColors.accentDark,
                )
              : GoogleFonts.plusJakartaSans(
                  fontSize: 10,
                  letterSpacing: 2.4,
                  color: AppColors.accentDark,
                ),
        ),
        const SizedBox(height: 10),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: m.surface,
            border: Border.all(color: m.border),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        l.thisMonth,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 12,
                                color: m.textMuted,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 11.5,
                                color: m.textMuted,
                              ),
                      ),
                      Text(
                        Formatters.currencyCompact(totalRevenue),
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 21,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                      ),
                    ],
                  ),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        l.occupancy,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 12,
                                color: m.textMuted,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 11.5,
                                color: m.textMuted,
                              ),
                      ),
                      Text(
                        '${occupancy.toStringAsFixed(1)}%',
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 21,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 8,
                  backgroundColor: m.surfaceAlt,
                  valueColor: AlwaysStoppedAnimation(AppColors.accent),
                ),
              ),
              const SizedBox(height: 6),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    l.collectedSoFar((progress * 100).toStringAsFixed(0)),
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 11,
                            color: m.textMuted,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 11,
                            color: m.textMuted,
                          ),
                  ),
                  Text(
                    // Show collected so the bottom-right complements the
                    // headline figure (totalRevenue) rather than duplicating it.
                    Formatters.currencyCompact(collected),
                    style: GoogleFonts.jetBrainsMono(
                      fontSize: 11,
                      color: m.textMuted,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

// ─── Shimmer ────────────────────────────────────────────────────────────────

class _DashboardShimmer extends StatelessWidget {
  const _DashboardShimmer();

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        GridView.count(
          // Nested in a scroll view: without this the sliver auto-pads
          // with MediaQuery.padding, which under extendBody carries the
          // floating nav height and opens a gap below the content.
          padding: EdgeInsets.zero,
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 10,
          mainAxisSpacing: 10,
          childAspectRatio: 1.55,
          children: List.generate(
            4,
            (_) => const ShimmerLoading(
              height: double.infinity,
              width: double.infinity,
              borderRadius: 14,
            ),
          ),
        ),
        const SizedBox(height: 20),
        const ShimmerLoading(height: 18, width: 120),
        const SizedBox(height: 10),
        ...List.generate(
          3,
          (_) => const Padding(
            padding: EdgeInsets.only(bottom: 8),
            child: ShimmerLoading(
              height: 60,
              width: double.infinity,
              borderRadius: 14,
            ),
          ),
        ),
        const SizedBox(height: 20),
        GridView.count(
          // Nested in a scroll view: without this the sliver auto-pads
          // with MediaQuery.padding, which under extendBody carries the
          // floating nav height and opens a gap below the content.
          padding: EdgeInsets.zero,
          crossAxisCount: 4,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
          childAspectRatio: 0.95,
          children: List.generate(
            4,
            (_) => const ShimmerLoading(
              height: double.infinity,
              width: double.infinity,
              borderRadius: 12,
            ),
          ),
        ),
        const SizedBox(height: 22),
        const ShimmerLoading(
          height: 110,
          width: double.infinity,
          borderRadius: 14,
        ),
      ],
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String greeting(String name) => ar ? 'مرحباً، $name' : 'Hi, $name';
  String get loadError =>
      ar ? 'تعذّر تحميل لوحة التحكم' : 'Failed to load dashboard';

  // KPI grid
  String get occupancy => ar ? 'الإشغال' : 'Occupancy';
  String get collected => ar ? 'المُحصَّل' : 'Collected';
  String ofDue(String amount) => ar ? 'من أصل $amount مستحق' : 'of $amount due';
  String get overdue => ar ? 'متأخر' : 'Overdue';
  String get renewals => ar ? 'التجديدات' : 'Renewals';
  String draftsPending(int n) =>
      ar ? '$n مسودات معلّقة' : '$n draft${n == 1 ? '' : 's'} pending';

  // Needs attention
  String get needsAttention => ar ? 'يتطلب اهتمامًا' : 'Needs attention';
  String itemsCount(int n) => ar ? '$n عناصر' : '$n items';
  String get allCaughtUp => ar
      ? 'لا يوجد ما يتطلب اهتمامك الآن.'
      : 'Nothing needs your attention right now.';
  String get tagOverdue => ar ? 'متأخر' : 'Overdue';
  String get overduePayments => ar ? 'مدفوعات متأخرة' : 'Overdue payments';
  String get actCall => ar ? 'اتصال' : 'Call';
  String get tagCollect => ar ? 'تحصيل' : 'Collect';
  String get pendingPayments => ar ? 'مدفوعات معلّقة' : 'Pending payments';
  String get actToday => ar ? 'اليوم' : 'Today';
  String get tagRenewal => ar ? 'تجديد' : 'Renewal';
  String get leasesExpiring =>
      ar ? 'عقود إيجار تنتهي قريبًا' : 'Leases expiring soon';
  String withinDays(int n) => ar ? '$n خلال 30 يومًا' : '$n within 30 days';
  String get actConfirm => ar ? 'تأكيد' : 'Confirm';
  String get tagDraft => ar ? 'مسودة' : 'Draft';
  String get draftsToActivate =>
      ar ? 'مسودات بانتظار التفعيل' : 'Drafts to activate';
  String pendingCount(int n) => ar ? '$n قيد الانتظار' : '$n pending';
  String get actReview => ar ? 'مراجعة' : 'Review';

  // Quick actions
  String get scanCheque => ar ? 'مسح الشيك' : 'Scan cheque';
  String get newLease => ar ? 'عقد جديد' : 'New lease';
  String get recordPay => ar ? 'تسجيل دفعة' : 'Record pay';
  String get maintenance => ar ? 'الصيانة' : 'Maintenance';

  // Portfolio glance
  String get portfolio => ar ? 'المحفظة' : 'Portfolio';
  String get thisMonth => ar ? 'هذا الشهر' : 'This month';
  String collectedSoFar(String pct) =>
      ar ? '$pct% تم تحصيله حتى الآن' : '$pct% collected so far';
}
