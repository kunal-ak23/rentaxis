import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../gatepass/pass_display.dart';

/// What `/result` needs: the gate's verdict, and the credential it was reached
/// on.
///
/// The credential travels with the verdict because `ScanResponse` does not carry
/// one back — deliberately, it is guest-facing only — and **Log exit** has to
/// re-present the same qrToken or numericCode to `POST /scan`. There is nothing
/// on the response to reconstruct it from, so it is passed forward or the exit
/// button cannot exist.
class ScanResultArgs {
  const ScanResultArgs({
    required this.response,
    this.qrToken,
    this.numericCode,
  });

  /// The raw `ScanResponse`. Every field except `result` may be null — see
  /// [ResultScreen].
  final Map<String, dynamic> response;

  final String? qrToken;
  final String? numericCode;
}

/// The verdict, built to be read at arm's length in daylight.
///
/// Deliberately mode-independent: the whole screen is the verdict, a single
/// dark field (green for admit, red for refuse) so it doubles as a light
/// source at night. Type and touch targets are sized for gloved hands and are
/// not reduced for visual tidiness.
///
/// **Every guest field can be null and the screen must still make sense.** When
/// a guard scans a pass for a property they are not posted to, the backend
/// answers REJECTED with `guestName`, `guestPhone`, `vehicleNumber`, `purpose`,
/// `unitNumber`, `passType`, `validFrom` and `validTo` all null — the pass is
/// real, but it is not this guard's to read (`GatePassScanService.scan` returns
/// the outcome without the pass; `GatePassController.toScanResponse` blinds on
/// `pass == null`). Nothing here may assume a field is present: the rejection
/// that carries the least is the one a guard is most likely to see from a
/// stranger at the gate.
///
/// Rejections at the guard's *own* gate do carry the guest, because there the
/// guard has to explain the refusal to the person in front of them.
class ResultScreen extends ConsumerStatefulWidget {
  const ResultScreen({super.key, required this.args});

  final ScanResultArgs args;

  @override
  ConsumerState<ResultScreen> createState() => _ResultScreenState();
}

/// Field colours — fixed, not theme-derived. See the class doc.
const _allowField = Color(0xFF0E2B1B);
const _rejectField = Color(0xFF31120E);
const _allowMark = Color(0xFF1E9E5A);
const _rejectMark = Color(0xFFD64545);
const _rejectText = Color(0xFFF2B3A6);

class _ResultScreenState extends ConsumerState<ResultScreen> {
  bool _loggingExit = false;
  bool _exitLogged = false;
  String? _exitError;

  Map<String, dynamic> get _response => widget.args.response;

  bool get _allowed => passString(_response, 'result') == 'ALLOWED';

  /// Re-presents the same credential as an EXIT.
  ///
  /// EXIT skips the entry checks server-side — status and window govern
  /// admission, and whoever is inside must be able to leave — but it does insist
  /// an ALLOWED entry was recorded first, so this can still be refused with "no
  /// entry recorded".
  Future<void> _logExit() async {
    if (_loggingExit) return;
    setState(() {
      _loggingExit = true;
      _exitError = null;
    });

    final ar = context.isAr;
    try {
      final response = await ref
          .read(gatePassServiceProvider)
          .scan(
            qrToken: widget.args.qrToken,
            numericCode: widget.args.numericCode,
            direction: 'EXIT',
          );
      if (!mounted) return;

      if (passString(response, 'result') == 'ALLOWED') {
        setState(() => _exitLogged = true);
        return;
      }
      // The gate refused the exit — most likely "no entry recorded". Report the
      // refusal rather than the tick: a screen that says "Exit logged" over a
      // rejected scan is a lie the audit trail will not back up.
      setState(
        () => _exitError = describeRejection(
          passString(response, 'reason'),
          ar: ar,
        ),
      );
    } catch (_) {
      if (!mounted) return;
      setState(() => _exitError = _L(ar).exitFailed);
    } finally {
      if (mounted) setState(() => _loggingExit = false);
    }
  }

  void _done() => context.pop();

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    final allowed = _allowed;
    final reason = passString(_response, 'reason');

    // Read once, and never assume any of them arrived.
    final name = passString(_response, 'guestName');
    final phone = passString(_response, 'guestPhone');
    final vehicle = passString(_response, 'vehicleNumber');
    final purpose = passString(_response, 'purpose');
    final unit = passString(_response, 'unitNumber');
    final passType = passString(_response, 'passType');
    final from = passInstant(_response, 'validFrom');
    final to = passInstant(_response, 'validTo');
    final hasWindow = from != null || to != null;

    final rows = <({String label, String value, bool mono})>[
      if (unit != null) (label: l.unit, value: unit, mono: false),
      if (phone != null) (label: l.phone, value: phone, mono: true),
      if (vehicle != null) (label: l.vehicle, value: vehicle, mono: true),
      if (hasWindow)
        (label: l.valid, value: formatWindow(from, to, ar: l.ar), mono: false),
      if (purpose != null) (label: l.purpose, value: purpose, mono: false),
      if (passType != null)
        (label: l.passType, value: l.passTypeValue(passType), mono: false),
    ];

    return PopScope(
      // Nothing here is unsaved — the scan is already recorded server-side — so
      // a back gesture is just Done by another name.
      canPop: true,
      child: Scaffold(
        backgroundColor: allowed ? _allowField : _rejectField,
        body: SafeArea(
          child: Column(
            children: [
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
                  children: [
                    const SizedBox(height: 12),
                    _Mark(allowed: allowed),
                    const SizedBox(height: 26),
                    Text(
                      allowed ? l.allowed : l.doNotAdmit,
                      textAlign: TextAlign.center,
                      style: l.ar
                          ? MiftahType.ar(
                              size: 38,
                              weight: FontWeight.w700,
                              color: Colors.white,
                            )
                          : MiftahType.display(
                              color: Colors.white,
                            ).copyWith(fontSize: 42, letterSpacing: -1.26),
                    ),
                    const SizedBox(height: 8),
                    // The refusal has to say why, in words a guard can repeat
                    // to the person at the gate.
                    Text(
                      allowed
                          ? l.entryRecorded
                          : describeRejection(reason, ar: l.ar),
                      textAlign: TextAlign.center,
                      style: l.ar
                          ? MiftahType.ar(
                              size: allowed ? 15 : 17,
                              weight: allowed
                                  ? FontWeight.w400
                                  : FontWeight.w600,
                              color: allowed
                                  ? Colors.white.withValues(alpha: 0.6)
                                  : _rejectText,
                            )
                          : MiftahType.body(
                              size: allowed ? 15 : 17,
                              color: allowed
                                  ? Colors.white.withValues(alpha: 0.6)
                                  : _rejectText,
                            ).copyWith(
                              fontWeight: allowed
                                  ? FontWeight.w400
                                  : FontWeight.w600,
                            ),
                    ),
                    const SizedBox(height: 30),
                    if (name != null || rows.isNotEmpty)
                      _DetailsPanel(name: name, rows: rows, l: l)
                    else if (!allowed)
                      // The blinded rejection: no guest, by design. Say why the
                      // screen is bare, or it reads as a bug at the worst moment.
                      _NoDetails(l: l),
                    if (!allowed) ...[
                      const SizedBox(height: 12),
                      _WayOut(l: l),
                    ],
                  ],
                ),
              ),
              _Actions(
                allowed: allowed,
                exitLogged: _exitLogged,
                loggingExit: _loggingExit,
                exitError: _exitError,
                onLogExit: _logExit,
                onDone: _done,
                l: l,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The 112px verdict mark with its soft ring — the thing a guard reads first.
class _Mark extends StatelessWidget {
  const _Mark({required this.allowed});

  final bool allowed;

  @override
  Widget build(BuildContext context) {
    final color = allowed ? _allowMark : _rejectMark;
    return Center(
      child: Container(
        width: 112,
        height: 112,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: color,
          boxShadow: [
            BoxShadow(
              color: color.withValues(alpha: 0.16),
              spreadRadius: 18,
              blurRadius: 0,
            ),
          ],
        ),
        child: Icon(
          allowed ? Icons.check_rounded : Icons.close_rounded,
          size: allowed ? 62 : 58,
          color: Colors.white,
        ),
      ),
    );
  }
}

/// Guest name plus whatever fields survived the blinding, on a translucent
/// panel so it reads on either field colour.
class _DetailsPanel extends StatelessWidget {
  const _DetailsPanel({
    required this.name,
    required this.rows,
    required this.l,
  });

  final String? name;
  final List<({String label, String value, bool mono})> rows;
  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(22),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (name != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                name!,
                style: l.ar
                    ? MiftahType.ar(
                        size: 24,
                        weight: FontWeight.w700,
                        color: Colors.white,
                      )
                    : MiftahType.amount(size: 26, color: Colors.white),
              ),
            ),
          for (var i = 0; i < rows.length; i++)
            Container(
              padding: const EdgeInsets.symmetric(vertical: 13),
              decoration: BoxDecoration(
                border: i == rows.length - 1
                    ? null
                    : Border(
                        bottom: BorderSide(
                          color: Colors.white.withValues(alpha: 0.1),
                        ),
                      ),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    rows[i].label,
                    style: l.ar
                        ? MiftahType.ar(
                            size: 13,
                            color: Colors.white.withValues(alpha: 0.55),
                          )
                        : MiftahType.body(
                            size: 13,
                            color: Colors.white.withValues(alpha: 0.55),
                          ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Text(
                      rows[i].value,
                      textAlign: TextAlign.end,
                      style: rows[i].mono
                          ? MiftahType.mono(
                              size: 15,
                              color: Colors.white,
                            ).copyWith(fontWeight: FontWeight.w500)
                          : (l.ar
                                ? MiftahType.ar(
                                    size: 15,
                                    weight: FontWeight.w700,
                                    color: Colors.white,
                                  )
                                : MiftahType.body(
                                    size: 15,
                                    color: Colors.white,
                                  ).copyWith(fontWeight: FontWeight.w700)),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// A refusal is not an ending — tell the guard what to do next.
class _WayOut extends StatelessWidget {
  const _WayOut({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(MiftahRadii.tile),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.info_outline_rounded, size: 20, color: _rejectText),
          const SizedBox(width: 11),
          Expanded(
            child: Text(
              l.wayOut,
              style: l.ar
                  ? MiftahType.ar(size: 13, color: _rejectText)
                  : MiftahType.body(size: 13, color: _rejectText),
            ),
          ),
        ],
      ),
    );
  }
}

class _NoDetails extends StatelessWidget {
  const _NoDetails({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(22),
      ),
      child: Text(
        l.noDetails,
        style: l.ar
            ? MiftahType.ar(size: 15, color: Colors.white)
            : MiftahType.body(size: 15, color: Colors.white),
      ),
    );
  }
}

/// Bottom bar. 58px targets — sized for gloved hands, per the handoff's
/// guard-specific rules; do not shrink these for tidiness.
class _Actions extends StatelessWidget {
  const _Actions({
    required this.allowed,
    required this.exitLogged,
    required this.loggingExit,
    required this.exitError,
    required this.onLogExit,
    required this.onDone,
    required this.l,
  });

  final bool allowed;
  final bool exitLogged;
  final bool loggingExit;
  final String? exitError;
  final VoidCallback onLogExit;
  final VoidCallback onDone;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final field = allowed ? _allowField : _rejectField;
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (exitError != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text(
                exitError!,
                textAlign: TextAlign.center,
                style: l.ar
                    ? MiftahType.ar(
                        size: 14,
                        weight: FontWeight.w600,
                        color: _rejectText,
                      )
                    : MiftahType.body(size: 14, color: _rejectText),
              ),
            ),
          Row(
            children: [
              Expanded(
                child: _GuardButton(
                  label: l.done,
                  filled: true,
                  fieldColor: field,
                  onTap: onDone,
                  l: l,
                ),
              ),
              // Exit is only meaningful once an entry was admitted; a refused
              // visitor never came in.
              if (allowed) ...[
                const SizedBox(width: 10),
                _GuardButton(
                  label: exitLogged
                      ? l.exitLogged
                      : (loggingExit ? l.loggingExit : l.logExit),
                  filled: false,
                  fieldColor: field,
                  onTap: exitLogged || loggingExit ? null : onLogExit,
                  l: l,
                ),
              ] else ...[
                const SizedBox(width: 10),
                _GuardButton(
                  label: l.walkIn,
                  filled: false,
                  fieldColor: field,
                  onTap: () => context.push('/walk-in'),
                  l: l,
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

class _GuardButton extends StatelessWidget {
  const _GuardButton({
    required this.label,
    required this.filled,
    required this.fieldColor,
    required this.onTap,
    required this.l,
  });

  final String label;
  final bool filled;
  final Color fieldColor;
  final VoidCallback? onTap;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final disabled = onTap == null;
    return Opacity(
      opacity: disabled ? 0.6 : 1,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          height: 58,
          alignment: Alignment.center,
          padding: filled ? null : const EdgeInsets.symmetric(horizontal: 22),
          decoration: BoxDecoration(
            color: filled ? Colors.white : null,
            borderRadius: BorderRadius.circular(MiftahRadii.tile),
            border: filled
                ? null
                : Border.all(
                    color: Colors.white.withValues(alpha: 0.3),
                    width: 1.5,
                  ),
          ),
          child: Text(
            label,
            style: l.ar
                ? MiftahType.ar(
                    size: filled ? 16 : 14,
                    weight: FontWeight.w700,
                    color: filled ? fieldColor : Colors.white,
                  )
                : MiftahType.button(
                    size: filled ? 17 : 15,
                    color: filled ? fieldColor : Colors.white,
                  ),
          ),
        ),
      ),
    );
  }
}

// ─── Strings (EN/AR) ────────────────────────────────────────────────────────

class _L {
  _L(this.ar);
  final bool ar;

  String get allowed => ar ? 'مسموح بالدخول' : 'ALLOWED';
  String get doNotAdmit => ar ? 'رفض الدخول' : 'DO NOT ADMIT';
  String get guest => ar ? 'الزائر' : 'Guest';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get phone => ar ? 'رقم الهاتف' : 'Phone';
  String get vehicle => ar ? 'المركبة' : 'Vehicle';
  String get purpose => ar ? 'الغرض' : 'Purpose';
  String get valid => ar ? 'صالح خلال' : 'Valid';
  String get passType => ar ? 'نوع التصريح' : 'Pass type';
  String get noDetails => ar
      ? 'لا توجد بيانات زائر لهذا التصريح. إنه يخص بوابة لست مكلفًا بها.'
      : 'No guest details for this pass. It belongs to a gate you are not '
            'assigned to.';
  String get logExit => ar ? 'تسجيل الخروج' : 'Log exit';
  String get loggingExit => ar ? 'جارٍ التسجيل…' : 'Logging…';
  String get exitLogged => ar ? 'تم تسجيل الخروج' : 'Exit logged';
  String get exitFailed => ar
      ? 'تعذّر تسجيل الخروج. حاول مرة أخرى.'
      : 'Could not log the exit. Try again.';
  String get done => ar ? 'تم' : 'Done';
  String get scanAgain => ar ? 'مسح مرة أخرى' : 'Scan again';

  String passTypeValue(String value) {
    switch (value) {
      case 'SINGLE_USE':
        return ar ? 'استخدام واحد' : 'single use';
      case 'RECURRING':
        return ar ? 'متكرر' : 'recurring';
      default:
        return value.replaceAll('_', ' ').toLowerCase();
    }
  }

  // ── Added by the redesign (design screens 05 / 06) ──
  String get entryRecorded => ar ? 'تم تسجيل الدخول' : 'Entry recorded';
  String get walkIn => ar ? 'زائر بدون تصريح' : 'Walk-in';
  String get wayOut => ar
      ? 'اطلب من الساكن إصدار تصريح جديد، أو سجّل الزائر كزائر بدون تصريح.'
      : 'Ask the resident to issue a new pass, or sign the visitor in as a '
            'walk-in.';
}
