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

    try {
      final response = await ref.read(gatePassServiceProvider).scan(
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
      setState(() => _exitError = describeRejection(
            passString(response, 'reason'),
          ));
    } catch (_) {
      if (!mounted) return;
      setState(() => _exitError = 'Could not log the exit. Try again.');
    } finally {
      if (mounted) setState(() => _loggingExit = false);
    }
  }

  void _done() => context.pop();

  @override
  Widget build(BuildContext context) {
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
        backgroundColor: AppColors.background,
        body: SafeArea(
          child: Column(
            children: [
              _Verdict(allowed: allowed, reason: reason),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
                  children: [
                    if (name != null)
                      _BigField(label: 'Guest', value: name)
                    else if (!allowed)
                      // The blinded rejection: no guest, by design. Say why the
                      // screen is bare, or it reads as a bug at the worst moment.
                      const _NoDetails(),
                    if (unit != null) _BigField(label: 'Unit', value: unit),
                    if (phone != null) _BigField(label: 'Phone', value: phone),
                    if (vehicle != null)
                      _BigField(label: 'Vehicle', value: vehicle),
                    if (purpose != null)
                      _BigField(label: 'Purpose', value: purpose),
                    if (hasWindow)
                      _BigField(label: 'Valid', value: formatWindow(from, to)),
                    if (passType != null)
                      _BigField(
                        label: 'Pass type',
                        value: passType.replaceAll('_', ' ').toLowerCase(),
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
  const _Verdict({required this.allowed, required this.reason});

  final bool allowed;
  final String? reason;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: allowed ? AppColors.success : AppColors.danger,
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
            allowed ? 'ALLOWED' : 'DO NOT ADMIT',
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 34,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.5,
              color: Colors.white,
            ),
          ),
          if (!allowed) ...[
            const SizedBox(height: 10),
            Text(
              describeRejection(reason),
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 16,
                height: 1.35,
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
  const _NoDetails();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
      ),
      child: const Row(
        children: [
          Icon(Icons.lock_outline, size: 20, color: AppColors.textMuted),
          SizedBox(width: 10),
          Expanded(
            child: Text(
              'No guest details for this pass. It belongs to a gate you are '
              'not assigned to.',
              style: TextStyle(fontSize: 14, color: AppColors.textSecondary),
            ),
          ),
        ],
      ),
    );
  }
}

/// A label over a large value. Sized for a phone held at arm's length.
class _BigField extends StatelessWidget {
  const _BigField({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label.toUpperCase(),
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 1,
              color: AppColors.textMuted,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            value,
            style: const TextStyle(
              fontSize: 21,
              fontWeight: FontWeight.w700,
              color: AppColors.textPrimary,
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
  });

  final bool allowed;
  final bool exitLogged;
  final bool loggingExit;
  final String? exitError;
  final VoidCallback onLogExit;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(top: BorderSide(color: AppColors.border)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (exitError != null) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.dangerLight,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.danger),
              ),
              child: Text(
                exitError!,
                style: const TextStyle(fontSize: 13, color: AppColors.danger),
              ),
            ),
            const SizedBox(height: 10),
          ],
          if (exitLogged) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.successLight,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.success),
              ),
              child: const Row(
                children: [
                  Icon(Icons.check_circle_outline,
                      size: 18, color: AppColors.success),
                  SizedBox(width: 8),
                  Text(
                    'Exit logged',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: AppColors.success,
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
                  child: SizedBox(
                    height: 54,
                    child: OutlinedButton.icon(
                      key: const Key('logExitButton'),
                      onPressed: loggingExit ? null : onLogExit,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppColors.primary,
                        side: const BorderSide(color: AppColors.primary),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                      icon: loggingExit
                          ? const SizedBox(
                              height: 16,
                              width: 16,
                              child:
                                  CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.logout),
                      label: const Text(
                        'Log exit',
                        style: TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w700),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: SizedBox(
                  height: 54,
                  child: ElevatedButton(
                    key: const Key('doneButton'),
                    onPressed: onDone,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.primary,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                      ),
                    ),
                    child: Text(
                      allowed ? 'Done' : 'Scan again',
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w700),
                    ),
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
