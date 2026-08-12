import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final ticketsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final service = ref.watch(_ticketServiceProvider);
  return service.getTickets();
});

enum _TicketFilter { all, open, closed }

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get maintenance => ar ? 'الصيانة' : 'MAINTENANCE';
  String get newRequest => ar ? '+ جديد' : '+ NEW';
  String open(int n) =>
      ar ? (n > 0 ? 'مفتوحة $n' : 'مفتوحة') : (n > 0 ? 'OPEN $n' : 'OPEN');
  String get all => ar ? 'الكل' : 'ALL';
  String get closed => ar ? 'مغلقة' : 'CLOSED';
  String get searchHint =>
      ar ? 'ابحث في طلبات الصيانة...' : 'Search tickets...';
  String get noMatching => ar ? 'لا توجد طلبات مطابقة' : 'No Matching Tickets';
  String get nothingPending => ar ? 'لا توجد طلبات بعد' : 'No Requests Yet';
  String get tryDifferentFilter => ar
      ? 'جرّب تصفية أو كلمة بحث مختلفة'
      : 'Try a different filter or search term';
  String get raiseHint => ar
      ? 'ارفع طلبًا وسيُعيَّن فني مفتاح خلال ساعتين.'
      : 'Raise a request and a Miftah technician is assigned within two hours.';
  String get raiseRequest => ar ? 'رفع طلب' : 'Raise a Request';
  String get loadFailed => ar ? 'فشل تحميل الطلبات' : 'Failed to load tickets';
  String get general => ar ? 'عام' : 'GENERAL';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get untitled => ar ? 'بدون عنوان' : 'Untitled';
  String completedRated(String rating) =>
      ar ? 'اكتملت · تقييم $rating/٥' : 'Completed · rated $rating/5';
  String completedAgo(String time) => ar ? 'اكتملت $time' : 'Completed $time';
  String assignedTo(String name) =>
      ar ? 'مسندة إلى $name' : 'Assigned to $name';
  String raisedAgo(String time) => ar ? 'أُثيرت $time' : 'Raised $time';

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
      case 'OTHER':
      case '':
        return general;
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

class TicketsScreen extends ConsumerStatefulWidget {
  const TicketsScreen({super.key});

  @override
  ConsumerState<TicketsScreen> createState() => _TicketsScreenState();
}

class _TicketsScreenState extends ConsumerState<TicketsScreen> {
  String _searchQuery = '';
  _TicketFilter _filter = _TicketFilter.all;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final ticketsAsync = ref.watch(ticketsProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _buildChromeHeader(ticketsAsync, l),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 8),
            child: _buildSearchBar(m, l),
          ),
          Expanded(
            child: ticketsAsync.when(
              data: (tickets) {
                var filtered = tickets;
                if (_filter == _TicketFilter.open) {
                  filtered = filtered
                      .where((t) => (t['status'] ?? '') != 'CLOSED')
                      .toList();
                } else if (_filter == _TicketFilter.closed) {
                  filtered = filtered
                      .where((t) => (t['status'] ?? '') == 'CLOSED')
                      .toList();
                }
                if (_searchQuery.isNotEmpty) {
                  final query = _searchQuery.toLowerCase();
                  filtered = filtered.where((t) {
                    final title = (t['title'] ?? '').toLowerCase();
                    final category = (t['category'] ?? '').toLowerCase();
                    return title.contains(query) || category.contains(query);
                  }).toList();
                }

                final hasActiveFilter =
                    _filter != _TicketFilter.all || _searchQuery.isNotEmpty;

                if (filtered.isEmpty) {
                  return _TicketsEmptyState(
                    title: hasActiveFilter ? l.noMatching : l.nothingPending,
                    subtitle: hasActiveFilter
                        ? l.tryDifferentFilter
                        : l.raiseHint,
                    actionLabel: hasActiveFilter ? null : l.raiseRequest,
                    onAction: hasActiveFilter
                        ? null
                        : () => context.push('/tickets/create'),
                    ar: l.ar,
                  );
                }

                return RefreshIndicator(
                  color: AppColors.accent,
                  onRefresh: () async => ref.invalidate(ticketsProvider),
                  child: ListView.builder(
                    padding: EdgeInsets.fromLTRB(
                      20,
                      8,
                      20,
                      24,
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
              loading: () => Padding(
                padding: const EdgeInsets.all(20),
                child: ListShimmer(itemCount: 4),
              ),
              error: (err, _) => ErrorState(
                message: l.loadFailed,
                onRetry: () => ref.invalidate(ticketsProvider),
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Dark chrome header (always near-black regardless of theme mode),
  /// per design 1f/2c: tracked "MAINTENANCE" title, "+ NEW" gold action,
  /// and status filter chips.
  Widget _buildChromeHeader(AsyncValue<List<dynamic>> ticketsAsync, _L l) {
    final tickets = ticketsAsync.valueOrNull ?? const [];
    final openCount = tickets
        .where((t) => (t['status'] ?? '') != 'CLOSED')
        .length;

    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 22, 20, 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                l.maintenance,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 16,
                        letterSpacing: 3.2,
                        color: Colors.white,
                      ),
              ),
              GestureDetector(
                onTap: () => context.push('/tickets/create'),
                child: Text(
                  l.newRequest,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    letterSpacing: l.ar ? 0 : 2.2,
                    color: AppColors.accent,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              _FilterChip(
                label: l.open(openCount),
                selected: _filter == _TicketFilter.open,
                ar: l.ar,
                onTap: () => setState(
                  () => _filter = _filter == _TicketFilter.open
                      ? _TicketFilter.all
                      : _TicketFilter.open,
                ),
              ),
              const SizedBox(width: 8),
              _FilterChip(
                label: l.all,
                selected: _filter == _TicketFilter.all,
                ar: l.ar,
                onTap: () => setState(() => _filter = _TicketFilter.all),
              ),
              const SizedBox(width: 8),
              _FilterChip(
                label: l.closed,
                selected: _filter == _TicketFilter.closed,
                ar: l.ar,
                onTap: () => setState(
                  () => _filter = _filter == _TicketFilter.closed
                      ? _TicketFilter.all
                      : _TicketFilter.closed,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSearchBar(LegacyMiftahColors m, _L l) {
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: TextField(
        onChanged: (value) => setState(() => _searchQuery = value),
        style: GoogleFonts.plusJakartaSans(fontSize: 14, color: m.textPrimary),
        decoration: InputDecoration(
          hintText: l.searchHint,
          hintStyle: GoogleFonts.plusJakartaSans(color: m.textMuted, fontSize: 14),
          prefixIcon: Icon(Icons.search, size: 20, color: m.textMuted),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 18,
            vertical: 14,
          ),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: BorderSide.none,
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: BorderSide.none,
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: AppColors.accent, width: 1.5),
          ),
          filled: true,
          fillColor: m.surface,
        ),
      ),
    );
  }
}

class _FilterChip extends StatelessWidget {
  final String label;
  final bool selected;
  final bool ar;
  final VoidCallback onTap;

  const _FilterChip({
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
          style: GoogleFonts.plusJakartaSans(
            fontSize: 11.5,
            fontWeight: FontWeight.w500,
            letterSpacing: ar ? 0 : 1.6,
            color: selected
                ? AppColors.primary
                : Colors.white.withValues(alpha: 0.7),
          ),
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

  String _shortId(dynamic id) {
    final s = (id?.toString() ?? '').replaceAll('-', '');
    if (s.isEmpty) return '----';
    return s.substring(0, s.length > 6 ? 6 : s.length).toUpperCase();
  }

  int _stageFilled(String status) => switch (status) {
    'OPEN' => 1,
    'ASSIGNED' => 2,
    'IN_PROGRESS' => 2,
    'RESOLVED' => 3,
    _ => 1,
  };

  String _metaLine(bool isClosed) {
    if (isClosed) {
      final rating = ticket['satisfactionRating'] ?? ticket['rating'];
      if (rating != null) return l.completedRated(rating.toString());
      return l.completedAgo(
        Formatters.timeAgo(
          ticket['closedAt'] ?? ticket['updatedAt'] ?? ticket['createdAt'],
          ar: l.ar,
        ),
      );
    }
    final status = ticket['status'] ?? 'OPEN';
    final assignee = ticket['assigneeName'] ?? ticket['assignedToName'];
    if (assignee != null &&
        assignee.toString().isNotEmpty &&
        status != 'OPEN') {
      return l.assignedTo(assignee.toString());
    }
    return l.raisedAgo(Formatters.timeAgo(ticket['createdAt'], ar: l.ar));
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final title = ticket['title'] ?? l.untitled;
    final status = ticket['status'] ?? 'OPEN';
    final priority = ticket['priority'] ?? 'MEDIUM';
    final category = ticket['category'] ?? '';
    final propertyName =
        ticket['property']?['name'] ?? ticket['propertyName'] ?? '';
    final unitNumber =
        ticket['unit']?['unitNumber'] ?? ticket['unitNumber'] ?? '';
    final isClosed = status == 'CLOSED';
    final isUrgent = priority == 'URGENT';

    final pillLabel = isUrgent ? l.priority(priority) : l.status(status);
    final pillColor = isUrgent
        ? StatusHelper.getPriorityColor(priority)
        : StatusHelper.getTicketStatusColor(status);

    // A rounded border can't mix colors per side, so the status accent is an
    // inner strip clipped to the card's radius instead of a left BorderSide.
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: isClosed ? m.surfaceDim : m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Opacity(
        opacity: isClosed ? 0.72 : 1,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(14),
            child: Stack(
              children: [
                if (!isClosed)
                  PositionedDirectional(
                    start: 0,
                    top: 0,
                    bottom: 0,
                    child: Container(width: 2, color: pillColor),
                  ),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Text(
                              '#${_shortId(ticket['id'])} · ${l.category(category.toString())}',
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 11,
                                letterSpacing: l.ar ? 0 : 1.8,
                                color: m.textMuted,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          const SizedBox(width: 8),
                          _StatusPill(
                            label: pillLabel,
                            color: pillColor,
                            ar: l.ar,
                          ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      Text(
                        title,
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 15,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (propertyName.isNotEmpty || unitNumber.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          [
                            propertyName,
                            if (unitNumber.isNotEmpty) '${l.unit} $unitNumber',
                          ].join(' - '),
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 11.5,
                            color: m.textMuted,
                          ),
                        ),
                      ],
                      const SizedBox(height: 10),
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              _metaLine(isClosed),
                              style: GoogleFonts.plusJakartaSans(
                                fontSize: 11.5,
                                color: m.textMuted,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (!isClosed) ...[
                            const SizedBox(width: 8),
                            _StageDots(filled: _stageFilled(status), m: m),
                          ],
                        ],
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
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 10.5,
          fontWeight: FontWeight.w600,
          letterSpacing: ar ? 0 : 1.2,
          color: color,
        ),
      ),
    );
  }
}

class _StageDots extends StatelessWidget {
  final int filled;
  final LegacyMiftahColors m;

  const _StageDots({required this.filled, required this.m});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(3, (i) {
        return Container(
          margin: EdgeInsetsDirectional.only(start: i == 0 ? 0 : 4),
          width: 5,
          height: 5,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: i < filled ? AppColors.accent : m.borderStrong,
          ),
        );
      }),
    );
  }
}

/// Empty state per design 1f/2c: dashed gold-tinted card, rotated-square
/// glyph, Cinzel tracked heading, muted copy, gold tracked action link.
class _TicketsEmptyState extends StatelessWidget {
  final String title;
  final String? subtitle;
  final String? actionLabel;
  final VoidCallback? onAction;
  final bool ar;

  const _TicketsEmptyState({
    required this.title,
    this.subtitle,
    this.actionLabel,
    this.onAction,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 32),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: AppColors.accent.withValues(alpha: 0.3)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Transform.rotate(
                angle: 0.785398, // 45deg
                child: Container(
                  width: 26,
                  height: 26,
                  decoration: BoxDecoration(
                    border: Border.all(
                      color: AppColors.accent.withValues(alpha: 0.5),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Text(
                ar ? title : title.toUpperCase(),
                style: ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 14,
                        letterSpacing: 1.6,
                        color: m.textPrimary,
                      ),
                textAlign: TextAlign.center,
              ),
              if (subtitle != null) ...[
                const SizedBox(height: 10),
                Text(
                  subtitle!,
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 12.5,
                    color: m.textMuted,
                    height: 1.5,
                  ),
                  textAlign: TextAlign.center,
                ),
              ],
              if (actionLabel != null && onAction != null) ...[
                const SizedBox(height: 18),
                GestureDetector(
                  onTap: onAction,
                  child: Text(
                    ar ? actionLabel! : actionLabel!.toUpperCase(),
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      letterSpacing: ar ? 0 : 1.8,
                      color: m.isDark ? AppColors.accent : AppColors.accentDark,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
