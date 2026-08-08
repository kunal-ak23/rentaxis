import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../gatepass/pass_display.dart';
import '../providers/gate_pass_provider.dart';

/// `/approvals` as a route of its own. The same queue is the home screen's third
/// tab; both render [ApprovalsView], which is where the behaviour lives.
class ApprovalsScreen extends StatelessWidget {
  const ApprovalsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: AppColors.primary,
        elevation: 0,
        scrolledUnderElevation: 0.5,
        shape: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
        iconTheme: const IconThemeData(color: AppColors.accent),
        title: Text(
          l.approvals,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                  color: AppColors.gold400,
                )
              : GoogleFonts.cinzel(fontSize: 17, color: AppColors.gold400),
        ),
      ),
      body: const SafeArea(child: ApprovalsView()),
    );
  }
}

/// Recurring passes waiting on a decision at the guard's assigned properties.
class ApprovalsView extends ConsumerWidget {
  const ApprovalsView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final approvals = ref.watch(approvalsProvider);
    final l = _L(context.isAr);

    return RefreshIndicator(
      onRefresh: () => ref.refresh(approvalsProvider.future),
      child: approvals.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: l.loadError,
            onRetry: () => ref.invalidate(approvalsProvider),
          ),
        ),
        data: (passes) {
          if (passes.isEmpty) {
            return _Scrollable(
              child: EmptyState(
                icon: Icons.inbox_outlined,
                title: l.emptyTitle,
                subtitle: l.emptySubtitle,
              ),
            );
          }

          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
            itemCount: passes.length,
            itemBuilder: (context, index) => AnimatedListItem(
              index: index,
              child: _ApprovalCard(pass: passes[index]),
            ),
          );
        },
      ),
    );
  }
}

/// One pending pass, with its decision buttons.
///
/// Stateful for one reason: [_deciding]. A decision is a network call, and two
/// taps on Approve would post two decisions — the second of which the backend
/// answers 400 for ("not pending approval"), turning a double-tap into an error
/// on a pass that was in fact approved.
class _ApprovalCard extends ConsumerStatefulWidget {
  const _ApprovalCard({required this.pass});

  final Map<String, dynamic> pass;

  @override
  ConsumerState<_ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends ConsumerState<_ApprovalCard> {
  bool _deciding = false;

  /// Refresh-on-success rather than an optimistic removal.
  ///
  /// The optimistic version has to guess at a failure: it would drop the card,
  /// then put it back on an error — and a card that reappears silently reads as
  /// a rendering glitch, not as "your approval did not happen". Since a decision
  /// is one tap on a short list, waiting out one round trip costs the guard
  /// nothing and keeps the screen unable to claim a decision the server
  /// refused.
  Future<void> _decide(bool approved) async {
    final id = passString(widget.pass, 'id');
    if (id == null || _deciding) return;

    final l = _L(context.isAr);
    setState(() => _deciding = true);
    try {
      await ref.read(gatePassServiceProvider).decide(id, approved);
      if (!mounted) return;
      // Only after the server has agreed.
      ref.invalidate(approvalsProvider);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(approved ? l.approvedNotice : l.rejectedNotice),
          backgroundColor: approved ? AppColors.success : AppColors.textMuted,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (_) {
      if (!mounted) return;
      // The card stays exactly as it was, so the queue still shows the pass as
      // undecided — which it is.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(approved ? l.approveFailed : l.rejectFailed),
          backgroundColor: AppColors.danger,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } finally {
      if (mounted) setState(() => _deciding = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final pass = widget.pass;
    // Keyed per pass so a tap in a test — and a hit test in a rebuilt list —
    // names one card's button rather than "whichever Approve is on screen".
    final id = passString(pass, 'id') ?? '';
    final name = passString(pass, 'guestName') ?? l.guestFallback;
    final unit = passString(pass, 'unitNumber');
    final purpose = passString(pass, 'purpose');
    final vehicle = passString(pass, 'vehicleNumber');
    final window = formatWindow(
      passInstant(pass, 'validFrom'),
      passInstant(pass, 'validTo'),
    );

    // A rounded border can't mix colors per side, so the recurring-pass accent
    // is an inner strip clipped to the card's radius instead of a left side.
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        children: [
          PositionedDirectional(
            start: 0,
            top: 0,
            bottom: 0,
            child: Container(width: 3, color: AppColors.accentDark),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        name,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 17,
                                fontWeight: FontWeight.w600,
                                color: m.textPrimary,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 18,
                                fontWeight: FontWeight.w700,
                                color: m.textPrimary,
                              ),
                      ),
                    ),
                    _StatusPill(
                      label: l.recurring,
                      color: AppColors.info,
                      ar: l.ar,
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                if (unit != null)
                  _DetailRow(
                    icon: Icons.home_outlined,
                    value: l.unitLabel(unit),
                  ),
                _DetailRow(icon: Icons.schedule, value: window),
                if (purpose != null)
                  _DetailRow(icon: Icons.notes_outlined, value: purpose),
                if (vehicle != null)
                  _DetailRow(icon: Icons.directions_car, value: vehicle),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Expanded(
                      child: GoldButton.outlined(
                        key: Key('reject-$id'),
                        label: l.reject,
                        onPressed: _deciding ? null : () => _decide(false),
                        height: 46,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: AnimatedSwitcher(
                        duration: const Duration(milliseconds: 200),
                        child: _deciding
                            ? const SizedBox(
                                key: ValueKey('deciding'),
                                height: 46,
                                child: Center(
                                  child: SizedBox(
                                    height: 18,
                                    width: 18,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: AppColors.accent,
                                    ),
                                  ),
                                ),
                              )
                            : GoldButton(
                                key: Key('approve-$id'),
                                label: l.approve,
                                onPressed: () => _decide(true),
                                height: 46,
                              ),
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
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.2,
                color: color,
              ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.icon, required this.value});

  final IconData icon;
  final String value;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 15, color: m.textMuted),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    fontSize: 14,
                    color: m.textSecondary,
                  ),
            ),
          ),
        ],
      ),
    );
  }
}

/// See the note on the home screen's copy of this: a [RefreshIndicator] over a
/// non-scrolling child cannot be pulled.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get approvals => ar ? 'الموافقات' : 'Approvals';
  String get recurring => ar ? 'متكرر' : 'RECURRING';
  String get reject => ar ? 'رفض' : 'Reject';
  String get approve => ar ? 'موافقة' : 'Approve';
  String get guestFallback => ar ? 'زائر' : 'Guest';
  String get loadError =>
      ar ? 'تعذّر تحميل الموافقات.' : 'Could not load approvals.';
  String get emptyTitle =>
      ar ? 'لا شيء بانتظار الموافقة' : 'Nothing waiting for approval';
  String get emptySubtitle => ar
      ? 'تظهر هنا التصاريح المتكررة الخاصة ببوابتك.\n\n'
            'لا تظهر أي عناصر؟ اطلب من مديرك التأكد من تعيينك على عقار.'
      : 'Recurring passes raised for your gate appear here.\n\n'
            'Never see any? Ask your manager to check that you are '
            'assigned to a property.';
  String get approvedNotice =>
      ar ? 'تمت الموافقة على التصريح' : 'Pass approved';
  String get rejectedNotice => ar ? 'تم رفض التصريح' : 'Pass rejected';
  String get approveFailed => ar
      ? 'تعذّرت الموافقة على التصريح. ما زال معلّقًا — حاول مرة أخرى.'
      : 'Could not approve the pass. It is still pending — try again.';
  String get rejectFailed => ar
      ? 'تعذّر رفض التصريح. ما زال معلّقًا — حاول مرة أخرى.'
      : 'Could not reject the pass. It is still pending — try again.';

  String unitLabel(String unit) => ar ? 'وحدة $unit' : 'Unit $unit';
}
