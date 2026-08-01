import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
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
    final m = context.miftah;
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

    return PopScope(
      // Nothing here is unsaved — the scan is already recorded server-side — so
      // a back gesture is just Done by another name.
      canPop: true,
      child: Scaffold(
        backgroundColor: m.background,
        body: SafeArea(
          child: Column(
            children: [
              _Verdict(allowed: allowed, reason: reason, l: l),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
                  children: [
                    if (name != null)
                      _BigField(label: l.guest, value: name, l: l)
                    else if (!allowed)
                      // The blinded rejection: no guest, by design. Say why the
                      // screen is bare, or it reads as a bug at the worst moment.
                      _NoDetails(l: l),
                    if (unit != null)
                      _BigField(label: l.unit, value: unit, l: l),
                    if (phone != null)
                      _BigField(label: l.phone, value: phone, l: l),
                    if (vehicle != null)
                      _BigField(label: l.vehicle, value: vehicle, l: l),
                    if (purpose != null)
                      _BigField(label: l.purpose, value: purpose, l: l),
                    if (hasWindow)
                      _BigField(
                        label: l.valid,
                        value: formatWindow(from, to, ar: l.ar),
                        l: l,
                      ),
                    if (passType != null)
                      _BigField(
                        label: l.passType,
                        value: l.passTypeValue(passType),
                        l: l,
                      ),
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

/// The band that has to answer "in or out?" in under a second: full-width, one
/// word, maximum contrast — legible at arm's length in direct sun, where a badge
/// or a coloured border would not be.
class _Verdict extends StatelessWidget {
  const _Verdict({
    required this.allowed,
    required this.reason,
    required this.l,
  });

  final bool allowed;
  final String? reason;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      width: double.infinity,
      color: allowed ? m.success : m.danger,
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
      child: Column(
        children: [
          Icon(
            allowed ? Icons.check_circle : Icons.cancel,
            color: Colors.white,
            size: 64,
          ),
          const SizedBox(height: 10),
          Text(
            allowed ? l.allowed : l.doNotAdmit,
            textAlign: TextAlign.center,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 30,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  )
                : GoogleFonts.cinzel(
                    fontSize: 30,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1.8,
                    color: Colors.white,
                  ),
          ),
          if (!allowed) ...[
            const SizedBox(height: 10),
            Text(
              describeRejection(reason, ar: l.ar),
              textAlign: TextAlign.center,
              style:
                  (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(
                    fontSize: 15,
                    height: 1.45,
                    fontWeight: FontWeight.w500,
                    color: Colors.white,
                  ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Stands in for the guest block on a rejection the guard is not entitled to
/// read.
class _NoDetails extends StatelessWidget {
  const _NoDetails({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsetsDirectional.all(16),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Row(
        children: [
          Icon(Icons.lock_outline, size: 20, color: m.textMuted),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              l.noDetails,
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts
                        .josefinSans)(fontSize: 14, color: m.textSecondary),
            ),
          ),
        ],
      ),
    );
  }
}

/// A label over a large value. Sized for a phone held at arm's length.
class _BigField extends StatelessWidget {
  const _BigField({required this.label, required this.value, required this.l});

  final String label;
  final String value;
  final _L l;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: 16,
        vertical: 12,
      ),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            // Arabic uppercase is a no-op and tracking breaks glyph joining, so
            // the label only tracks/uppercases in English.
            l.ar ? label : label.toUpperCase(),
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w600,
                    color: m.textMuted,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1,
                    color: m.textMuted,
                  ),
          ),
          const SizedBox(height: 3),
          Text(
            value,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 19,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.cinzel(
                    fontSize: 20,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
        ],
      ),
    );
  }
}

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
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border(top: BorderSide(color: m.border)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (exitError != null) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsetsDirectional.all(10),
              decoration: BoxDecoration(
                color: m.dangerBg,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: m.danger),
              ),
              child: Text(
                exitError!,
                style: bodyFont(fontSize: 13, color: m.danger),
              ),
            ),
            const SizedBox(height: 10),
          ],
          if (exitLogged) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsetsDirectional.all(10),
              decoration: BoxDecoration(
                color: m.successBg,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: m.success),
              ),
              child: Row(
                children: [
                  Icon(Icons.check_circle_outline, size: 18, color: m.success),
                  const SizedBox(width: 8),
                  Text(
                    l.exitLogged,
                    style: bodyFont(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: m.success,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
          ],
          Row(
            children: [
              // Log exit is offered only on an allowed entry, and only until it
              // succeeds: a second EXIT on the same pass would write a second
              // scan row for one departure.
              if (allowed && !exitLogged) ...[
                Expanded(
                  child: GoldButton.outlined(
                    key: const Key('logExitButton'),
                    label: loggingExit ? l.loggingExit : l.logExit,
                    onPressed: loggingExit ? null : onLogExit,
                    height: 54,
                    icon: loggingExit
                        ? const SizedBox(
                            height: 16,
                            width: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.logout),
                  ),
                ),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: GoldButton(
                  key: const Key('doneButton'),
                  label: allowed ? l.done : l.scanAgain,
                  onPressed: onDone,
                  height: 54,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Result screen strings (EN/AR). Lightweight per-screen pattern — see
/// arabic-brief.
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
}
