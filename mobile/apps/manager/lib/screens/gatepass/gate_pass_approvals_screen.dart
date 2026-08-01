import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/pass_display.dart';
import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'موافقات تصاريح الدخول' : 'Gate Pass Approvals';
  String get loadFailed =>
      ar ? 'فشل تحميل الموافقات' : 'Failed to load approvals';
  String get nothingWaiting =>
      ar ? 'لا يوجد ما ينتظر الموافقة' : 'Nothing waiting for approval';
  String get nothingWaitingSubtitle => ar
      ? 'تظهر هنا تصاريح الدخول المتكررة التي يرفعها المستأجرون حتى يوافق عليها أو يرفضها أحدهم.'
      : 'Recurring passes raised by renters appear here until someone '
            'approves or rejects them.';
  String get guest => ar ? 'ضيف' : 'Guest';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get approve => ar ? 'اعتماد' : 'Approve';
  String get reject => ar ? 'رفض' : 'Reject';
  String get passApproved => ar ? 'تم اعتماد التصريح' : 'Pass approved';
  String get passRejected => ar ? 'تم رفض التصريح' : 'Pass rejected';
  String get alreadyDecided => ar
      ? 'تم اعتماد قرار بشأن هذا التصريح مسبقًا من قِبل شخص آخر. جارٍ تحديث القائمة.'
      : 'Someone already decided this pass. Refreshing the queue.';
  String get couldNotApprove => ar
      ? 'تعذّر اعتماد التصريح. ما زال معلّقًا — حاول مرة أخرى.'
      : 'Could not approve the pass. It is still pending — try again.';
  String get couldNotReject => ar
      ? 'تعذّر رفض التصريح. ما زال معلّقًا — حاول مرة أخرى.'
      : 'Could not reject the pass. It is still pending — try again.';

  String passType(String value) => switch (value) {
    'SINGLE_USE' => ar ? 'استخدام واحد' : 'Single use',
    'RECURRING' => ar ? 'متكرر' : 'Recurring',
    _ => value.replaceAll('_', ' '),
  };
}

/// The tenant's gate passes awaiting a decision.
///
/// Tenant-wide, unlike the guard app's copy of this queue — same endpoint, scope
/// chosen server-side by role. So a card here may be for any property, which is
/// why every card names its property and the guard app's does not.
class GatePassApprovalsScreen extends ConsumerWidget {
  const GatePassApprovalsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final approvals = ref.watch(approvalsProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
      ),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(approvalsProvider.future),
        color: AppColors.accent,
        child: approvals.when(
          loading: () => const Center(
            child: CircularProgressIndicator(color: AppColors.accent),
          ),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: l.loadFailed,
              onRetry: () => ref.invalidate(approvalsProvider),
            ),
          ),
          data: (passes) {
            if (passes.isEmpty) {
              return _Scrollable(
                child: EmptyState(
                  icon: Icons.inbox_outlined,
                  title: l.nothingWaiting,
                  subtitle: l.nothingWaitingSubtitle,
                ),
              );
            }

            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 80),
              itemCount: passes.length,
              itemBuilder: (context, index) => AnimatedListItem(
                index: index,
                child: _ApprovalCard(pass: passes[index], l: l),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// One pending pass, with its decision buttons.
///
/// Stateful for [_deciding]: a decision is a network call, and two taps on
/// Approve would post two decisions — the second of which comes back 400,
/// turning a double-tap into an error on a pass that was in fact approved.
class _ApprovalCard extends ConsumerStatefulWidget {
  const _ApprovalCard({required this.pass, required this.l});

  final Map<String, dynamic> pass;
  final _L l;

  @override
  ConsumerState<_ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends ConsumerState<_ApprovalCard> {
  bool _deciding = false;

  /// Refresh-on-success rather than an optimistic removal, so the screen can
  /// never claim a decision the server refused.
  ///
  /// The three outcomes are deliberately distinct, because they call for
  /// different things from the manager:
  ///
  ///  * **Success** — the queue is re-read and the card goes.
  ///  * **400** — someone else decided this pass first. A manager and a guard
  ///    see the same queue (`/approvals` is shared, and a guard's scope is a
  ///    subset of this one), so this is an ordinary race, not an error the
  ///    manager can fix by retrying. `GatePassService.approve` rejects any pass
  ///    that is no longer PENDING_APPROVAL. Telling them to "try again" would
  ///    be a lie — the pass is settled — so the queue is refreshed and the card
  ///    disappears, which is the honest outcome. It also self-heals the stale
  ///    list rather than leaving other decided cards on screen.
  ///  * **Anything else** — the pass is still pending, the card stays exactly as
  ///    it was, and retrying is the right advice.
  Future<void> _decide(bool approved) async {
    final id = passString(widget.pass, 'id');
    if (id == null || _deciding) return;
    final l = widget.l;

    setState(() => _deciding = true);
    try {
      await ref.read(gatePassServiceProvider).decide(id, approved);
      if (!mounted) return;
      ref.invalidate(approvalsProvider); // only after the server has agreed
      _notify(
        approved ? l.passApproved : l.passRejected,
        approved ? AppColors.success : context.miftah.textMuted,
      );
    } catch (error) {
      if (!mounted) return;
      if (_isAlreadyDecided(error)) {
        ref.invalidate(approvalsProvider);
        _notify(l.alreadyDecided, AppColors.warning);
      } else {
        _notify(
          approved ? l.couldNotApprove : l.couldNotReject,
          AppColors.danger,
        );
      }
    } finally {
      if (mounted) setState(() => _deciding = false);
    }
  }

  /// A 400 on this path means exactly one thing.
  ///
  /// The endpoint's only other client-error outcomes are 403 (wrong role — the
  /// screen is unreachable) and 404 (a guard probing a property they are not
  /// posted to — not a manager, whose scope is the whole tenant). The remaining
  /// 400 is `GatePassService.approve` refusing a pass that has left
  /// PENDING_APPROVAL.
  bool _isAlreadyDecided(Object error) =>
      error is DioException && error.response?.statusCode == 400;

  void _notify(String message, Color background) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: background,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = widget.l;
    final pass = widget.pass;
    // Keyed per pass so a tap in a test — and a hit test in a rebuilt list —
    // names one card's button rather than "whichever Approve is on screen".
    final id = passString(pass, 'id') ?? '';
    final name = passString(pass, 'guestName') ?? l.guest;
    final property = passString(pass, 'propertyName');
    final unit = passString(pass, 'unitNumber');
    final purpose = passString(pass, 'purpose');
    final vehicle = passString(pass, 'vehicleNumber');
    final passType = passString(pass, 'passType');
    final window = formatWindow(
      passInstant(pass, 'validFrom'),
      passInstant(pass, 'validTo'),
      ar: l.ar,
    );

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsets.all(16),
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
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                ),
              ),
              if (passType != null)
                _PassTypePill(label: l.passType(passType), ar: l.ar),
            ],
          ),
          const SizedBox(height: 10),
          // `propertyName` is resolved server-side (commit 5180e3a); when it is
          // null the row is gone, and no id is shown in its place — a raw UUID
          // tells a manager nothing they can act on.
          if (property != null)
            _DetailRow(icon: Icons.apartment_outlined, value: property, m: m),
          if (unit != null)
            _DetailRow(
              icon: Icons.home_outlined,
              value: '${l.unit} $unit',
              m: m,
            ),
          _DetailRow(icon: Icons.schedule, value: window, m: m),
          if (purpose != null)
            _DetailRow(icon: Icons.notes_outlined, value: purpose, m: m),
          if (vehicle != null)
            _DetailRow(icon: Icons.directions_car, value: vehicle, m: m),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: SizedBox(
                  key: Key('reject-$id'),
                  child: GoldButton.outlined(
                    label: l.reject,
                    onPressed: _deciding ? null : () => _decide(false),
                    height: 44,
                    icon: _deciding
                        ? SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: m.isDark
                                  ? AppColors.accent
                                  : m.textPrimary,
                            ),
                          )
                        : null,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: SizedBox(
                  key: Key('approve-$id'),
                  child: GoldButton(
                    label: l.approve,
                    onPressed: _deciding ? null : () => _decide(true),
                    height: 44,
                    icon: _deciding
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppColors.primary,
                            ),
                          )
                        : null,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PassTypePill extends StatelessWidget {
  const _PassTypePill({required this.label, required this.ar});

  final String label;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.accent.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: AppColors.accentDark,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.2,
                color: AppColors.accentDark,
              ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.icon, required this.value, required this.m});

  final IconData icon;
  final String value;
  final MiftahColors m;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 15, color: m.textMuted),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: context.isAr
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 13,
                      color: m.textSecondary,
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
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
