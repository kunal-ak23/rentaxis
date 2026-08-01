import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class WalkInStatusScreen extends ConsumerStatefulWidget {
  const WalkInStatusScreen({super.key, required this.passId});
  final String passId;

  @override
  ConsumerState<WalkInStatusScreen> createState() => _WalkInStatusScreenState();
}

class _WalkInStatusScreenState extends ConsumerState<WalkInStatusScreen> {
  Map<String, dynamic>? _pass;
  Timer? _timer;
  bool _loading = true;
  bool _admitting = false;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (_pass?['status'] == 'PENDING_APPROVAL') {
        _refresh(silent: true);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    try {
      final pass = await ref
          .read(gatePassServiceProvider)
          .walkInStatus(widget.passId);
      if (mounted) {
        setState(() {
          _pass = pass;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted && !silent) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _admit() async {
    if (_admitting) return;
    final l = _L(context.isAr);
    setState(() => _admitting = true);
    try {
      final pass = await ref
          .read(gatePassServiceProvider)
          .admitWalkIn(widget.passId);
      if (!mounted) return;
      setState(() => _pass = pass);
      final m = context.miftah;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(l.entryRecorded), backgroundColor: m.success),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.admitFailed)));
      }
    } finally {
      if (mounted) setState(() => _admitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    final status = _pass?['status']?.toString();
    final pending = status == 'PENDING_APPROVAL';
    final active = status == 'ACTIVE';
    final admitted = status == 'USED';

    final Color statusColor = pending
        ? m.warning
        : (active || admitted)
        ? m.success
        : m.danger;
    final Color statusBg = pending
        ? m.warningBg
        : (active || admitted)
        ? m.successBg
        : m.dangerBg;

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: m.surface,
        foregroundColor: m.textPrimary,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        title: Text(
          l.visitorEntry,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: m.textPrimary,
                )
              : GoogleFonts.cinzel(
                  fontSize: 17,
                  fontWeight: FontWeight.w500,
                  letterSpacing: 1.2,
                  color: m.textPrimary,
                ),
        ),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => context.go('/'),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _pass == null
          ? Center(
              child: GoldButton(
                label: l.tryAgain,
                onPressed: _refresh,
                expanded: false,
              ),
            )
          : Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Spacer(),
                  Center(
                    child: Container(
                      width: 96,
                      height: 96,
                      decoration: BoxDecoration(
                        color: statusBg,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        pending
                            ? Icons.hourglass_top
                            : active
                            ? Icons.verified
                            : admitted
                            ? Icons.login
                            : Icons.cancel,
                        size: 48,
                        color: statusColor,
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Center(
                    child: _StatusPill(
                      label: l.statusLabel(status),
                      color: statusColor,
                      ar: l.ar,
                    ),
                  ),
                  const SizedBox(height: 14),
                  Text(
                    _pass!['guestName']?.toString() ?? l.visitorFallback,
                    textAlign: TextAlign.center,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 22,
                            fontWeight: FontWeight.w700,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 22,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    l.unitLine(_pass!['unitNumber']?.toString() ?? ''),
                    textAlign: TextAlign.center,
                    style: bodyFont(fontSize: 16, color: m.textSecondary),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    pending
                        ? l.waitingForResident
                        : active
                        ? l.approvedTapToAdmit
                        : admitted
                        ? l.entryHasBeenRecorded
                        : l.rejectedOrExpired,
                    textAlign: TextAlign.center,
                    style: bodyFont(
                      fontSize: 14,
                      height: 1.5,
                      color: m.textSecondary,
                    ),
                  ),
                  const Spacer(),
                  if (active)
                    GoldButton(
                      label: _admitting ? l.recording : l.admitVisitor,
                      onPressed: _admitting ? null : _admit,
                      icon: const Icon(Icons.login),
                    ),
                  if (pending)
                    GoldButton.outlined(
                      label: l.checkNow,
                      onPressed: _refresh,
                      icon: const Icon(Icons.refresh),
                    ),
                  if (!pending && !active)
                    GoldButton(label: l.done, onPressed: () => context.go('/')),
                ],
              ),
            ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({
    required this.label,
    required this.color,
    required this.ar,
  });

  final String label;
  final Color color;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
          fontSize: 11.5,
          fontWeight: FontWeight.w600,
          letterSpacing: ar ? 0 : 1.4,
          color: color,
        ),
      ),
    );
  }
}

/// Walk-in status screen strings (EN/AR). Lightweight per-screen pattern —
/// see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get visitorEntry => ar ? 'دخول الزائر' : 'Visitor entry';
  String get tryAgain => ar ? 'إعادة المحاولة' : 'Try again';
  String get visitorFallback => ar ? 'زائر' : 'Visitor';
  String unitLine(String unit) => unit.isEmpty
      ? (ar ? 'الوحدة' : 'Unit')
      : (ar ? 'الوحدة $unit' : 'Unit $unit');
  String get waitingForResident => ar
      ? 'بانتظار موافقة المقيم. تتحدّث هذه الشاشة تلقائيًا.'
      : 'Waiting for the resident. This screen updates automatically.';
  String get approvedTapToAdmit => ar
      ? 'تمت الموافقة. اضغط على السماح بالدخول لتسجيل الدخول.'
      : 'Approved. Tap Admit visitor to record entry.';
  String get entryHasBeenRecorded =>
      ar ? 'تم تسجيل الدخول.' : 'Entry has been recorded.';
  String get rejectedOrExpired => ar
      ? 'تم رفض الطلب أو انتهت صلاحيته.'
      : 'The request was rejected or expired.';
  String get recording => ar ? 'جارٍ التسجيل…' : 'Recording…';
  String get admitVisitor => ar ? 'السماح بدخول الزائر' : 'Admit visitor';
  String get checkNow => ar ? 'التحقق الآن' : 'Check now';
  String get done => ar ? 'تم' : 'Done';
  String get entryRecorded => ar
      ? 'تم تسجيل الدخول. يمكن للزائر الدخول.'
      : 'Entry recorded. The visitor may enter.';
  String get admitFailed => ar
      ? 'تعذّر السماح بدخول الزائر. حدّث الصفحة وحاول مرة أخرى.'
      : 'Could not admit the visitor. Refresh and try again.';

  String statusLabel(String? status) {
    switch (status) {
      case 'PENDING_APPROVAL':
        return ar ? 'بانتظار الموافقة' : 'PENDING APPROVAL';
      case 'ACTIVE':
        return ar ? 'تمت الموافقة' : 'APPROVED';
      case 'USED':
        return ar ? 'تم الدخول' : 'ADMITTED';
      default:
        return ar ? 'مرفوض / منتهي' : 'REJECTED / EXPIRED';
    }
  }
}
