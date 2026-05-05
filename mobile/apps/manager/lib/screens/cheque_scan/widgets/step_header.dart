import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Top header for every step in the cheque scan wizard:
///  - back / close icon row
///  - "Step N of 4" label
///  - 4-segment progress bar (gold up to current step)
///  - serif title
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
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
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
                        color: AppColors.surface,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                          side: const BorderSide(color: AppColors.border),
                        ),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(10),
                          onTap: onBack,
                          child: const Icon(Icons.arrow_back,
                              size: 14, color: AppColors.textPrimary),
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
              Text(
                'Step $step of 4',
                style: GoogleFonts.inter(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w500,
                  color: AppColors.textMuted,
                ),
              ),
              SizedBox(
                width: 32,
                height: 32,
                child: InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: onClose,
                  child: const Icon(Icons.close,
                      size: 16, color: AppColors.textSecondary),
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
                  margin: EdgeInsets.only(right: i == 3 ? 0 : 4),
                  decoration: BoxDecoration(
                    color: filled ? AppColors.accent : AppColors.surface2,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              );
            }),
          ),
          const SizedBox(height: 14),
          Text(
            title,
            style: GoogleFonts.sourceSerif4(
              fontSize: 22,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.4,
              color: AppColors.textPrimary,
            ),
          ),
        ],
      ),
    );
  }
}
