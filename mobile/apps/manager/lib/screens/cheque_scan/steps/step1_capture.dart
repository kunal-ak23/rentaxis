import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../widgets/step_header.dart';

/// Step 1 — capture the cheque image.
///
/// Shows a live in-app camera preview inside a dark frame with corner
/// brackets, a gold shutter button, a torch toggle, and a gallery
/// fallback. Camera lifecycle is fully managed: initialised on mount,
/// disposed on dispose, paused on app backgrounding.
class Step1Capture extends StatefulWidget {
  final ValueChanged<File> onCaptured;
  final VoidCallback? onClose;

  const Step1Capture({super.key, required this.onCaptured, this.onClose});

  @override
  State<Step1Capture> createState() => _Step1CaptureState();
}

class _Step1CaptureState extends State<Step1Capture>
    with WidgetsBindingObserver {
  CameraController? _controller;
  String? _initError;
  bool _flashOn = false;
  bool _capturing = false;
  // Set to true in dispose() so async camera-init paths can bail out
  // cleanly if the user pops the route while initialise() is in flight.
  bool _disposed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initCamera();
  }

  @override
  void dispose() {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
    _controller?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_disposed) return;
    final ctrl = _controller;
    if (state == AppLifecycleState.inactive) {
      // Release camera while backgrounded; resume rebuilds it.
      ctrl?.dispose();
      _controller = null;
    } else if (state == AppLifecycleState.resumed) {
      _initCamera();
    }
  }

  Future<void> _initCamera() async {
    try {
      final cameras = await availableCameras();
      if (_disposed) return;
      if (cameras.isEmpty) {
        if (mounted) {
          setState(() => _initError = _L(context.isAr).noCamera);
        }
        return;
      }
      // Prefer back camera if multiple are present.
      final back = cameras.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cameras.first,
      );
      final ctrl = CameraController(
        back,
        ResolutionPreset.high,
        enableAudio: false,
        imageFormatGroup: ImageFormatGroup.jpeg,
      );
      await ctrl.initialize();
      if (_disposed) {
        // The user popped while initialise was in flight — release the
        // controller we just opened so the camera doesn't stay on.
        await ctrl.dispose();
        return;
      }
      _controller = ctrl;
      setState(() {});
    } catch (e) {
      if (_disposed || !mounted) return;
      setState(() => _initError = _humanizeCameraError(e));
    }
  }

  String _humanizeCameraError(Object e) {
    final msg = e.toString();
    final l = _L(context.isAr);
    if (msg.contains('CameraAccessDenied') ||
        msg.contains('Permission denied')) {
      return l.cameraAccessDenied;
    }
    return l.cameraUnavailable(msg);
  }

  Future<void> _takePicture() async {
    final ctrl = _controller;
    if (ctrl == null || !ctrl.value.isInitialized || _capturing) return;
    setState(() => _capturing = true);
    final flashWasOn = _flashOn;
    try {
      if (flashWasOn) {
        await ctrl.setFlashMode(FlashMode.torch);
      }
      try {
        final XFile shot = await ctrl.takePicture();
        widget.onCaptured(File(shot.path));
      } finally {
        // Always restore — if takePicture throws (camera busy, orientation
        // change, etc.) we mustn't leave the torch stuck on.
        if (flashWasOn && !_disposed) {
          try {
            await ctrl.setFlashMode(FlashMode.off);
          } catch (_) {
            /* best effort */
          }
        }
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_L(context.isAr).captureFailed(e.toString()))),
      );
    } finally {
      if (mounted) setState(() => _capturing = false);
    }
  }

  Future<void> _pickFromGallery() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
    );
    if (picked == null) return;
    widget.onCaptured(File(picked.path));
  }

  void _toggleFlash() {
    setState(() => _flashOn = !_flashOn);
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 1,
          title: l.title,
          showBack: false,
          onClose: widget.onClose,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.instructions,
                  style: bodyFont(
                    fontSize: 13,
                    height: 1.5,
                    color: m.textSecondary,
                  ),
                ),
                const SizedBox(height: 14),
                _CameraFrame(
                  controller: _controller,
                  initError: _initError,
                  l: l,
                ),
                const SizedBox(height: 16),
                ...l.tips.map(
                  (t) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: _TipRow(text: t, l: l),
                  ),
                ),
                const SizedBox(height: 22),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _SecondaryButton(
                      icon: Icons.image_outlined,
                      onTap: _pickFromGallery,
                    ),
                    const SizedBox(width: 28),
                    _Shutter(
                      capturing: _capturing,
                      enabled:
                          _initError == null &&
                          (_controller?.value.isInitialized ?? false),
                      onTap: _takePicture,
                    ),
                    const SizedBox(width: 28),
                    _SecondaryButton(
                      icon: _flashOn
                          ? Icons.flash_on
                          : Icons.flash_off_outlined,
                      onTap: _toggleFlash,
                      active: _flashOn,
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Center(
                  child: Text(
                    _initError != null ? l.useGallery : l.tapToCapture,
                    style: bodyFont(fontSize: 11, color: m.textMuted),
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

/// Step 1 strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'وضع الشيك' : 'Position the cheque';
  String get instructions => ar
      ? 'ضع الشيك على سطح داكن. تأكد من ظهور جميع الزوايا الأربع داخل الإطار.'
      : 'Lay the cheque flat on a dark surface. Fit all four corners inside the frame.';
  String get useGallery => ar
      ? 'استخدم أيقونة المعرض لاختيار صورة'
      : 'Use the gallery icon to pick a photo';
  String get tapToCapture =>
      ar ? 'اضغط على الزر الذهبي للالتقاط' : 'Tap the gold button to capture';
  String get noCamera => ar
      ? 'لا توجد كاميرا متاحة على هذا الجهاز.'
      : 'No camera available on this device.';
  String get cameraAccessDenied => ar
      ? 'تم رفض الوصول إلى الكاميرا. فعّلها من إعدادات الجهاز، أو استخدم صورة من المعرض بدلاً من ذلك.'
      : 'Camera access is denied. Enable it in your device settings, or use a photo from your gallery instead.';
  String cameraUnavailable(String msg) =>
      ar ? 'الكاميرا غير متاحة: $msg' : 'Camera unavailable: $msg';
  String captureFailed(String msg) =>
      ar ? 'فشل الالتقاط: $msg' : 'Capture failed: $msg';
  String get startingCamera => ar ? 'جارٍ تشغيل الكاميرا…' : 'Starting camera…';

  List<String> get tips => ar
      ? const [
          'إضاءة جيدة · تجنّب الظلال',
          'أبقِ الكاميرا موازية للشيك',
          'تأكد من ظهور الزوايا الأربع',
        ]
      : const [
          'Good lighting · avoid shadows',
          'Keep camera parallel to cheque',
          'Make sure all 4 corners are visible',
        ];
}

class _CameraFrame extends StatelessWidget {
  final CameraController? controller;
  final String? initError;
  final _L l;
  const _CameraFrame({
    required this.controller,
    required this.initError,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return AspectRatio(
      aspectRatio: 1.55,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(18),
        child: Container(
          color: AppColors.navyDark,
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (initError != null)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Center(
                    child: Text(
                      initError!,
                      textAlign: TextAlign.center,
                      style: bodyFont(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.75),
                        height: 1.5,
                      ),
                    ),
                  ),
                )
              else if (controller == null || !controller!.value.isInitialized)
                Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const SizedBox(
                        width: 22,
                        height: 22,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          valueColor: AlwaysStoppedAnimation(AppColors.accent),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        l.startingCamera,
                        style: bodyFont(
                          fontSize: 11,
                          color: Colors.white.withValues(alpha: 0.5),
                        ),
                      ),
                    ],
                  ),
                )
              else
                FittedBox(
                  fit: BoxFit.cover,
                  child: SizedBox(
                    width: controller!.value.previewSize?.height ?? 0,
                    height: controller!.value.previewSize?.width ?? 0,
                    child: CameraPreview(controller!),
                  ),
                ),
              // Corner-bracket capture guide — functional overlay, left as-is
              // per the scanner brief (chrome/labels/buttons only restyled).
              ..._cornerPositions.map(
                (pos) => Positioned(
                  top: pos.top,
                  bottom: pos.bottom,
                  left: pos.left,
                  right: pos.right,
                  child: CustomPaint(
                    size: const Size(30, 30),
                    painter: _CornerPainter(pos.bracket),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static const _cornerPositions = [
    _Pos(top: 14, left: 14, bracket: _Bracket.tl),
    _Pos(top: 14, right: 14, bracket: _Bracket.tr),
    _Pos(bottom: 14, left: 14, bracket: _Bracket.bl),
    _Pos(bottom: 14, right: 14, bracket: _Bracket.br),
  ];
}

class _Pos {
  final double? top;
  final double? bottom;
  final double? left;
  final double? right;
  final _Bracket bracket;
  const _Pos({
    this.top,
    this.bottom,
    this.left,
    this.right,
    required this.bracket,
  });
}

enum _Bracket { tl, tr, bl, br }

class _CornerPainter extends CustomPainter {
  final _Bracket bracket;
  _CornerPainter(this.bracket);

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = Colors.white.withValues(alpha: 0.6)
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke;
    switch (bracket) {
      case _Bracket.tl:
        canvas.drawLine(const Offset(0, 0), Offset(size.width, 0), p);
        canvas.drawLine(const Offset(0, 0), Offset(0, size.height), p);
        break;
      case _Bracket.tr:
        canvas.drawLine(Offset(0, 0), Offset(size.width, 0), p);
        canvas.drawLine(
          Offset(size.width, 0),
          Offset(size.width, size.height),
          p,
        );
        break;
      case _Bracket.bl:
        canvas.drawLine(
          Offset(0, size.height),
          Offset(size.width, size.height),
          p,
        );
        canvas.drawLine(const Offset(0, 0), Offset(0, size.height), p);
        break;
      case _Bracket.br:
        canvas.drawLine(
          Offset(0, size.height),
          Offset(size.width, size.height),
          p,
        );
        canvas.drawLine(
          Offset(size.width, 0),
          Offset(size.width, size.height),
          p,
        );
        break;
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _TipRow extends StatelessWidget {
  final String text;
  final _L l;
  const _TipRow({required this.text, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: 12,
        vertical: 10,
      ),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Container(
            width: 22,
            height: 22,
            decoration: BoxDecoration(
              color: m.successBg,
              shape: BoxShape.circle,
            ),
            child: Icon(Icons.check, size: 12, color: m.success),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts
                        .josefinSans)(fontSize: 12.5, color: m.textSecondary),
            ),
          ),
        ],
      ),
    );
  }
}

class _Shutter extends StatelessWidget {
  final VoidCallback onTap;
  final bool enabled;
  final bool capturing;
  const _Shutter({
    required this.onTap,
    required this.enabled,
    required this.capturing,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: enabled ? onTap : null,
      child: Container(
        width: 70,
        height: 70,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: enabled
              ? AppColors.accent
              : AppColors.accent.withValues(alpha: 0.4),
          border: Border.all(color: m.surface, width: 4),
          boxShadow: enabled
              ? [
                  BoxShadow(
                    color: AppColors.accent.withValues(alpha: 0.4),
                    blurRadius: 18,
                    offset: const Offset(0, 8),
                  ),
                ]
              : [],
        ),
        child: capturing
            ? const Center(
                child: SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(
                    strokeWidth: 2.5,
                    valueColor: AlwaysStoppedAnimation(AppColors.primary),
                  ),
                ),
              )
            : null,
      ),
    );
  }
}

class _SecondaryButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  final bool active;
  const _SecondaryButton({
    required this.icon,
    required this.onTap,
    this.active = false,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: active ? AppColors.accent : m.surface,
          border: Border.all(color: active ? AppColors.accent : m.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Icon(
          icon,
          size: 16,
          color: active ? AppColors.primary : m.textSecondary,
        ),
      ),
    );
  }
}
