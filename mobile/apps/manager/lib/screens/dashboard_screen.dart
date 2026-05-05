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

final _dashboardDataProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(_dashboardServiceProvider);
  return service.getSummary();
});

/// Manager Today / Home screen.
///
/// Built to the new sand/navy/gold design (handoff: mobile-manager.jsx →
/// `ManagerHome`). Layout from top:
///   1. Greeting strip — date + "Hi, {name}" + notifications bell
///   2. Today summary card — navy gradient with gold radial accent,
///      headline (X actions need you), 3 mini-stats
///   3. Quick actions — 4-column grid; first (Scan cheque) is gold-filled
///   4. Today's queue — task feed derived from overdue / pending /
///      expiring / draft counts (no new backend endpoints)
///   5. Portfolio glance — single surface card with monthly revenue +
///      occupancy + progress bar
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

    return RefreshIndicator(
      onRefresh: _refresh,
      color: AppColors.primary,
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 130),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _Header(
              userName: _firstName(auth.name),
              unreadNotifications: notifications.unreadCount,
            ),
            const SizedBox(height: 18),
            dashboardAsync.when(
              loading: () => const _DashboardShimmer(),
              error: (e, _) => ErrorState(
                  message: 'Failed to load dashboard', onRetry: _refresh),
              data: (data) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _TodayCard(data: data),
                  const SizedBox(height: 18),
                  _QuickActions(),
                  const SizedBox(height: 22),
                  _TodaysQueue(data: data),
                  const SizedBox(height: 22),
                  _PortfolioGlance(data: data),
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
  const _Header({required this.userName, required this.unreadNotifications});

  @override
  Widget build(BuildContext context) {
    final dateLine = DateFormat('EEE · dd MMM').format(DateTime.now());
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                dateLine,
                style: GoogleFonts.inter(
                    fontSize: 11.5, color: AppColors.textMuted),
              ),
              const SizedBox(height: 2),
              Text(
                'Hi, $userName',
                style: GoogleFonts.inter(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: AppColors.textPrimary,
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
              color: AppColors.surface,
              border: Border.all(color: AppColors.border),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(icon, size: 16, color: AppColors.textPrimary),
          ),
          if (badge != null)
            Positioned(
              top: -3,
              right: -3,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
                decoration: BoxDecoration(
                  color: AppColors.danger,
                  shape: BoxShape.rectangle,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: AppColors.background, width: 2),
                ),
                child: Center(
                  child: Text(
                    '$badge',
                    style: GoogleFonts.inter(
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

// ─── Today summary card ──────────────────────────────────────────────────────

class _TodayCard extends StatelessWidget {
  final Map<String, dynamic> data;
  const _TodayCard({required this.data});

  @override
  Widget build(BuildContext context) {
    final renewals = (data['expiringLeases'] ?? 0) as num;
    final drafts = (data['draftLeases'] ?? 0) as num;
    final overdueAmount = ((data['overdueAmount'] ?? 0) as num).toDouble();
    final pendingAmount = ((data['pendingAmount'] ?? 0) as num).toDouble();
    final actionCount = renewals.toInt() +
        drafts.toInt() +
        (overdueAmount > 0 ? 1 : 0) +
        (pendingAmount > 0 ? 1 : 0);

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.primary, AppColors.primaryLight],
        ),
      ),
      child: Stack(
        children: [
          // Gold radial accent in the top-right corner.
          Positioned(
            top: -50,
            right: -30,
            child: Container(
              width: 160,
              height: 160,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [
                    AppColors.accent.withValues(alpha: 0.3),
                    Colors.transparent,
                  ],
                  stops: const [0, 0.65],
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'TODAY',
                  style: GoogleFonts.inter(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.8,
                    color: Colors.white.withValues(alpha: 0.6),
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  actionCount == 0
                      ? 'All caught up'
                      : '$actionCount ${actionCount == 1 ? "action needs" : "actions need"} you',
                  style: GoogleFonts.sourceSerif4(
                    fontSize: 26,
                    fontWeight: FontWeight.w600,
                    letterSpacing: -0.4,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: _MiniStat(
                        n: '${renewals.toInt()}',
                        l: 'Renewals to confirm',
                        tone: AppColors.gold400,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _MiniStat(
                        n: '${drafts.toInt()}',
                        l: 'Draft leases',
                        tone: Colors.white,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _MiniStat(
                        // Show actual currency amount, not "!" — the user
                        // needs to see how much is at stake at a glance.
                        n: overdueAmount > 0
                            ? Formatters.currencyCompact(overdueAmount)
                            : '0',
                        l: 'Overdue',
                        tone: overdueAmount > 0
                            ? const Color(0xFFE89289)
                            : Colors.white,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MiniStat extends StatelessWidget {
  final String n;
  final String l;
  final Color tone;
  const _MiniStat({required this.n, required this.l, required this.tone});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          n,
          style: GoogleFonts.sourceSerif4(
            fontSize: 24,
            fontWeight: FontWeight.w600,
            color: tone,
            height: 1,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          l,
          style: GoogleFonts.inter(
            fontSize: 10.5,
            height: 1.3,
            color: Colors.white.withValues(alpha: 0.7),
          ),
        ),
      ],
    );
  }
}

// ─── Quick actions ───────────────────────────────────────────────────────────

class _QuickActions extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final actions = [
      (
        icon: Icons.qr_code_scanner_outlined,
        label: 'Scan cheque',
        primary: true,
        route: '/scan'
      ),
      (
        icon: Icons.note_add_outlined,
        label: 'New lease',
        primary: false,
        route: '/leases'
      ),
      (
        icon: Icons.payments_outlined,
        label: 'Record pay',
        primary: false,
        route: '/payments'
      ),
      (
        icon: Icons.build_outlined,
        label: 'Maintenance',
        primary: false,
        route: '/tickets'
      ),
    ];
    return GridView.count(
      crossAxisCount: 4,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: 8,
      mainAxisSpacing: 8,
      childAspectRatio: 0.95,
      children: actions
          .map((a) => InkWell(
                onTap: () => a.primary
                    // Scan is a sub-route outside the bottom-nav shell;
                    // push it so the user returns to the dashboard on close.
                    ? context.push(a.route)
                    // Other quick actions are bottom-nav tabs; use go() so
                    // we don't accumulate back-stack entries.
                    : context.go(a.route),
                borderRadius: BorderRadius.circular(14),
                child: Container(
                  decoration: BoxDecoration(
                    color: a.primary ? AppColors.accent : AppColors.surface,
                    border: Border.all(
                        color: a.primary
                            ? AppColors.accent
                            : AppColors.border),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(a.icon,
                          size: 18,
                          color: a.primary
                              ? AppColors.primary
                              : AppColors.textPrimary),
                      const SizedBox(height: 6),
                      Text(
                        a.label,
                        textAlign: TextAlign.center,
                        style: GoogleFonts.inter(
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          color: a.primary
                              ? AppColors.primary
                              : AppColors.textPrimary,
                        ),
                      ),
                    ],
                  ),
                ),
              ))
          .toList(),
    );
  }
}

// ─── Today's queue ──────────────────────────────────────────────────────────

enum _Tone { gold, amber, red, teal }

class _Task {
  final _Tone tone;
  final String tag;
  final String title;
  final String sub;
  final String time;
  final String route;
  _Task({
    required this.tone,
    required this.tag,
    required this.title,
    required this.sub,
    required this.time,
    required this.route,
  });
}

class _TodaysQueue extends StatelessWidget {
  final Map<String, dynamic> data;
  const _TodaysQueue({required this.data});

  List<_Task> _buildTasks() {
    final tasks = <_Task>[];
    final overdueAmount = ((data['overdueAmount'] ?? 0) as num).toDouble();
    final pendingAmount = ((data['pendingAmount'] ?? 0) as num).toDouble();
    final expiring = (data['expiringLeases'] ?? 0) as num;
    final drafts = (data['draftLeases'] ?? 0) as num;

    if (overdueAmount > 0) {
      tasks.add(_Task(
        tone: _Tone.red,
        tag: 'Overdue',
        title: 'Overdue payments',
        sub: Formatters.currencyCompact(overdueAmount),
        time: 'Call',
        route: '/payments',
      ));
    }
    if (pendingAmount > 0) {
      tasks.add(_Task(
        tone: _Tone.gold,
        tag: 'Collect',
        title: 'Pending payments',
        sub: Formatters.currencyCompact(pendingAmount),
        time: 'Today',
        route: '/payments',
      ));
    }
    if (expiring.toInt() > 0) {
      tasks.add(_Task(
        tone: _Tone.amber,
        tag: 'Renewal',
        title: 'Leases expiring soon',
        sub: '${expiring.toInt()} within 30 days',
        time: 'Confirm',
        route: '/leases',
      ));
    }
    if (drafts.toInt() > 0) {
      tasks.add(_Task(
        tone: _Tone.teal,
        tag: 'Draft',
        title: 'Drafts to activate',
        sub: '${drafts.toInt()} pending',
        time: 'Review',
        route: '/leases',
      ));
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
              "Today's queue",
              style: GoogleFonts.inter(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
              ),
            ),
            InkWell(
              onTap: () => context.go('/payments'),
              child: Text(
                'All →',
                style: GoogleFonts.inter(
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  color: AppColors.accent,
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
              color: AppColors.surface,
              border: Border.all(color: AppColors.border),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                const Icon(Icons.check_circle_outline,
                    color: AppColors.success, size: 18),
                const SizedBox(width: 10),
                Text(
                  'Nothing on the queue. Enjoy your day.',
                  style: GoogleFonts.inter(
                    fontSize: 12.5,
                    color: AppColors.textSecondary,
                  ),
                ),
              ],
            ),
          )
        else
          ...tasks.map((t) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _TaskRow(task: t),
              )),
      ],
    );
  }
}

class _TaskRow extends StatelessWidget {
  final _Task task;
  const _TaskRow({required this.task});

  @override
  Widget build(BuildContext context) {
    final fg = switch (task.tone) {
      _Tone.gold => AppColors.accentDark,
      _Tone.amber => AppColors.warning,
      _Tone.red => AppColors.danger,
      _Tone.teal => AppColors.info,
    };
    final bg = switch (task.tone) {
      _Tone.gold => AppColors.accentLight,
      _Tone.amber => AppColors.warningLight,
      _Tone.red => AppColors.dangerLight,
      _Tone.teal => const Color(0xFFD6EBEB),
    };
    return InkWell(
      // Task routes (/payments, /leases) are bottom-nav tabs — go() so
      // the bottom nav stays consistent and we don't accumulate stack entries.
      onTap: () => context.go(task.route),
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: bg,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                task.tag.toUpperCase(),
                style: GoogleFonts.inter(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.4,
                  color: fg,
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    task.title,
                    style: GoogleFonts.inter(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: AppColors.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 1),
                  Text(
                    task.sub,
                    style: GoogleFonts.inter(
                        fontSize: 11, color: AppColors.textMuted),
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Text(
              task.time,
              style: GoogleFonts.inter(
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
                color: AppColors.textSecondary,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Portfolio glance ───────────────────────────────────────────────────────

class _PortfolioGlance extends StatelessWidget {
  final Map<String, dynamic> data;
  const _PortfolioGlance({required this.data});

  @override
  Widget build(BuildContext context) {
    final totalRevenue =
        ((data['totalRentRevenue'] ?? 0) as num).toDouble();
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
          'Portfolio',
          style: GoogleFonts.inter(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: AppColors.textPrimary,
          ),
        ),
        const SizedBox(height: 10),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: AppColors.surface,
            border: Border.all(color: AppColors.border),
            borderRadius: BorderRadius.circular(12),
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
                        'This month',
                        style: GoogleFonts.inter(
                          fontSize: 11.5,
                          color: AppColors.textMuted,
                        ),
                      ),
                      Text(
                        Formatters.currencyCompact(totalRevenue),
                        style: GoogleFonts.sourceSerif4(
                          fontSize: 22,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textPrimary,
                        ),
                      ),
                    ],
                  ),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text(
                        'Occupancy',
                        style: GoogleFonts.inter(
                          fontSize: 11.5,
                          color: AppColors.textMuted,
                        ),
                      ),
                      Text(
                        '${occupancy.toStringAsFixed(1)}%',
                        style: GoogleFonts.sourceSerif4(
                          fontSize: 22,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textPrimary,
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
                  backgroundColor: AppColors.surface2,
                  valueColor: const AlwaysStoppedAnimation(AppColors.accent),
                ),
              ),
              const SizedBox(height: 6),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    '${(progress * 100).toStringAsFixed(0)}% collected so far',
                    style: GoogleFonts.inter(
                        fontSize: 11, color: AppColors.textMuted),
                  ),
                  Text(
                    // Show collected so the bottom-right complements the
                    // headline figure (totalRevenue) rather than duplicating it.
                    Formatters.currencyCompact(collected),
                    style: GoogleFonts.jetBrainsMono(
                      fontSize: 11,
                      color: AppColors.textMuted,
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
        ShimmerLoading(
          height: 130,
          width: double.infinity,
          borderRadius: 18,
        ),
        const SizedBox(height: 18),
        GridView.count(
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
                  borderRadius: 14)),
        ),
        const SizedBox(height: 22),
        const ShimmerLoading(height: 18, width: 120),
        const SizedBox(height: 10),
        ...List.generate(
            3,
            (_) => const Padding(
                  padding: EdgeInsets.only(bottom: 8),
                  child: ShimmerLoading(
                      height: 60, width: double.infinity, borderRadius: 12),
                )),
        const SizedBox(height: 16),
        const ShimmerLoading(height: 110, width: double.infinity, borderRadius: 12),
      ],
    );
  }
}
