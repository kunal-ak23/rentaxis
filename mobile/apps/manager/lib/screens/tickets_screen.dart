import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final _ticketsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_ticketServiceProvider);
  return service.getTickets();
});

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Category/status/priority terms mirror the renter app's tickets_screen so
/// vocabulary stays identical across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String get maintenance => ar ? 'الصيانة' : 'MAINTENANCE';
  String get triage => ar ? 'الفرز' : 'Triage';
  String urgent(int n) => ar ? 'عاجلة $n' : 'Urgent $n';
  String open(int n) => ar ? 'مفتوحة $n' : 'Open $n';
  String assigned(int n) => ar ? 'مسندة $n' : 'Assigned $n';
  String get myAssigned => ar ? 'المسندة إليّ' : 'My Assigned';
  String get allTickets => ar ? 'كل الطلبات' : 'All Tickets';
  String get statusLabel => ar ? 'الحالة' : 'Status';
  String get allStatus => ar ? 'كل الحالات' : 'All Status';
  String get priorityLabel => ar ? 'الأولوية' : 'Priority';
  String get allPriority => ar ? 'كل الأولويات' : 'All Priority';
  String get noneAssigned =>
      ar ? 'لا توجد طلبات مسندة إليك' : 'No tickets assigned to you';
  String get noneFound => ar ? 'لا توجد طلبات' : 'No tickets found';
  String get loadFailed => ar ? 'فشل تحميل الطلبات' : 'Failed to load tickets';
  String get untitled => ar ? 'بدون عنوان' : 'Untitled';
  String get unassigned => ar ? 'غير مسندة' : 'Unassigned';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get slaBreached => ar ? 'تجاوزت المهلة' : 'SLA breached';
  String slaHours(int h, int m) => ar ? 'المهلة $h س $m د' : 'SLA ${h}h ${m}m';
  String slaDays(int d) => ar ? 'المهلة $d يوم' : 'SLA ${d}d';
  String assignedTo(String name) =>
      ar ? 'مسندة إلى $name' : 'Assigned to $name';
  String raisedAgo(String time) => ar ? 'أُثيرت $time' : 'Raised $time';
  String resolvedAgo(String time) => ar ? 'تم الحل $time' : 'Resolved $time';

  String category(String value) {
    switch (value) {
      case 'PLUMBING':
        return ar ? 'سباكة' : 'Plumbing';
      case 'ELECTRICAL':
        return ar ? 'كهرباء' : 'Electrical';
      case 'HVAC':
        return ar ? 'تكييف' : 'HVAC';
      case 'APPLIANCE':
        return ar ? 'أجهزة' : 'Appliance';
      case 'STRUCTURAL':
        return ar ? 'إنشائي' : 'Structural';
      case 'PEST_CONTROL':
        return ar ? 'مكافحة حشرات' : 'Pest Control';
      case 'CLEANING':
        return ar ? 'تنظيف' : 'Cleaning';
      case 'SECURITY':
        return ar ? 'أمن' : 'Security';
      case 'MAINTENANCE':
        return ar ? 'صيانة' : 'Maintenance';
      case 'GENERAL':
      case 'OTHER':
      case '':
        return ar ? 'عام' : 'General';
      default:
        return value.replaceAll('_', ' ');
    }
  }

  String status(String value) {
    switch (value) {
      case 'OPEN':
        return ar ? 'مفتوحة' : 'OPEN';
      case 'ASSIGNED':
        return ar ? 'مسندة' : 'ASSIGNED';
      case 'IN_PROGRESS':
        return ar ? 'قيد التنفيذ' : 'IN PROGRESS';
      case 'RESOLVED':
        return ar ? 'تم الحل' : 'RESOLVED';
      case 'CLOSED':
        return ar ? 'مغلقة' : 'CLOSED';
      case 'REOPENED':
        return ar ? 'أُعيد فتحها' : 'REOPENED';
      default:
        return value.replaceAll('_', ' ');
    }
  }

  String priority(String value) {
    switch (value) {
      case 'LOW':
        return ar ? 'منخفضة' : 'LOW';
      case 'MEDIUM':
        return ar ? 'متوسطة' : 'MEDIUM';
      case 'HIGH':
        return ar ? 'مرتفعة' : 'HIGH';
      case 'URGENT':
        return ar ? 'عاجلة' : 'URGENT';
      default:
        return value.replaceAll('_', ' ');
    }
  }
}

int _priorityRank(String priority) => switch (priority) {
  'URGENT' => 0,
  'HIGH' => 1,
  'MEDIUM' => 2,
  'LOW' => 3,
  _ => 2,
};

int _lifecycleRank(String status) => switch (status) {
  'CLOSED' => 2,
  'RESOLVED' => 1,
  _ => 0,
};

/// SLA windows are derived from priority for display purposes only — a
/// purely presentational hint mirroring the design's "SLA 1h 50m" chips.
/// It reads no new data and triggers no new behavior.
Duration? _slaRemaining(dynamic createdAt, String priority) {
  if (createdAt == null) return null;
  DateTime created;
  try {
    created = DateTime.parse(createdAt.toString()).toLocal();
  } catch (_) {
    return null;
  }
  final windowHours = switch (priority) {
    'URGENT' => 2,
    'HIGH' => 6,
    'MEDIUM' => 48,
    _ => 72,
  };
  final deadline = created.add(Duration(hours: windowHours));
  return deadline.difference(DateTime.now());
}

String _slaText(_L l, Duration? remaining) {
  if (remaining == null) return '';
  if (remaining.isNegative) return l.slaBreached;
  if (remaining.inHours < 24) {
    return l.slaHours(remaining.inHours, remaining.inMinutes % 60);
  }
  return l.slaDays(remaining.inDays);
}

class TicketsScreen extends ConsumerStatefulWidget {
  const TicketsScreen({super.key});

  @override
  ConsumerState<TicketsScreen> createState() => _TicketsScreenState();
}

class _TicketsScreenState extends ConsumerState<TicketsScreen> {
  int _segment = 0; // 0 = My Assigned, 1 = All
  String? _statusFilter;
  String? _priorityFilter;

  final _statusOptions = [
    null,
    'OPEN',
    'ASSIGNED',
    'IN_PROGRESS',
    'RESOLVED',
    'CLOSED',
  ];

  final _priorityOptions = [null, 'LOW', 'MEDIUM', 'HIGH', 'URGENT'];

  Future<void> _refresh() async {
    ref.invalidate(_ticketsProvider);
  }

  Future<void> _pickFilter({
    required String title,
    required List<String?> options,
    required String? current,
    required String Function(String? value) label,
    required ValueChanged<String?> onSelected,
  }) async {
    final m = context.miftah;
    final l = _L(context.isAr);
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: m.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
                child: Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: Text(
                    title,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: m.textMuted,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 10.5,
                            letterSpacing: 2.2,
                            color: m.textMuted,
                          ),
                  ),
                ),
              ),
              for (final option in options)
                ListTile(
                  title: Text(
                    label(option),
                    style: GoogleFonts.josefinSans(
                      fontSize: 14,
                      color: option == current
                          ? AppColors.accent
                          : m.textPrimary,
                      fontWeight: option == current
                          ? FontWeight.w600
                          : FontWeight.w400,
                    ),
                  ),
                  trailing: option == current
                      ? const Icon(Icons.check_rounded, color: AppColors.accent)
                      : null,
                  onTap: () {
                    onSelected(option);
                    Navigator.pop(ctx);
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final ticketsAsync = ref.watch(_ticketsProvider);
    final authState = ref.watch(authProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _buildChromeHeader(ticketsAsync, l),
          _buildFilterRow(m, l),
          Expanded(
            child: ticketsAsync.when(
              loading: () => Padding(
                padding: const EdgeInsets.all(20),
                child: ListShimmer(itemCount: 3),
              ),
              error: (e, _) =>
                  ErrorState(message: l.loadFailed, onRetry: _refresh),
              data: (tickets) {
                var filtered = tickets.where((t) {
                  if (_segment == 0) {
                    final assignedTo = t['assignedToId'] ?? t['assignedTo'];
                    if (assignedTo != authState.userId) return false;
                  }
                  if (_statusFilter != null && t['status'] != _statusFilter) {
                    return false;
                  }
                  if (_priorityFilter != null &&
                      t['priority'] != _priorityFilter) {
                    return false;
                  }
                  return true;
                }).toList();

                // Urgency-sorted: active tickets first (most urgent, oldest
                // raised, first), resolved next, closed at the bottom.
                filtered.sort((a, b) {
                  final aStatus = (a['status'] ?? 'OPEN').toString();
                  final bStatus = (b['status'] ?? 'OPEN').toString();
                  final lifecycle = _lifecycleRank(
                    aStatus,
                  ).compareTo(_lifecycleRank(bStatus));
                  if (lifecycle != 0) return lifecycle;
                  final priorityCmp =
                      _priorityRank(
                        (a['priority'] ?? 'MEDIUM').toString(),
                      ).compareTo(
                        _priorityRank((b['priority'] ?? 'MEDIUM').toString()),
                      );
                  if (priorityCmp != 0) return priorityCmp;
                  return (a['createdAt'] ?? '').toString().compareTo(
                    (b['createdAt'] ?? '').toString(),
                  );
                });

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.confirmation_number_outlined,
                    title: _segment == 0 ? l.noneAssigned : l.noneFound,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: EdgeInsets.fromLTRB(
                      20,
                      12,
                      20,
                      AppInsets.bottomNav(context, spacing: 100),
                    ),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final ticket = filtered[index];
                      return AnimatedListItem(
                        index: index,
                        child: _TicketCard(
                          ticket: ticket,
                          l: l,
                          onTap: () => context.push('/tickets/${ticket['id']}'),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: Padding(
        padding: const EdgeInsets.only(bottom: 120),
        child: FloatingActionButton(
          backgroundColor: AppColors.accent,
          foregroundColor: AppColors.primary,
          onPressed: () => context.push('/tickets/create'),
          child: const Icon(Icons.add),
        ),
      ),
    );
  }

  /// Dark chrome header per admin design 1g/2d: tracked "MAINTENANCE"
  /// overline, Cinzel "Triage" title, and informational urgency counts.
  Widget _buildChromeHeader(AsyncValue<List<dynamic>> ticketsAsync, _L l) {
    final tickets = ticketsAsync.valueOrNull ?? const [];
    final urgentCount = tickets
        .where((t) => (t['priority'] ?? '') == 'URGENT')
        .length;
    final openCount = tickets
        .where((t) => (t['status'] ?? '') == 'OPEN')
        .length;
    final assignedCount = tickets
        .where(
          (t) =>
              (t['status'] ?? '') == 'ASSIGNED' ||
              (t['status'] ?? '') == 'IN_PROGRESS',
        )
        .length;

    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 22, 20, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.maintenance,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: AppColors.goldMid,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 9,
                    letterSpacing: 3.4,
                    color: AppColors.goldMid,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            l.triage,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 20,
                    fontWeight: FontWeight.w600,
                    color: AppColors.gold400,
                  )
                : GoogleFonts.cinzel(fontSize: 23, color: AppColors.gold400),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 7,
            runSpacing: 7,
            children: [
              _CountChip(label: l.urgent(urgentCount), emphasize: true),
              _CountChip(label: l.open(openCount)),
              _CountChip(label: l.assigned(assignedCount)),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              _SegmentChip(
                label: l.myAssigned,
                selected: _segment == 0,
                ar: l.ar,
                onTap: () => setState(() {
                  _segment = 0;
                  _refresh();
                }),
              ),
              const SizedBox(width: 8),
              _SegmentChip(
                label: l.allTickets,
                selected: _segment == 1,
                ar: l.ar,
                onTap: () => setState(() {
                  _segment = 1;
                  _refresh();
                }),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildFilterRow(LegacyMiftahColors m, _L l) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 4),
      child: Row(
        children: [
          Expanded(
            child: _FilterButton(
              label: _statusFilter != null
                  ? l.status(_statusFilter!)
                  : l.statusLabel,
              active: _statusFilter != null,
              onTap: () => _pickFilter(
                title: l.statusLabel,
                options: _statusOptions,
                current: _statusFilter,
                label: (v) => v == null ? l.allStatus : l.status(v),
                onSelected: (v) => setState(() => _statusFilter = v),
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: _FilterButton(
              label: _priorityFilter != null
                  ? l.priority(_priorityFilter!)
                  : l.priorityLabel,
              active: _priorityFilter != null,
              onTap: () => _pickFilter(
                title: l.priorityLabel,
                options: _priorityOptions,
                current: _priorityFilter,
                label: (v) => v == null ? l.allPriority : l.priority(v),
                onSelected: (v) => setState(() => _priorityFilter = v),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CountChip extends StatelessWidget {
  final String label;
  final bool emphasize;

  const _CountChip({required this.label, this.emphasize = false});

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5),
      decoration: BoxDecoration(
        gradient: emphasize ? LegacyMiftahGradients.gold : null,
        color: emphasize ? null : Colors.transparent,
        border: emphasize
            ? null
            : Border.all(color: Colors.white.withValues(alpha: 0.14)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: GoogleFonts.josefinSans(
          fontSize: 9.5,
          fontWeight: FontWeight.w600,
          letterSpacing: ar ? 0 : 1.2,
          color: emphasize
              ? AppColors.primary
              : Colors.white.withValues(alpha: 0.6),
        ),
      ),
    );
  }
}

class _SegmentChip extends StatelessWidget {
  final String label;
  final bool selected;
  final bool ar;
  final VoidCallback onTap;

  const _SegmentChip({
    required this.label,
    required this.selected,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: selected ? AppColors.accent : Colors.transparent,
          borderRadius: BorderRadius.circular(999),
          border: selected
              ? null
              : Border.all(color: Colors.white.withValues(alpha: 0.22)),
        ),
        child: Text(
          label,
          style: GoogleFonts.josefinSans(
            fontSize: 11.5,
            fontWeight: FontWeight.w500,
            letterSpacing: ar ? 0 : 1.2,
            color: selected
                ? AppColors.primary
                : Colors.white.withValues(alpha: 0.7),
          ),
        ),
      ),
    );
  }
}

class _FilterButton extends StatelessWidget {
  final String label;
  final bool active;
  final VoidCallback onTap;

  const _FilterButton({
    required this.label,
    required this.active,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: active ? AppColors.accent.withValues(alpha: 0.1) : m.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: active ? AppColors.accent.withValues(alpha: 0.5) : m.border,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Expanded(
              child: Text(
                label,
                style: GoogleFonts.josefinSans(
                  fontSize: 12.5,
                  letterSpacing: ar ? 0 : 0.6,
                  fontWeight: active ? FontWeight.w600 : FontWeight.w400,
                  color: active
                      ? (m.isDark ? AppColors.accent : AppColors.accentDark)
                      : m.textSecondary,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            Icon(
              Icons.expand_more_rounded,
              size: 18,
              color: active
                  ? (m.isDark ? AppColors.accent : AppColors.accentDark)
                  : m.textMuted,
            ),
          ],
        ),
      ),
    );
  }
}

class _TicketCard extends StatelessWidget {
  final Map<String, dynamic> ticket;
  final _L l;
  final VoidCallback onTap;

  const _TicketCard({
    required this.ticket,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final title = ticket['title'] ?? l.untitled;
    final status = (ticket['status'] ?? 'OPEN').toString();
    final priority = (ticket['priority'] ?? 'MEDIUM').toString();
    final category = (ticket['category'] ?? '').toString();
    final propertyName = ticket['propertyName'] ?? '';
    final unitNumber = ticket['unitNumber'] ?? '';
    final assignedToName = (ticket['assigneeName'] ?? ticket['assignedToName'])
        ?.toString();
    final isDimmed = status == 'CLOSED';
    final isResolved = status == 'RESOLVED';

    final accentColor = isResolved
        ? StatusHelper.getTicketStatusColor(status)
        : StatusHelper.getPriorityColor(priority);

    final overline = isResolved
        ? '${l.status(status)} · ${Formatters.timeAgo(ticket['updatedAt'] ?? ticket['createdAt'], ar: l.ar)}'
        : '${l.priority(priority)} · ${l.category(category)}';

    final slaText = isResolved || isDimmed
        ? ''
        : _slaText(l, _slaRemaining(ticket['createdAt'], priority));

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: isDimmed ? m.surfaceDim : m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Opacity(
        opacity: isDimmed ? 0.72 : 1,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(14),
            child: Stack(
              children: [
                if (!isDimmed)
                  PositionedDirectional(
                    start: 0,
                    top: 0,
                    bottom: 0,
                    child: Container(width: 2, color: accentColor),
                  ),
                Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Text(
                              overline,
                              style: GoogleFonts.josefinSans(
                                fontSize: 10.5,
                                fontWeight: FontWeight.w600,
                                letterSpacing: l.ar ? 0 : 1.4,
                                color: accentColor,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (slaText.isNotEmpty) ...[
                            const SizedBox(width: 8),
                            _StatusPill(
                              label: slaText,
                              color: accentColor,
                              ar: l.ar,
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 7),
                      Text(
                        title,
                        style: GoogleFonts.josefinSans(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (propertyName.toString().isNotEmpty ||
                          unitNumber.toString().isNotEmpty) ...[
                        const SizedBox(height: 3),
                        Text(
                          [
                            propertyName,
                            if (unitNumber.toString().isNotEmpty)
                              '${l.unit} $unitNumber',
                          ].join(' · '),
                          style: GoogleFonts.josefinSans(
                            fontSize: 11.5,
                            color: m.textMuted,
                          ),
                        ),
                      ],
                      const SizedBox(height: 9),
                      if (assignedToName != null &&
                          assignedToName.isNotEmpty &&
                          (status == 'ASSIGNED' || status == 'IN_PROGRESS'))
                        Row(
                          children: [
                            Container(
                              width: 22,
                              height: 22,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                border: Border.all(
                                  color: AppColors.accent.withValues(
                                    alpha: 0.3,
                                  ),
                                ),
                              ),
                              alignment: Alignment.center,
                              child: Text(
                                assignedToName.isNotEmpty
                                    ? assignedToName[0].toUpperCase()
                                    : '?',
                                style: GoogleFonts.josefinSans(
                                  fontSize: 9,
                                  color: AppColors.accent,
                                ),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                l.assignedTo(assignedToName),
                                style: GoogleFonts.josefinSans(
                                  fontSize: 11.5,
                                  color: m.textMuted,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        )
                      else
                        Text(
                          isResolved
                              ? l.resolvedAgo(
                                  Formatters.timeAgo(
                                    ticket['updatedAt'] ?? ticket['createdAt'],
                                    ar: l.ar,
                                  ),
                                )
                              : (assignedToName == null ||
                                        assignedToName.isEmpty
                                    ? '${l.unassigned} · ${l.raisedAgo(Formatters.timeAgo(ticket['createdAt'], ar: l.ar))}'
                                    : l.raisedAgo(
                                        Formatters.timeAgo(
                                          ticket['createdAt'],
                                          ar: l.ar,
                                        ),
                                      )),
                          style: GoogleFonts.josefinSans(
                            fontSize: 11.5,
                            color: m.textMuted,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  final bool ar;

  const _StatusPill({
    required this.label,
    required this.color,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: GoogleFonts.josefinSans(
          fontSize: 9.5,
          fontWeight: FontWeight.w600,
          letterSpacing: ar ? 0 : 0.8,
          color: color,
        ),
      ),
    );
  }
}
