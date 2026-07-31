import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/l10n.dart';
import 'gold_button.dart';

class EmptyState extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final String? actionLabel;
  final VoidCallback? onAction;

  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.subtitle,
    this.actionLabel,
    this.onAction,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 56, color: m.textMuted.withValues(alpha: 0.55)),
            const SizedBox(height: 18),
            Text(
              title.toUpperCase(),
              textAlign: TextAlign.center,
              style: ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    )
                  : GoogleFonts.cinzel(
                      fontSize: 17,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 1.6,
                      color: m.textPrimary,
                    ),
            ),
            if (subtitle != null) ...[
              const SizedBox(height: 10),
              Text(
                subtitle!,
                textAlign: TextAlign.center,
                style:
                    (ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                      fontSize: 13.5,
                      height: 1.6,
                      color: m.textSecondary,
                    ),
              ),
            ],
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 24),
              GoldButton(
                label: actionLabel!,
                onPressed: onAction,
                expanded: false,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
