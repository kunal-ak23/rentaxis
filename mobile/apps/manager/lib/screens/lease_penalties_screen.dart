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

  Future<void> _recalculatePenalties() async {
    final l = _l;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.recalculateTitle, style: _display(l.ar, size: 17)),
        content: Text(
          l.recalculateBody,
          style: _body(l.ar, color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.primary),
            child: Text(
              l.recalculate,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
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
          .recalculatePenalties(widget.leaseId);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.recalculated)));
        _loadPenalties();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToRecalculate)));
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
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
              textAlign: l.ar ? TextAlign.right : TextAlign.left,
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
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
            child: Text(
              l.waive,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
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
          .waivePenalty(
            penaltyId,
            reason: reasonCtrl.text.isNotEmpty ? reasonCtrl.text : null,
          );
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
            onPressed: _recalculatePenalties,
            tooltip: l.recalculate,
          ),
        ],
      ),
      body: LoadingOverlay(isLoading: _isActioning, child: _buildBody(m, l)),
    );
  }

  Widget _buildBody(MiftahColors m, _L l) {
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
  final MiftahColors m;
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
    final status = (penalty['status'] ?? 'ACTIVE').toString().toUpperCase();
    final isActive = status == 'ACTIVE';
    final accent = isActive ? m.danger : m.textMuted;

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: isActive ? m.danger.withValues(alpha: 0.25) : m.border,
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
                            l.penaltyTypeLabel(
                              (penalty['penaltyType'] ?? penalty['type'])
                                  ?.toString(),
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
                            (penalty['amount'] ?? 0).toDouble(),
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
                    if (penalty['paymentNumber'] != null ||
                        penalty['dueDate'] != null) ...[
                      const SizedBox(height: 8),
                      Text(
                        [
                          if (penalty['paymentNumber'] != null)
                            l.paymentNumber(penalty['paymentNumber']),
                          if (penalty['dueDate'] != null)
                            l.dueOn(
                              Formatters.date(penalty['dueDate'], ar: l.ar),
                            ),
                        ].join(' · '),
                        style: _body(l.ar, size: 12, color: m.textSecondary),
                      ),
                    ],
                    const SizedBox(height: 4),
                    Text(
                      Formatters.date(penalty['createdAt'], ar: l.ar),
                      style: _body(l.ar, size: 11, color: m.textMuted),
                    ),
                    if (isActive) ...[
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

  String get recalculate => ar ? 'إعادة الحساب' : 'Recalculate';
  String get recalculateTitle =>
      ar ? 'إعادة حساب الغرامات' : 'Recalculate Penalties';
  String get recalculateBody => ar
      ? 'سيعيد هذا حساب جميع الغرامات لهذا العقد بناءً على حالات الدفعات الحالية. هل تريد المتابعة؟'
      : 'This will recalculate all penalties for this lease based on current payment statuses. Continue?';
  String get recalculated =>
      ar ? 'تمت إعادة حساب الغرامات' : 'Penalties recalculated';
  String get failedToRecalculate =>
      ar ? 'فشلت إعادة حساب الغرامات' : 'Failed to recalculate penalties';

  String get waive => ar ? 'إعفاء' : 'Waive';
  String get waiveTitle => ar ? 'إعفاء الغرامة' : 'Waive Penalty';
  String get waiveConfirm => ar
      ? 'هل أنت متأكد من إعفاء هذه الغرامة؟'
      : 'Are you sure you want to waive this penalty?';
  String get waiveReasonHint => ar ? 'السبب (اختياري)' : 'Reason (optional)';
  String get penaltyWaived => ar ? 'تم إعفاء الغرامة' : 'Penalty waived';
  String get failedToWaive =>
      ar ? 'فشل إعفاء الغرامة' : 'Failed to waive penalty';

  String paymentNumber(dynamic n) => ar ? 'الدفعة رقم $n' : 'Payment #$n';
  String dueOn(String date) => ar ? 'الاستحقاق: $date' : 'Due: $date';

  String statusLabel(String status) {
    switch (status) {
      case 'ACTIVE':
        return ar ? 'مفتوحة' : 'ACTIVE';
      case 'WAIVED':
        return ar ? 'معفوة' : 'WAIVED';
      case 'CLEARED':
      case 'PAID':
        return ar ? 'مسددة' : 'CLEARED';
      default:
        return status;
    }
  }

  String penaltyTypeLabel(String? type) {
    switch (type) {
      case 'BOUNCE':
      case 'BOUNCED_CHEQUE':
        return ar ? 'ارتداد الشيك' : 'Bounced Cheque';
      case 'LATE_PAYMENT':
        return ar ? 'تأخر في الدفع' : 'Late Payment';
      case 'SIGNATURE_MISMATCH':
        return ar ? 'عدم تطابق التوقيع' : 'Signature Mismatch';
      case 'ACCOUNT_CLOSED':
        return ar ? 'الحساب مغلق' : 'Account Closed';
      default:
        return type ?? (ar ? 'غرامة' : 'Penalty');
    }
  }
}
