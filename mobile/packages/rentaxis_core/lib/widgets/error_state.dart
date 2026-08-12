import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/l10n.dart';

class ErrorState extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;

  const ErrorState({
    super.key,
    this.message = 'Something went wrong',
    this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        // A rounded border can't mix colors per side; the danger accent is an
        // inner strip clipped to the card's radius instead of a left side.
        child: Container(
          constraints: const BoxConstraints(maxWidth: 340),
          decoration: BoxDecoration(
            color: m.surface,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: m.border),
          ),
          clipBehavior: Clip.antiAlias,
          child: Stack(
            children: [
              PositionedDirectional(
                start: 0,
                top: 0,
                bottom: 0,
                child: Container(width: 2, color: m.danger),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
                child: _body(m, ar),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _body(LegacyMiftahColors m, bool ar) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          message,
          style: (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 13.5,
            color: m.textPrimary,
          ),
        ),
        if (onRetry != null) ...[
          const SizedBox(height: 4),
          InkWell(
            onTap: onRetry,
            child: Text(
              ar ? 'إعادة المحاولة' : 'Retry',
              style: ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: AppColors.accentDark,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.4,
                      color: AppColors.accentDark,
                    ),
            ),
          ),
        ],
      ],
    );
  }
}
