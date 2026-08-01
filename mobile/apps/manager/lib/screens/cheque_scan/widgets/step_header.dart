import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Top header for every step in the cheque scan wizard:
///  - back / close icon row
///  - "Step N of 4" label
///  - 4-segment progress bar (gold up to current step)
///  - Cinzel/Naskh title, matching the Miftah chrome typography.
class StepHeader extends StatelessWidget {
  final int step;
  final String title;
  final bool showBack;
  final VoidCallback? onBack;
  final VoidCallback? onClose;

  const StepHeader({
    super.key,
    required this.step,
    required this.title,
    this.showBack = true,
    this.onBack,
    this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    final stepLabel = ar ? 'الخطوة $step من 4' : 'Step $step of 4';

    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(20, 4, 20, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              SizedBox(
                width: 32,
                height: 32,
                child: showBack
                    ? Material(
                        color: m.surface,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                          side: BorderSide(color: m.border),
                        ),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(10),
                          onTap: onBack,
                          child: Icon(
                            ar ? Icons.arrow_forward : Icons.arrow_back,
                            size: 14,
                            color: m.textPrimary,
                          ),
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
              Text(
                stepLabel,
                style: ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w500,
                        color: m.textMuted,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 11.5,
                        fontWeight: FontWeight.w500,
                        letterSpacing: 1.0,
                        color: m.textMuted,
                      ),
              ),
              SizedBox(
                width: 32,
                height: 32,
                child: InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: onClose,
                  child: Icon(Icons.close, size: 16, color: m.textSecondary),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          // 4-segment progress bar
          Row(
            children: List.generate(4, (i) {
              final filled = (i + 1) <= step;
              return Expanded(
                child: Container(
                  height: 3,
                  margin: EdgeInsetsDirectional.only(end: i == 3 ? 0 : 4),
                  decoration: BoxDecoration(
                    gradient: filled ? MiftahGradients.goldProgress : null,
                    color: filled ? null : m.surfaceAlt,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              );
            }),
          ),
          const SizedBox(height: 14),
          Text(
            title,
            style: ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 21,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.cinzel(
                    fontSize: 21,
                    fontWeight: FontWeight.w500,
                    letterSpacing: 0.4,
                    color: m.textPrimary,
                  ),
          ),
        ],
      ),
    );
  }
}
