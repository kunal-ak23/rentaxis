import 'dart:io';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../widgets/step_header.dart';

/// Step 1 — capture the cheque image.
///
/// Shows a dark camera frame with corner brackets, a gold shutter button,
/// and a list of capture tips. The actual camera invocation is delegated
/// to image_picker (no in-app live preview yet — that would need
/// camera/mobile_scanner; defer to a follow-up).
class Step1Capture extends StatelessWidget {
  final ValueChanged<File> onCaptured;
  final VoidCallback? onClose;

  const Step1Capture({
    super.key,
    required this.onCaptured,
    this.onClose,
  });

  Future<void> _pick(BuildContext context, ImageSource source) async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(
      source: source,
      imageQuality: 85,
    );
    if (picked == null) return;
    onCaptured(File(picked.path));
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 1,
          title: 'Position the cheque',
          showBack: false,
          onClose: onClose,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Lay the cheque flat on a dark surface. Fit all four corners inside the frame.',
                  style: GoogleFonts.inter(
                    fontSize: 13,
                    height: 1.5,
                    color: AppColors.textSecondary,
                  ),
                ),
                const SizedBox(height: 14),
                _CameraFrame(),
                const SizedBox(height: 16),
                ..._tips.map((t) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: _TipRow(text: t),
                    )),
                const SizedBox(height: 22),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _SecondaryButton(
                      icon: Icons.image_outlined,
                      onTap: () => _pick(context, ImageSource.gallery),
                    ),
                    const SizedBox(width: 28),
                    _Shutter(onTap: () => _pick(context, ImageSource.camera)),
                    const SizedBox(width: 28),
                    _SecondaryButton(
                      icon: Icons.flash_on_outlined,
                      onTap: () {
                        // Live flash control needs camera package; placeholder.
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Flash control coming soon')),
                        );
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Center(
                  child: Text(
                    'Tap to capture · or upload from photos',
                    style: GoogleFonts.inter(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  static const _tips = [
    'Good lighting · avoid shadows',
    'Keep camera parallel to cheque',
    'Make sure all 4 corners are visible',
  ];
}

class _CameraFrame extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 1.55,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.navyDark,
          borderRadius: BorderRadius.circular(18),
        ),
        child: Stack(
          children: [
            Center(
              child: Text(
                'Searching for cheque…',
                style: GoogleFonts.inter(
                  fontSize: 11,
                  color: Colors.white.withValues(alpha: 0.3),
                ),
              ),
            ),
            ..._cornerPositions.map((pos) => Positioned(
                  top: pos.top,
                  bottom: pos.bottom,
                  left: pos.left,
                  right: pos.right,
                  child: CustomPaint(
                    size: const Size(30, 30),
                    painter: _CornerPainter(pos.bracket),
                  ),
                )),
          ],
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
  const _Pos({this.top, this.bottom, this.left, this.right, required this.bracket});
}

enum _Bracket { tl, tr, bl, br }

class _CornerPainter extends CustomPainter {
  final _Bracket bracket;
  _CornerPainter(this.bracket);

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = Colors.white.withValues(alpha: 0.4)
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke;
    switch (bracket) {
      case _Bracket.tl:
        canvas.drawLine(const Offset(0, 0), Offset(size.width, 0), p);
        canvas.drawLine(const Offset(0, 0), Offset(0, size.height), p);
        break;
      case _Bracket.tr:
        canvas.drawLine(Offset(0, 0), Offset(size.width, 0), p);
        canvas.drawLine(Offset(size.width, 0), Offset(size.width, size.height), p);
        break;
      case _Bracket.bl:
        canvas.drawLine(Offset(0, size.height), Offset(size.width, size.height), p);
        canvas.drawLine(const Offset(0, 0), Offset(0, size.height), p);
        break;
      case _Bracket.br:
        canvas.drawLine(Offset(0, size.height), Offset(size.width, size.height), p);
        canvas.drawLine(Offset(size.width, 0), Offset(size.width, size.height), p);
        break;
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _TipRow extends StatelessWidget {
  final String text;
  const _TipRow({required this.text});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Container(
            width: 22,
            height: 22,
            decoration: const BoxDecoration(
              color: AppColors.successLight,
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.check, size: 12, color: AppColors.success),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: GoogleFonts.inter(
                fontSize: 12.5,
                color: AppColors.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Shutter extends StatelessWidget {
  final VoidCallback onTap;
  const _Shutter({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 70,
        height: 70,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: AppColors.accent,
          border: Border.all(color: AppColors.surface, width: 4),
          boxShadow: [
            BoxShadow(
              color: AppColors.accent.withValues(alpha: 0.4),
              blurRadius: 18,
              offset: const Offset(0, 8),
            ),
          ],
        ),
      ),
    );
  }
}

class _SecondaryButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;
  const _SecondaryButton({required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: AppColors.surface,
          border: Border.all(color: AppColors.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Icon(icon, size: 16, color: AppColors.textSecondary),
      ),
    );
  }
}
