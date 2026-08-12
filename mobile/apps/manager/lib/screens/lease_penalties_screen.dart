import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _penaltyServiceProvider = Provider<PenaltyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PenaltyService(client.dio);
});

/// Text style helper — Arabic uses Noto Naskh instead of Cinzel/Josefin
/// Sans, and never carries the EN tracked-uppercase letterSpacing (breaks
/// glyph joining). See arabic-brief.
TextStyle _display(
  bool ar, {
  double size = 16,
  FontWeight weight = FontWeight.w600,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.cinzel(fontSize: size, fontWeight: weight, color: color);

TextStyle _body(
  bool ar, {
  double size = 13,
  FontWeight weight = FontWeight.w400,
  Color? color,
  double letterSpacing = 0,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.josefinSans(
        fontSize: size,
        fontWeight: weight,
        color: color,
        letterSpacing: letterSpacing,
      );

class LeasePenaltiesScreen extends ConsumerStatefulWidget {
  final String leaseId;
  const LeasePenaltiesScreen({super.key, required this.leaseId});

  @override
  ConsumerState<LeasePenaltiesScreen> createState() =>
      _LeasePenaltiesScreenState();
}

class _LeasePenaltiesScreenState extends ConsumerState<LeasePenaltiesScreen> {
  List<dynamic> _penalties = [];
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  _L get _l => _L(context.isAr);

  @override
  void initState() {
    super.initState();
    _loadPenalties();
  }

  Future<void> _loadPenalties() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final service = ref.read(_penaltyServiceProvider);
      final penalties = await service.getPenalties(widget.leaseId);
      if (!mounted) return;
      setState(() {
        _penalties = penalties;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = _l.failedToLoad;
        _isLoading = false;
      });
    }
  }

  Future<void> _waivePenalty(String penaltyId) async {
    final l = _l;
    final reasonCtrl = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.waiveTitle, style: _display(l.ar, size: 17)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              l.waiveConfirm,
              style: _body(l.ar, color: AppColors.textSecondary),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: reasonCtrl,
              maxLines: 2,
              textAlign: TextAlign.start,
              style: _body(l.ar),
              decoration: InputDecoration(
                hintText: l.waiveReasonHint,
                hintStyle: _body(l.ar, color: AppColors.textMuted),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          // Backend requires a non-blank reason (@NotBlank), so the confirm
          // button stays disabled until one is entered.
          ValueListenableBuilder<TextEditingValue>(
            valueListenable: reasonCtrl,
            builder: (ctx2, value, _) => ElevatedButton(
              onPressed: value.text.trim().isEmpty
                  ? null
                  : () => Navigator.pop(ctx, true),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.danger,
              ),
              child: Text(
                l.waive,
                style: _body(
                  l.ar,
                  weight: FontWeight.w600,
                  color: Colors.white,
                ),
              ),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _isActioning = true);
    try {
      await ref
          .read(_penaltyServiceProvider)
          .waivePenalty(penaltyId, reason: reasonCtrl.text.trim());
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.penaltyWaived)));
        _loadPenalties();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToWaive)));
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _l;
    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: AppColors.primary,
        iconTheme: const IconThemeData(color: Colors.white70),
        title: Text(
          l.title,
          style: _display(l.ar, size: 17, color: AppColors.gold400),
        ),
        actions: [
          IconButton(
            icon: Icon(
              Icons.refresh,
              color: AppColors.accent.withValues(alpha: 0.85),
            ),
            onPressed: _loadPenalties,
            tooltip: l.refresh,
          ),
        ],
      ),
      body: LoadingOverlay(isLoading: _isActioning, child: _buildBody(m, l)),
    );
  }

  Widget _buildBody(LegacyMiftahColors m, _L l) {
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(color: AppColors.accent),
      );
    }

    if (_error != null) {
      return ErrorState(message: _error!, onRetry: _loadPenalties);
    }

    if (_penalties.isEmpty) {
      return EmptyState(
        icon: Icons.gavel,
        title: l.noPenalties,
        subtitle: l.noPenaltiesSubtitle,
      );
    }

    return RefreshIndicator(
      onRefresh: _loadPenalties,
      color: AppColors.accent,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.fromLTRB(16, 16, 16, AppInsets.bottomNav(context)),
        itemCount: _penalties.length,
        itemBuilder: (context, index) {
          final penalty = _penalties[index];
          return _PenaltyCard(
            penalty: penalty,
            m: m,
            l: l,
            onWaive: () => _waivePenalty(penalty['id']),
          );
        },
      ),
    );
  }
}

class _PenaltyCard extends StatelessWidget {
  final Map<String, dynamic> penalty;
  final LegacyMiftahColors m;
  final _L l;
  final VoidCallback onWaive;

  const _PenaltyCard({
    required this.penalty,
    required this.m,
    required this.l,
    required this.onWaive,
  });

  @override
  Widget build(BuildContext context) {
    // PenaltyDTO.status is derived server-side: OPEN | CLEARED | WAIVED.
    final status = (penalty['status'] ?? 'OPEN').toString().toUpperCase();
    final isOpen = status == 'OPEN';
    final accent = isOpen ? m.danger : m.textMuted;
    final daysOverdue = (penalty['daysOverdue'] as num?) ?? 0;

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: isOpen ? m.danger.withValues(alpha: 0.25) : m.border,
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(width: 4, color: accent),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            l.reasonLabel(
                              penalty['failureReason']?.toString(),
                            ),
                            style: _body(
                              l.ar,
                              size: 14,
                              weight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                          ),
                        ),
                        Text(
                          Formatters.currency(
                            ((penalty['currentTotal'] ??
                                        penalty['penaltyAmount'] ??
                                        0)
                                    as num)
                                .toDouble(),
                          ),
                          style: GoogleFonts.cinzel(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            color: accent,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    _StatusPill(
                      label: l.statusLabel(status),
                      color: accent,
                      ar: l.ar,
                    ),
                    if (daysOverdue > 0) ...[
                      const SizedBox(height: 8),
                      Text(
                        l.daysOverdue(daysOverdue.toInt()),
                        style: _body(l.ar, size: 12, color: m.textSecondary),
                      ),
                    ],
                    const SizedBox(height: 4),
                    Text(
                      Formatters.date(penalty['createdAt'], ar: l.ar),
                      style: _body(l.ar, size: 11, color: m.textMuted),
                    ),
                    if (isOpen) ...[
                      const SizedBox(height: 8),
                      GestureDetector(
                        onTap: onWaive,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.cancel_outlined,
                              size: 15,
                              color: m.danger,
                            ),
                            const SizedBox(width: 5),
                            Text(
                              l.waive,
                              style: _body(
                                l.ar,
                                size: 12.5,
                                weight: FontWeight.w600,
                                color: m.danger,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
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
        color: color.withValues(alpha: 0.1),
        border: Border.all(color: color.withValues(alpha: 0.3)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.josefinSans(
                fontSize: 9,
                letterSpacing: 1.2,
                fontWeight: FontWeight.w600,
                color: color,
              ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Reuses RentAxis renter-app penalty terminology
/// (see renter/lib/screens/penalties_screen.dart and web/messages/ar.json
/// LeasePenalties/RenterPenalties keys) for consistency across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'الغرامات' : 'Penalties';
  String get failedToLoad =>
      ar ? 'تعذر تحميل الغرامات' : 'Failed to load penalties';
  String get noPenalties => ar ? 'لا توجد غرامات' : 'No penalties';
  String get noPenaltiesSubtitle =>
      ar ? 'لا توجد غرامات لهذا العقد' : 'No penalties for this lease';

  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get refresh => ar ? 'تحديث' : 'Refresh';

  String get waive => ar ? 'إعفاء' : 'Waive';
  String get waiveTitle => ar ? 'إعفاء الغرامة' : 'Waive Penalty';
  String get waiveConfirm => ar
      ? 'هل أنت متأكد من إعفاء هذه الغرامة؟'
      : 'Are you sure you want to waive this penalty?';
  String get waiveReasonHint => ar ? 'السبب (مطلوب)' : 'Reason (required)';
  String get penaltyWaived => ar ? 'تم إعفاء الغرامة' : 'Penalty waived';
  String get failedToWaive =>
      ar ? 'فشل إعفاء الغرامة' : 'Failed to waive penalty';

  String daysOverdue(int n) {
    if (!ar) return '$n days overdue';
    if (n == 1) return 'متأخر يوماً واحداً';
    if (n == 2) return 'متأخر يومين';
    if (n >= 3 && n <= 10) return 'متأخر $n أيام';
    return 'متأخر $n يوماً';
  }

  String statusLabel(String status) {
    switch (status) {
      case 'WAIVED':
        return ar ? 'معفوة' : 'WAIVED';
      case 'CLEARED':
        return ar ? 'مسددة' : 'CLEARED';
      case 'OPEN':
        return ar ? 'مفتوحة' : 'OPEN';
      default:
        return status;
    }
  }

  String reasonLabel(String? reason) {
    switch (reason) {
      case 'BOUNCE':
        return ar ? 'ارتداد الشيك' : 'Bounced Cheque';
      case 'SIGNATURE_MISMATCH':
        return ar ? 'عدم تطابق التوقيع' : 'Signature Mismatch';
      case 'ACCOUNT_CLOSED':
        return ar ? 'الحساب مغلق' : 'Account Closed';
      default:
        return reason ?? (ar ? 'غرامة' : 'Penalty');
    }
  }
}
