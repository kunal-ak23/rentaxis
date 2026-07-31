import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';

/// Primary Miftah CTA: bronze→gold→pale-gold gradient fill with wide-tracked
/// uppercase label. Use [GoldButton.outlined] for the secondary variant
/// (hairline gold border on dark chrome, neutral border on light surfaces).
class GoldButton extends StatelessWidget {
  final String label;
  final VoidCallback? onPressed;
  final double height;
  final bool expanded;
  final Widget? icon;

  /// Outlined (secondary) variant instead of the gradient fill.
  final bool _outlined;

  /// For the outlined variant: gold border/text (on dark chrome) instead of
  /// the neutral border/text used on light surfaces.
  final bool onDark;

  const GoldButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.height = 48,
    this.expanded = true,
    this.icon,
  })  : _outlined = false,
        onDark = false;

  const GoldButton.outlined({
    super.key,
    required this.label,
    required this.onPressed,
    this.height = 48,
    this.expanded = true,
    this.icon,
    this.onDark = false,
  }) : _outlined = true;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final enabled = onPressed != null;
    final goldOutlined = _outlined && (onDark || m.isDark);

    final Color fg = _outlined
        ? (goldOutlined ? AppColors.accent : m.textPrimary)
        : AppColors.primary;

    final child = Container(
      height: height,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      decoration: BoxDecoration(
        gradient: _outlined ? null : MiftahGradients.gold,
        borderRadius: BorderRadius.circular(10),
        border: _outlined
            ? Border.all(
                color: goldOutlined ? m.goldOutline : m.borderStrong,
              )
            : null,
      ),
      child: Row(
        mainAxisSize: expanded ? MainAxisSize.max : MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (icon != null) ...[
            IconTheme(
              data: IconThemeData(color: fg, size: 18),
              child: icon!,
            ),
            const SizedBox(width: 10),
          ],
          Flexible(
            // Scale long labels down rather than ellipsizing mid-word.
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                label.toUpperCase(),
                maxLines: 1,
                style: GoogleFonts.josefinSans(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 2.2,
                  color: fg,
                ),
              ),
            ),
          ),
        ],
      ),
    );

    return Opacity(
      opacity: enabled ? 1 : 0.5,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onPressed,
          borderRadius: BorderRadius.circular(10),
          child: child,
        ),
      ),
    );
  }
}
