import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'result_screen.dart';

/// The gate scanner: a full-screen viewfinder, plus a keyed-code fallback for
/// the guest whose phone screen will not read.
///
/// Every scan is sent as `ENTRY`. Exit is logged from the result screen, on a
/// pass that has already been resolved — see [ScanResultArgs].
class ScanScreen extends ConsumerStatefulWidget {
  const ScanScreen({super.key, this.viewfinderBuilder});

  /// Replaces the live camera.
  ///
  /// Exists for tests only: [MobileScanner] drives a platform camera through a
  /// method channel that a test binding has no implementation for, so pumping
  /// the real one either throws or renders the plugin's error state — and either
  /// way the screen's actual behaviour (throttling detections, dispatching
  /// scans) never gets exercised. Injecting the viewfinder keeps that behaviour
  /// under test without the camera.
  @visibleForTesting
  final Widget Function(BuildContext context, void Function(String) onDetect)?
      viewfinderBuilder;

  @override
  ConsumerState<ScanScreen> createState() => ScanScreenState();
}

/// Public so tests can reach [handleDetection] — the camera cannot be driven
/// from a widget test, so the detection callback is the seam that stands in for
/// a barcode crossing the viewfinder.
class ScanScreenState extends ConsumerState<ScanScreen> {
  /// How long a detection is ignored after the previous scan finishes.
  ///
  /// The camera reports a barcode it can see roughly every frame, so a pass left
  /// sitting in front of the lens — which is exactly what happens while the
  /// guard reads the verdict and the guest puts their phone away — fires
  /// detections continuously. Unthrottled that is dozens of identical scans, and
  /// `POST /scan` is capped at 30/min per IP (`PublicRateLimitFilter`): one
  /// forgotten phone on the desk would spend the whole gate's budget in seconds
  /// and start 429ing real guests.
  static const scanCooldown = Duration(seconds: 3);

  late final MobileScannerController? _controller = widget.viewfinderBuilder == null
      ? MobileScannerController(
          // The pass credential is a QR token; restricting the formats keeps a
          // barcode on the guest's parcel from being read as a scan attempt.
          formats: const [BarcodeFormat.qrCode],
          detectionSpeed: DetectionSpeed.normal,
        )
      : null;

  /// True from the moment a scan is accepted until the result screen it pushed
  /// is popped.
  ///
  /// It deliberately outlives the network call. The result screen is *pushed
  /// over* this one, so the camera keeps running and keeps detecting underneath
  /// it — releasing when the request returns would let the pass still in frame
  /// fire a second scan while the guard is reading the first one's verdict.
  bool _busy = false;

  /// When the last scan cycle finished, for [scanCooldown].
  DateTime? _lastScanEndedAt;

  String? _error;

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  /// A barcode crossed the viewfinder.
  ///
  /// The cooldown is applied here rather than in [submitScan] on purpose: it
  /// exists to throttle a camera that cannot help repeating itself, not a guard.
  /// Keying a code by hand is self-limiting, and making a guard wait out a
  /// cooldown before a deliberate second attempt would be the app inventing a
  /// delay at a gate.
  @visibleForTesting
  void handleDetection(String? rawValue) {
    if (rawValue == null || rawValue.trim().isEmpty) return;
    if (_busy) return;

    final lastEnd = _lastScanEndedAt;
    if (lastEnd != null && DateTime.now().difference(lastEnd) < scanCooldown) {
      return;
    }

    submitScan(qrToken: rawValue);
  }

  /// Sends one credential to the gate and shows what came back.
  ///
  /// Exactly one of [qrToken] / [numericCode]; [GatePassApiService.scan] throws
  /// an [ArgumentError] rather than spending a round trip that cannot succeed.
  @visibleForTesting
  Future<void> submitScan({String? qrToken, String? numericCode}) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final response = await ref.read(gatePassServiceProvider).scan(
            qrToken: qrToken,
            numericCode: numericCode,
            direction: 'ENTRY',
          );
      if (!mounted) return;
      // A REJECTED verdict is a successful call — the result screen renders the
      // refusal. Only a transport/HTTP failure lands in the catch below.
      await context.push(
        '/result',
        extra: ScanResultArgs(
          response: response,
          qrToken: qrToken,
          numericCode: numericCode,
        ),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = _describeScanError(error));
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          // Stamped on return, not on dispatch: the cooldown should start when
          // the guard is looking at the viewfinder again, otherwise a long read
          // of the result screen would burn it off and the pass still in frame
          // would re-fire the instant they came back.
          _lastScanEndedAt = DateTime.now();
        });
      }
    }
  }

  String _describeScanError(Object error) {
    if (error is DioException) {
      if (error.response?.statusCode == 429) {
        // 30/min per IP. A real gate is nowhere near it, so this almost always
        // means something is scanning in a loop — say so, rather than inviting
        // the retry that keeps the bucket empty.
        return 'Too many scans in the last minute. Wait a moment, then scan '
            'again.';
      }
      if (error.response?.statusCode == 403) {
        return 'You are not permitted to scan. Ask your manager to check your '
            'account.';
      }
      if (error.type == DioExceptionType.connectionError ||
          error.type == DioExceptionType.connectionTimeout ||
          error.type == DioExceptionType.receiveTimeout ||
          error.type == DioExceptionType.sendTimeout) {
        return 'No connection. Check the network and scan again.';
      }
    }
    return 'Could not check that pass. Try again.';
  }

  Future<void> _openCodeSheet() async {
    final code = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => const _NumericCodeSheet(),
    );
    if (code == null || !mounted) return;
    await submitScan(numericCode: code);
  }

  Future<void> _retryCamera() async {
    setState(() => _error = null);
    try {
      await _controller?.start();
    } catch (_) {
      // start() reports through the controller's own error state, which
      // errorBuilder already renders; nothing to add here.
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        title: const Text('Scan pass'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => context.pop(),
        ),
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                _buildViewfinder(context),
                const IgnorePointer(child: _ScanReticle()),
                if (_busy)
                  Container(
                    color: Colors.black54,
                    child: const Center(
                      child: CircularProgressIndicator(color: Colors.white),
                    ),
                  ),
              ],
            ),
          ),
          _ScanFooter(
            error: _error,
            onEnterCode: _busy ? null : _openCodeSheet,
          ),
        ],
      ),
    );
  }

  Widget _buildViewfinder(BuildContext context) {
    final builder = widget.viewfinderBuilder;
    if (builder != null) return builder(context, handleDetection);

    return MobileScanner(
      controller: _controller,
      onDetect: (capture) {
        for (final barcode in capture.barcodes) {
          final raw = barcode.rawValue;
          if (raw != null && raw.trim().isNotEmpty) {
            handleDetection(raw);
            // One credential per frame: a frame holding two QR codes is not a
            // gate the guard meant to open twice.
            return;
          }
        }
      },
      // Without this the plugin renders a bare black box with an error icon —
      // indistinguishable, to a guard, from a camera that is simply pointed at
      // something dark. A denied permission has to say so and offer a way out.
      errorBuilder: (context, exception, _) => _CameraError(
        exception: exception,
        onRetry: _retryCamera,
        onEnterCode: _openCodeSheet,
      ),
    );
  }
}

/// The camera could not start.
class _CameraError extends StatelessWidget {
  const _CameraError({
    required this.exception,
    required this.onRetry,
    required this.onEnterCode,
  });

  final MobileScannerException exception;
  final VoidCallback onRetry;
  final VoidCallback onEnterCode;

  @override
  Widget build(BuildContext context) {
    final denied =
        exception.errorCode == MobileScannerErrorCode.permissionDenied;
    final unsupported =
        exception.errorCode == MobileScannerErrorCode.unsupported;

    final String message;
    if (denied) {
      // No permission_handler in this app, so the app cannot open Settings for
      // them; naming the exact path is the next best thing.
      message = 'RentAxis Security needs the camera to read passes.\n\n'
          'Open Settings › Apps › RentAxis Security › Permissions and allow '
          'Camera, then tap Try again.';
    } else if (unsupported) {
      message = 'This device cannot scan QR codes. Use the code entry below.';
    } else {
      message = 'The camera could not start. Tap Try again, or use the code '
          'entry below.';
    }

    return ColoredBox(
      color: Colors.black,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                denied ? Icons.no_photography_outlined : Icons.videocam_off,
                size: 56,
                color: Colors.white70,
              ),
              const SizedBox(height: 16),
              Text(
                denied ? 'Camera access is off' : 'Camera unavailable',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                  color: Colors.white,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                message,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, color: Colors.white70),
              ),
              const SizedBox(height: 22),
              if (!unsupported)
                OutlinedButton.icon(
                  onPressed: onRetry,
                  icon: const Icon(Icons.refresh),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.white,
                    side: const BorderSide(color: Colors.white54),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 20, vertical: 12),
                  ),
                  label: const Text('Try again'),
                ),
              const SizedBox(height: 10),
              TextButton(
                onPressed: onEnterCode,
                child: const Text(
                  'Enter code instead',
                  style: TextStyle(color: AppColors.gold400, fontSize: 15),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The aiming frame.
class _ScanReticle extends StatelessWidget {
  const _ScanReticle();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        width: 240,
        height: 240,
        decoration: BoxDecoration(
          border: Border.all(color: AppColors.gold400, width: 3),
          borderRadius: BorderRadius.circular(20),
        ),
      ),
    );
  }
}

class _ScanFooter extends StatelessWidget {
  const _ScanFooter({required this.error, required this.onEnterCode});

  final String? error;
  final VoidCallback? onEnterCode;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: Colors.black,
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (error != null) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.danger.withValues(alpha: 0.18),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.danger),
              ),
              child: Row(
                children: [
                  const Icon(Icons.error_outline,
                      color: Colors.white, size: 18),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      error!,
                      style: const TextStyle(color: Colors.white, fontSize: 13),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
          ] else
            const Padding(
              padding: EdgeInsets.only(bottom: 14),
              child: Text(
                "Point the camera at the guest's pass",
                style: TextStyle(color: Colors.white70, fontSize: 14),
              ),
            ),
          SizedBox(
            width: double.infinity,
            height: 52,
            child: OutlinedButton.icon(
              key: const Key('enterCodeButton'),
              onPressed: onEnterCode,
              icon: const Icon(Icons.dialpad),
              style: OutlinedButton.styleFrom(
                foregroundColor: Colors.white,
                disabledForegroundColor: Colors.white38,
                side: const BorderSide(color: Colors.white54),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              label: const Text(
                'Enter code instead',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Keyed entry of the 8-digit numeric code, for the pass whose QR will not read.
///
/// Eight digits is the server's own format (`GatePassService.uniqueNumericCode`
/// formats `%08d`), so Check is held disabled until the guard has typed exactly
/// that — a short code can only ever come back "not recognised", and finding
/// that out costs a round trip and one of the gate's 30 scans per minute.
class _NumericCodeSheet extends StatefulWidget {
  const _NumericCodeSheet();

  @override
  State<_NumericCodeSheet> createState() => _NumericCodeSheetState();
}

class _NumericCodeSheetState extends State<_NumericCodeSheet> {
  static const _codeLength = 8;

  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final code = _controller.text.trim();
    if (code.length != _codeLength) return;
    Navigator.of(context).pop(code);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        decoration: const BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                margin: const EdgeInsets.only(bottom: 18),
                decoration: BoxDecoration(
                  color: AppColors.border,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const Text(
              'Enter the 8-digit code',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w700,
                color: AppColors.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            const Text(
              "It is printed on the guest's pass, under the QR code.",
              style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
            ),
            const SizedBox(height: 16),
            TextField(
              key: const Key('numericCodeField'),
              controller: _controller,
              autofocus: true,
              keyboardType: TextInputType.number,
              textAlign: TextAlign.center,
              maxLength: _codeLength,
              inputFormatters: [
                FilteringTextInputFormatter.digitsOnly,
                LengthLimitingTextInputFormatter(_codeLength),
              ],
              style: const TextStyle(
                fontSize: 26,
                fontWeight: FontWeight.w700,
                letterSpacing: 8,
                color: AppColors.textPrimary,
              ),
              decoration: InputDecoration(
                counterText: '',
                hintText: '--------',
                hintStyle: TextStyle(
                  fontSize: 26,
                  letterSpacing: 8,
                  color: AppColors.textMuted.withValues(alpha: 0.4),
                ),
                filled: true,
                fillColor: AppColors.surface2,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(14),
                  borderSide: const BorderSide(color: AppColors.border),
                ),
              ),
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => _submit(),
            ),
            const SizedBox(height: 16),
            SizedBox(
              height: 52,
              child: ElevatedButton(
                key: const Key('submitCodeButton'),
                onPressed:
                    _controller.text.length == _codeLength ? _submit : null,
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.primary,
                  disabledBackgroundColor:
                      AppColors.primary.withValues(alpha: 0.35),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                child: const Text(
                  'Check pass',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
