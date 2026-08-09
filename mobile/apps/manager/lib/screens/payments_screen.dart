import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../widgets/mark_cheque_failed_dialog.dart';

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

/// Keyed by the selected property id (null = all properties) so the stat
/// tiles and status strip re-query GET /v1/payments/summary?propertyId=...
/// whenever the property filter changes, matching the web finance page —
/// otherwise tenant-wide totals sit above a property-scoped list.
final _paymentSummaryProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, String?>((ref, propertyId) async {
      final service = ref.watch(_paymentServiceProvider);
      return service.getSummary(propertyId: propertyId);
    });

final _propertiesForFilterProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

/// Manager cheque operations screen, per admin design 1f/2c: dark chrome
/// header, scanner entry tile + "in hand" stat, filter chips, status strip,
/// and an accent-stripped payment list. Behavior (providers, pagination,
/// filtering, action sheet) is unchanged from the pre-restyle screen.
class PaymentsScreen extends ConsumerStatefulWidget {
  const PaymentsScreen({super.key});

  @override
  ConsumerState<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends ConsumerState<PaymentsScreen>
    with SingleTickerProviderStateMixin {
  String? _selectedPropertyId;
  late TabController _tabController;

  static const _pageSize = 20;

  // Server-side pagination state for the active tab/property query: rows
  // accumulated page by page, plus the backend Page metadata. The tenant-wide
  // list can far exceed one page, so filtering a single big fetch client-side
  // (the old approach) silently truncated everything past the first request.
  List<dynamic> _rows = [];
  int _page = 0;
  int _totalPages = 1;
  int _totalElements = 0;
  bool _loading = true;
  bool _loadingMore = false;
  bool _loadFailed = false;
  int _fetchEpoch = 0;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 4, vsync: this);
    _tabController.addListener(() {
      if (!_tabController.indexIsChanging) {
        _reload();
      }
    });
    _reload();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  /// Server-side query for the active tab. Upcoming needs ascending due
  /// dates so its client-side month refinement lines up with page order;
  /// the other tabs keep the backend default (dueDate DESC). "Overdue" uses
  /// the backend's computed view (PENDING/COLLECTED/OVERDUE past due), same
  /// population the dashboard counts.
  ({String? status, bool overdue, List<String>? sort}) get _tabQuery =>
      switch (_tabController.index) {
        0 => (
          status: 'PENDING',
          overdue: false,
          sort: const ['dueDate,asc', 'id,asc'],
        ),
        1 => (status: null, overdue: true, sort: null),
        2 => (status: 'CLEARED', overdue: false, sort: null),
        _ => (status: null, overdue: false, sort: null),
      };

  Future<void> _reload() async {
    final epoch = ++_fetchEpoch;
    setState(() {
      _loading = true;
      _loadFailed = false;
      _rows = [];
      _page = 0;
      _totalPages = 1;
      _totalElements = 0;
    });
    await _fetchPage(0, epoch);
  }

  Future<void> _fetchPage(int page, int epoch) async {
    final q = _tabQuery;
    try {
      final result = await ref
          .read(_paymentServiceProvider)
          .getPaymentsPage(
            propertyId: _selectedPropertyId,
            status: q.status,
            overdue: q.overdue,
            sort: q.sort,
            page: page,
            size: _pageSize,
          );
      if (!mounted || epoch != _fetchEpoch) return;
      setState(() {
        _rows = [..._rows, ...(result['content'] as List? ?? const [])];
        _page = page;
        _totalPages = (result['totalPages'] as num?)?.toInt() ?? 1;
        _totalElements =
            (result['totalElements'] as num?)?.toInt() ?? _rows.length;
        _loading = false;
        _loadingMore = false;
      });
      // Early asc-sorted pages of the Upcoming tab can hold only past-due
      // rows, all refined out client-side — keep fetching until something
      // is visible (or the sort walks past the current month), so the tab
      // doesn't sit empty behind a Show More button while dues exist on
      // later pages. Bounded: pages strictly increase toward _totalPages.
      if (_tabController.index == 0 && _visibleRows.isEmpty && _hasMore) {
        setState(() => _loadingMore = true);
        await _fetchPage(page + 1, epoch);
      }
    } catch (_) {
      if (!mounted || epoch != _fetchEpoch) return;
      setState(() {
        _loading = false;
        _loadingMore = false;
        // Failing to extend the list shouldn't blank rows already on screen.
        _loadFailed = _rows.isEmpty;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore) return;
    setState(() => _loadingMore = true);
    await _fetchPage(_page + 1, _fetchEpoch);
  }

  Future<void> _refresh() async {
    ref.invalidate(_paymentSummaryProvider);
    await _reload();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final summaryAsync = ref.watch(_paymentSummaryProvider(_selectedPropertyId));
    final propertiesAsync = ref.watch(_propertiesForFilterProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: RefreshIndicator(
        onRefresh: _refresh,
        color: AppColors.primary,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            _ChromeHeader(l: l),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  summaryAsync.when(
                    loading: () => const _TopCellsShimmer(),
                    error: (_, _) => _TopCells(l: l, summary: const {}),
                    data: (summary) => _TopCells(l: l, summary: summary),
                  ),
                  const SizedBox(height: 16),
                  propertiesAsync.when(
                    loading: () => const SizedBox.shrink(),
                    error: (_, _) => const SizedBox.shrink(),
                    data: (properties) => _PropertyFilter(
                      l: l,
                      properties: properties,
                      selectedId: _selectedPropertyId,
                      onChanged: (v) {
                        setState(() => _selectedPropertyId = v);
                        _reload();
                      },
                    ),
                  ),
                  const SizedBox(height: 12),
                  _FilterChips(controller: _tabController, l: l),
                  const SizedBox(height: 6),
                  summaryAsync.when(
                    loading: () => const SizedBox.shrink(),
                    error: (_, _) => const SizedBox.shrink(),
                    data: (summary) => _StatusStrip(l: l, summary: summary),
                  ),
                  const SizedBox(height: 4),
                ],
              ),
            ),
            if (_loading)
              const Padding(
                padding: EdgeInsets.fromLTRB(16, 8, 16, 0),
                child: ListShimmer(itemCount: 4),
              )
            else if (_loadFailed)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 24, 16, 0),
                child: ErrorState(
                  message: l.failedToLoadPayments,
                  onRetry: _refresh,
                ),
              )
            else
              _buildList(l),
          ],
        ),
      ),
    );
  }

  /// With the Upcoming tab's ascending due-date sort, once the last loaded
  /// row is past the current month every remaining server page is too, so
  /// there is nothing left worth fetching for that tab.
  bool _lastRowBeyondCurrentMonth() {
    if (_rows.isEmpty) return false;
    final due = DateTime.tryParse(_rows.last['dueDate']?.toString() ?? '');
    if (due == null) return false;
    final now = DateTime.now();
    return DateTime(due.year, due.month).isAfter(DateTime(now.year, now.month));
  }

  /// Rows the active tab actually displays. Property, status and overdue
  /// filtering happen server-side (see _tabQuery); the only refinement the
  /// backend cannot express is the Upcoming tab's month scoping: with rows
  /// sorted dueDate ASC, keep pending dues of the current month that aren't
  /// already past due. Excluded rows are contiguous — overdue at the head,
  /// later months at the tail — so this never punches holes in loaded pages.
  List<dynamic> get _visibleRows {
    if (_tabController.index != 0) return _rows;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    return _rows.where((p) {
      final due = DateTime.tryParse(p['dueDate']?.toString() ?? '');
      if (due == null || due.year != now.year || due.month != now.month) {
        return false;
      }
      return !due.isBefore(today);
    }).toList();
  }

  bool get _hasMore =>
      _page + 1 < _totalPages &&
      !(_tabController.index == 0 && _lastRowBeyondCurrentMonth());

  Widget _buildList(_L l) {
    final visible = _visibleRows;
    final hasMore = _hasMore;

    if (visible.isEmpty && !hasMore) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 0),
        child: EmptyState(
          icon: Icons.payment_outlined,
          title: l.noPaymentsFound,
          // "Upcoming" is scoped to the current month while the stat tiles
          // above are not — say so, or an empty month reads as a bug.
          subtitle: _tabController.index == 0 ? l.noDuesThisMonth : null,
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 130),
      child: Column(
        children: [
          for (var index = 0; index < visible.length; index++)
            AnimatedListItem(
              index: index,
              child: _PaymentCard(
                payment: visible[index],
                l: l,
                onTap: () => _showPaymentActions(visible[index]),
              ),
            ),
          if (hasMore)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: _loadingMore
                  ? const SizedBox(
                      width: 28,
                      height: 28,
                      child: CircularProgressIndicator(
                        strokeWidth: 3,
                        color: AppColors.accentDark,
                      ),
                    )
                  : GoldButton.outlined(
                      // The Upcoming tab shows a refined subset of the
                      // server rows, so a server-derived remaining count
                      // would overstate it — use the plain label there.
                      label: _tabController.index == 0
                          ? l.showMorePlain
                          : l.showMore(_totalElements - _rows.length),
                      expanded: false,
                      onPressed: _loadMore,
                    ),
            ),
        ],
      ),
    );
  }

  void _showPaymentActions(Map<String, dynamic> payment) {
    final paymentId = payment['id'] ?? '';
    final l = _L(context.isAr);

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => _PaymentActionSheet(
        payment: payment,
        l: l,
        onCollect: () async {
          Navigator.pop(ctx);
          // Open the 4-step scan wizard with this payment pre-selected.
          // Mirrors the web flow: pick a payment row → Collect → scan
          // wizard handles capture, extraction, confirmation, deposit.
          await context.push('/scan?paymentId=$paymentId');
          // After the wizard closes, refresh the list so collected/deposited
          // status changes show up.
          await _refresh();
        },
        onDeposit: () async {
          Navigator.pop(ctx);
          await _performAction(
            () => ref.read(_paymentServiceProvider).depositPayment(paymentId),
            l.paymentDeposited,
          );
        },
        onClear: () async {
          Navigator.pop(ctx);
          await _performAction(
            () => ref.read(_paymentServiceProvider).clearPayment(paymentId),
            l.paymentCleared,
          );
        },
        onMarkFailed: () async {
          Navigator.pop(ctx);
          final installmentNumber = (payment['installmentNumber'] as int?) ?? 1;
          final amount = (payment['amount'] ?? 0) as num;
          final result = await showMarkChequeFailedDialog(
            context,
            paymentId: paymentId,
            installmentNumber: installmentNumber,
            amount: amount,
            paymentService: ref.read(_paymentServiceProvider),
          );
          if (result != null && mounted) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(SnackBar(content: Text(l.chequeMarkedFailed)));
            _refresh();
          }
        },
        onDownloadReceipt: () async {
          Navigator.pop(ctx);
          await _downloadReceipt(paymentId, l);
        },
      ),
    );
  }

  Future<void> _performAction(
    Future<dynamic> Function() action,
    String successMsg,
  ) async {
    try {
      await action();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(successMsg)));
        _refresh();
      }
    } catch (e) {
      if (mounted) {
        final l = _L(context.isAr);
        // Surface the backend's message (same pattern as the
        // mark-cheque-failed dialog) instead of a generic failure string.
        String message = l.actionFailed;
        if (e is DioException) {
          final data = e.response?.data;
          if (data is Map && data['message'] != null) {
            message = data['message'].toString();
          } else if (data is Map && data['error'] is String) {
            message = data['error'].toString();
          } else if (data is String && data.isNotEmpty) {
            message = data;
          }
        }
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(message)));
      }
    }
  }

  Future<void> _downloadReceipt(String paymentId, _L l) async {
    try {
      final bytes = await ref
          .read(_paymentServiceProvider)
          .downloadReceipt(paymentId);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/receipt_$paymentId.pdf');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToDownloadReceipt)));
      }
    }
  }
}

// ─── Chrome header ──────────────────────────────────────────────────────────

class _ChromeHeader extends StatelessWidget {
  final _L l;
  const _ChromeHeader({required this.l});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.ar ? l.overline : l.overline.toUpperCase(),
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accent.withValues(alpha: 0.7),
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 11,
                    letterSpacing: 2.0,
                    color: AppColors.accent.withValues(alpha: 0.7),
                  ),
          ),
          const SizedBox(height: 6),
          Text(
            l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 24,
                    fontWeight: FontWeight.w600,
                    color: Colors.white,
                  )
                : GoogleFonts.cinzel(
                    fontSize: 24,
                    fontWeight: FontWeight.w500,
                    color: Colors.white,
                  ),
          ),
        ],
      ),
    );
  }
}

// ─── Top cells: scan entry + "in hand" stat ────────────────────────────────

class _TopCells extends StatelessWidget {
  final _L l;
  final Map<String, dynamic> summary;
  const _TopCells({required this.l, required this.summary});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final inHandCount = ((summary['collectedCount'] ?? 0) as num).toInt();
    final inHandAmount = ((summary['collectedAmount'] ?? 0) as num).toDouble();

    // No stretch: inside the screen ListView the vertical constraint is
    // unbounded, and stretch would force infinite-height children.
    return Row(
      children: [
        Expanded(
          child: GestureDetector(
            onTap: () => context.push('/scan'),
            child: Container(
              height: 106,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: m.surfaceAlt,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(
                  color: AppColors.accent.withValues(alpha: 0.4),
                ),
              ),
              child: DottedBorderFallback(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      Icons.document_scanner_outlined,
                      color: AppColors.accentDark,
                      size: 22,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      l.scanCheque,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              letterSpacing: 1.2,
                              color: m.textPrimary,
                            ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Container(
            height: 106,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: m.surface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: m.border),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  l.ar ? l.inHand : l.inHand.toUpperCase(),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 11.5,
                          color: m.textMuted,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 10,
                          letterSpacing: 1.8,
                          color: m.textMuted,
                        ),
                ),
                const SizedBox(height: 8),
                Text(
                  '$inHandCount',
                  style: GoogleFonts.cinzel(
                    fontSize: 20,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  Formatters.currencyCompact(inHandAmount),
                  style:
                      (l.ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: 11,
                        color: AppColors.accentDark,
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

/// Lightweight dashed-border look without a new dependency: paints a dashed
/// rectangle behind [child] using a CustomPaint border.
class DottedBorderFallback extends StatelessWidget {
  final Widget child;
  const DottedBorderFallback({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return CustomPaint(painter: _DashedRectPainter(), child: child);
  }
}

class _DashedRectPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = AppColors.accent.withValues(alpha: 0.55)
      ..strokeWidth = 1.2
      ..style = PaintingStyle.stroke;
    final rrect = RRect.fromRectAndRadius(
      Offset.zero & size,
      const Radius.circular(10),
    );
    final path = Path()..addRRect(rrect);
    const dashWidth = 5.0;
    const dashSpace = 4.0;
    for (final metric in path.computeMetrics()) {
      var distance = 0.0;
      while (distance < metric.length) {
        final next = distance + dashWidth;
        canvas.drawPath(
          metric.extractPath(distance, next.clamp(0, metric.length)),
          paint,
        );
        distance = next + dashSpace;
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _TopCellsShimmer extends StatelessWidget {
  const _TopCellsShimmer();

  @override
  Widget build(BuildContext context) {
    return const Row(
      children: [
        Expanded(child: ShimmerLoading(height: 106, borderRadius: 14)),
        SizedBox(width: 10),
        Expanded(child: ShimmerLoading(height: 106, borderRadius: 14)),
      ],
    );
  }
}

// ─── Property filter ────────────────────────────────────────────────────────

class _PropertyFilter extends StatelessWidget {
  final _L l;
  final List<dynamic> properties;
  final String? selectedId;
  final ValueChanged<String?> onChanged;
  const _PropertyFilter({
    required this.l,
    required this.properties,
    required this.selectedId,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsetsDirectional.only(start: 14, end: 6),
      child: DropdownButtonHideUnderline(
        child: DropdownButtonFormField<String?>(
          initialValue: selectedId,
          isExpanded: true,
          decoration: const InputDecoration(
            filled: false,
            border: InputBorder.none,
            contentPadding: EdgeInsets.symmetric(vertical: 10),
          ),
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 13,
            color: m.textPrimary,
          ),
          dropdownColor: m.surface,
          icon: Icon(Icons.expand_more, color: m.textMuted),
          items: [
            DropdownMenuItem<String?>(
              value: null,
              child: Text(
                l.allProperties,
                style: const TextStyle(fontSize: 13),
              ),
            ),
            ...properties.map(
              (p) => DropdownMenuItem<String?>(
                value: p['id'],
                child: Text(
                  p['name'] ?? '',
                  style: const TextStyle(fontSize: 13),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ),
          ],
          onChanged: onChanged,
        ),
      ),
    );
  }
}

// ─── Filter chips (Upcoming / Overdue / Paid / All) ────────────────────────

class _FilterChips extends StatelessWidget {
  final TabController controller;
  final _L l;
  const _FilterChips({required this.controller, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final labels = [
      l.filterUpcoming,
      l.filterOverdue,
      l.filterPaid,
      l.filterAll,
    ];

    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (var i = 0; i < labels.length; i++)
                Padding(
                  padding: const EdgeInsetsDirectional.only(end: 8),
                  child: GestureDetector(
                    onTap: () => controller.animateTo(i),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 150),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 7,
                      ),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(999),
                        color: controller.index == i
                            ? (m.isDark ? AppColors.accent : AppColors.primary)
                            : Colors.transparent,
                        border: controller.index == i
                            ? null
                            : Border.all(color: m.borderStrong),
                      ),
                      child: Text(
                        labels[i],
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                color: controller.index == i
                                    ? (m.isDark
                                          ? AppColors.primary
                                          : AppColors.accent)
                                    : m.textSecondary,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 11.5,
                                letterSpacing: 1.4,
                                fontWeight: FontWeight.w500,
                                color: controller.index == i
                                    ? (m.isDark
                                          ? AppColors.primary
                                          : AppColors.accent)
                                    : m.textSecondary,
                              ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

// ─── Status strip ───────────────────────────────────────────────────────────

/// Semantic status cells. PENDING gets a distinct amber/clock treatment,
/// COLLECTED/DEPOSITED are bronze (still-in-clearing, not settled), CLEARED
/// is the only status that reads green, BOUNCED/OVERDUE stay danger red —
/// each with its own icon so the two bronze-adjacent states never look alike.
class _StatusStrip extends StatelessWidget {
  final _L l;
  final Map<String, dynamic> summary;
  const _StatusStrip({required this.l, required this.summary});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final items = [
      _StatusCellData(
        l.statusPending,
        (summary['pendingCount'] ?? 0) as num,
        Formatters.currencyCompact(
          ((summary['pendingAmount'] ?? 0) as num).toDouble(),
        ),
        m.warning,
        Icons.schedule,
      ),
      _StatusCellData(
        l.statusCollected,
        (summary['collectedCount'] ?? 0) as num,
        Formatters.currencyCompact(
          ((summary['collectedAmount'] ?? 0) as num).toDouble(),
        ),
        m.isDark ? AppColors.goldMid : AppColors.accentDark,
        Icons.move_to_inbox_outlined,
      ),
      _StatusCellData(
        l.statusDeposited,
        (summary['depositedCount'] ?? 0) as num,
        Formatters.currencyCompact(
          ((summary['depositedAmount'] ?? 0) as num).toDouble(),
        ),
        m.isDark ? AppColors.goldMid : AppColors.accentDark,
        Icons.account_balance_outlined,
      ),
      _StatusCellData(
        l.statusCleared,
        (summary['clearedCount'] ?? 0) as num,
        Formatters.currencyCompact(
          ((summary['clearedAmount'] ?? 0) as num).toDouble(),
        ),
        m.success,
        Icons.check_circle_outline,
      ),
      _StatusCellData(
        l.statusBounced,
        (summary['bouncedCount'] ?? 0) as num,
        Formatters.currencyCompact(
          ((summary['bouncedAmount'] ?? 0) as num).toDouble(),
        ),
        m.danger,
        Icons.cancel_outlined,
      ),
      _StatusCellData(
        l.statusOverdue,
        (summary['overdueCount'] ?? 0) as num,
        Formatters.currencyCompact(
          ((summary['overdueAmount'] ?? 0) as num).toDouble(),
        ),
        m.danger,
        Icons.warning_amber_rounded,
      ),
    ];

    return SizedBox(
      height: 84,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: items.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final item = items[index];
          return Container(
            width: 116,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: item.color.withValues(alpha: m.isDark ? 0.1 : 0.08),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: item.color.withValues(alpha: 0.24)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    Icon(item.icon, size: 14, color: item.color),
                    const SizedBox(width: 5),
                    Text(
                      '${item.count}',
                      style: GoogleFonts.josefinSans(
                        fontWeight: FontWeight.w700,
                        fontSize: 16,
                        color: item.color,
                      ),
                    ),
                  ],
                ),
                Text(
                  l.ar ? item.label : item.label.toUpperCase(),
                  style:
                      (l.ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: l.ar ? 11 : 9.5,
                        letterSpacing: l.ar ? 0 : 0.8,
                        color: item.color.withValues(alpha: 0.85),
                      ),
                  overflow: TextOverflow.ellipsis,
                ),
                Text(
                  item.amount,
                  style: GoogleFonts.josefinSans(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: item.color,
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _StatusCellData {
  final String label;
  final num count;
  final String amount;
  final Color color;
  final IconData icon;
  _StatusCellData(this.label, this.count, this.amount, this.color, this.icon);
}

// ─── Payment card ────────────────────────────────────────────────────────────

class _PaymentCard extends StatelessWidget {
  final Map<String, dynamic> payment;
  final _L l;
  final VoidCallback onTap;

  const _PaymentCard({
    required this.payment,
    required this.l,
    required this.onTap,
  });

  ({Color color, IconData icon}) _statusMeta(MiftahColors m, String status) {
    switch (status) {
      case 'PENDING':
      case 'ONLINE_PENDING':
        return (color: m.warning, icon: Icons.schedule);
      case 'COLLECTED':
      case 'DEPOSITED':
        return (
          color: m.isDark ? AppColors.goldMid : AppColors.accentDark,
          icon: status == 'COLLECTED'
              ? Icons.move_to_inbox_outlined
              : Icons.account_balance_outlined,
        );
      case 'CLEARED':
        return (color: m.success, icon: Icons.check_circle_outline);
      case 'BOUNCED':
        return (color: m.danger, icon: Icons.cancel_outlined);
      case 'OVERDUE':
        return (color: m.danger, icon: Icons.warning_amber_rounded);
      default:
        return (color: m.textMuted, icon: Icons.receipt_outlined);
    }
  }

  String _statusLabel(String status) => switch (status) {
    'PENDING' || 'ONLINE_PENDING' => l.statusPending,
    'COLLECTED' => l.statusCollected,
    'DEPOSITED' => l.statusDeposited,
    'CLEARED' => l.statusCleared,
    'BOUNCED' => l.statusBounced,
    'OVERDUE' => l.statusOverdue,
    'REPLACED' => l.statusReplaced,
    _ => status,
  };

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = payment['status'] ?? 'PENDING';
    final meta = _statusMeta(m, status);
    final amount = (payment['amount'] ?? 0).toDouble();

    // A rounded border can't mix colors per side, so the status accent is an
    // inner strip clipped to the card's radius instead of a left BorderSide.
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      // IntrinsicHeight bounds the stretch so the accent strip matches the
      // card height without inheriting the ListView's unbounded constraint.
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(width: 4, color: meta.color),
            Expanded(
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: onTap,
                  child: Padding(
                    padding: const EdgeInsetsDirectional.all(14),
                    child: Row(
                      children: [
                        Container(
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            color: meta.color.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Icon(meta.icon, color: meta.color, size: 20),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                payment['renterName'] ?? l.unknownRenter,
                                style:
                                    (l.ar
                                    ? GoogleFonts.notoNaskhArabic
                                    : GoogleFonts.josefinSans)(
                                      fontWeight: FontWeight.w600,
                                      fontSize: 14,
                                      color: m.textPrimary,
                                    ),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                l.propertyUnitLine(
                                  payment['propertyName']?.toString() ?? '-',
                                  (payment['unitIdentifier'] ??
                                          payment['unitNumber'] ??
                                          '-')
                                      .toString(),
                                ),
                                style:
                                    (l.ar
                                    ? GoogleFonts.notoNaskhArabic
                                    : GoogleFonts.josefinSans)(
                                      fontSize: 12,
                                      color: m.textSecondary,
                                    ),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                l.dueLine(
                                  Formatters.date(payment['dueDate'], ar: l.ar),
                                ),
                                style:
                                    (l.ar
                                    ? GoogleFonts.notoNaskhArabic
                                    : GoogleFonts.josefinSans)(
                                      fontSize: 11,
                                      color: m.textMuted,
                                    ),
                              ),
                            ],
                          ),
                        ),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            Text(
                              Formatters.currency(amount),
                              style: GoogleFonts.cinzel(
                                fontWeight: FontWeight.w600,
                                fontSize: 14,
                                color: m.textPrimary,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                                vertical: 3,
                              ),
                              decoration: BoxDecoration(
                                color: meta.color.withValues(alpha: 0.12),
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Text(
                                l.ar
                                    ? _statusLabel(status)
                                    : _statusLabel(status).toUpperCase(),
                                style: l.ar
                                    ? GoogleFonts.notoNaskhArabic(
                                        fontSize: 10.5,
                                        fontWeight: FontWeight.w600,
                                        color: meta.color,
                                      )
                                    : GoogleFonts.josefinSans(
                                        fontSize: 10,
                                        letterSpacing: 1.0,
                                        fontWeight: FontWeight.w600,
                                        color: meta.color,
                                      ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Action sheet ────────────────────────────────────────────────────────────

class _PaymentActionSheet extends StatelessWidget {
  final Map<String, dynamic> payment;
  final _L l;
  final VoidCallback onCollect;
  final VoidCallback onDeposit;
  final VoidCallback onClear;
  final VoidCallback onMarkFailed;
  final VoidCallback onDownloadReceipt;

  const _PaymentActionSheet({
    required this.payment,
    required this.l,
    required this.onCollect,
    required this.onDeposit,
    required this.onClear,
    required this.onMarkFailed,
    required this.onDownloadReceipt,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = payment['status'] ?? '';
    final amount = (payment['amount'] ?? 0).toDouble();

    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: m.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 20),

          // Payment summary
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: m.surfaceAlt,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        payment['renterName'] ?? l.unknownRenter,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.josefinSans)(
                              fontWeight: FontWeight.w600,
                              fontSize: 16,
                              color: m.textPrimary,
                            ),
                      ),
                    ),
                    Text(
                      l.ar
                          ? _statusText(status, l)
                          : _statusText(status, l).toUpperCase(),
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.josefinSans)(
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            color: m.textSecondary,
                          ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Text(
                      Formatters.currency(amount),
                      style: GoogleFonts.cinzel(
                        fontWeight: FontWeight.w600,
                        fontSize: 20,
                        color: m.textPrimary,
                      ),
                    ),
                    const Spacer(),
                    Text(
                      l.dueLine(Formatters.date(payment['dueDate'], ar: l.ar)),
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.josefinSans)(
                            fontSize: 12,
                            color: m.textSecondary,
                          ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // Actions
          if (status == 'PENDING' || status == 'OVERDUE')
            GoldButton(label: l.collectPayment, onPressed: onCollect),
          if (status == 'COLLECTED') ...[
            GoldButton(label: l.depositToBank, onPressed: onDeposit),
          ],
          if (status == 'DEPOSITED') ...[
            GoldButton(label: l.markAsCleared, onPressed: onClear),
            const SizedBox(height: 10),
            GoldButton.outlined(label: l.markFailed, onPressed: onMarkFailed),
          ],
          if (status == 'BOUNCED')
            GoldButton(label: l.replaceCheque, onPressed: onCollect),
          if (status == 'CLEARED')
            GoldButton.outlined(
              label: l.downloadReceipt,
              onPressed: onDownloadReceipt,
            ),
        ],
      ),
    );
  }

  String _statusText(String status, _L l) => switch (status) {
    'PENDING' || 'ONLINE_PENDING' => l.statusPending,
    'COLLECTED' => l.statusCollected,
    'DEPOSITED' => l.statusDeposited,
    'CLEARED' => l.statusCleared,
    'BOUNCED' => l.statusBounced,
    'OVERDUE' => l.statusOverdue,
    _ => status,
  };
}

// ─── Strings (EN/AR) ────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get overline => ar ? 'عمليات الشيكات' : 'CHEQUE OPERATIONS';
  String get title => ar ? 'المدفوعات' : 'Payments';

  String get scanCheque => ar ? 'مسح شيك' : 'Scan cheque';
  String get inHand => ar ? 'في الحوزة' : 'In hand';

  String get allProperties => ar ? 'كل العقارات' : 'All Properties';

  String get filterUpcoming => ar ? 'القادمة' : 'UPCOMING';
  String get filterOverdue => ar ? 'المتأخرة' : 'OVERDUE';
  String get filterPaid => ar ? 'مسدّدة' : 'PAID';
  String get filterAll => ar ? 'الكل' : 'ALL';

  String get statusPending => ar ? 'قيد الانتظار' : 'pending';
  String get statusCollected => ar ? 'تم التحصيل' : 'collected';
  String get statusDeposited => ar ? 'تم الإيداع' : 'deposited';
  String get statusCleared => ar ? 'تمت التسوية' : 'cleared';
  String get statusBounced => ar ? 'مرتجع' : 'bounced';
  String get statusOverdue => ar ? 'متأخر' : 'overdue';
  String get statusReplaced => ar ? 'مستبدل' : 'replaced';

  String get failedToLoadPayments =>
      ar ? 'تعذّر تحميل المدفوعات' : 'Failed to load payments';
  String get noPaymentsFound => ar ? 'لا توجد مدفوعات' : 'No payments found';
  String get noDuesThisMonth =>
      ar ? 'لا مستحقات هذا الشهر' : 'Nothing due this month';
  String get unknownRenter => ar ? 'مستأجر غير معروف' : 'Unknown';

  String propertyUnitLine(String property, String unit) =>
      ar ? '$property — وحدة $unit' : '$property | Unit $unit';
  String dueLine(String date) => ar ? 'الاستحقاق: $date' : 'Due: $date';

  String showMore(int remaining) => ar
      ? 'عرض المزيد ($remaining متبقية)'
      : 'Show More ($remaining remaining)';
  String get showMorePlain => ar ? 'عرض المزيد' : 'Show More';

  String get actionFailed => ar ? 'فشلت العملية' : 'Action failed';
  String get paymentDeposited => ar ? 'تم إيداع الدفعة' : 'Payment deposited';
  String get paymentCleared => ar ? 'تمت تسوية الدفعة' : 'Payment cleared';
  String get chequeMarkedFailed =>
      ar ? 'تم تحديد الشيك كمرتجع' : 'Cheque marked as failed';
  String get failedToDownloadReceipt =>
      ar ? 'تعذّر تنزيل الإيصال' : 'Failed to download receipt';

  String get collectPayment => ar ? 'تحصيل الدفعة' : 'Collect Payment';
  String get depositToBank => ar ? 'إيداع في البنك' : 'Deposit to Bank';
  String get markAsCleared => ar ? 'تحديد كمسددة' : 'Mark as Cleared';
  String get markFailed => ar ? 'تحديد كمرتجع' : 'Mark Failed';
  String get replaceCheque => ar ? 'استبدال الشيك' : 'Replace Cheque';
  String get downloadReceipt => ar ? 'تنزيل الإيصال' : 'Download Receipt';
}
