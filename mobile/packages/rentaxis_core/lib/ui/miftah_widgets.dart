import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import 'miftah_tokens.dart';

/// The widget set behind the redesigned screens. Every mockup element has a
/// one-to-one widget here, so screens become composition rather than styling.
///
/// Usage: `import 'package:rentaxis_core/ui/miftah_widgets.dart';`

// ─────────────────────────────────────────────────────────────────────────────
// Card — the base container. Border, never shadow.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahCard extends StatelessWidget {
  const MiftahCard({
    super.key,
    required this.child,
    this.onTap,
    this.padding = const EdgeInsets.all(MiftahSpacing.cardPad),
    this.radius = MiftahRadii.card,
    this.color,
    this.borderColor,
    this.emphasised = false,
  });

  final Widget child;
  final VoidCallback? onTap;
  final EdgeInsets padding;
  final double radius;
  final Color? color;
  final Color? borderColor;

  /// Draws the 1.5px brass border used for the one card that needs the eye.
  final bool emphasised;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final border = borderColor ?? (emphasised ? MiftahColors.brass : m.border);
    final content = Container(
      padding: padding,
      decoration: BoxDecoration(
        color: color ?? m.surface,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: border, width: emphasised ? 1.5 : 1),
      ),
      child: child,
    );
    if (onTap == null) return content;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(radius),
        onTap: onTap,
        child: content,
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gold hero — the money card. One per screen, maximum.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahGoldCard extends StatelessWidget {
  const MiftahGoldCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(MiftahSpacing.heroPad),
  });

  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(MiftahRadii.hero),
      child: Container(
        decoration: const BoxDecoration(gradient: MiftahGradients.gold),
        child: Stack(
          children: [
            // The soft light bloom in the corner of every gradient card.
            Positioned(
              right: -44,
              top: -56,
              child: Container(
                width: 170,
                height: 170,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: Colors.white.withValues(alpha: 0.14),
                ),
              ),
            ),
            Padding(padding: padding, child: child),
          ],
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status badge — the small caps pill on every row.
// ─────────────────────────────────────────────────────────────────────────────

enum MiftahTone { neutral, brass, success, danger, warning, info, onDark }

class MiftahBadge extends StatelessWidget {
  const MiftahBadge(this.label, {super.key, this.tone = MiftahTone.neutral});

  final String label;
  final MiftahTone tone;

  static ({Color bg, Color fg}) colorsFor(
    MiftahTone tone, {
    bool isDark = false,
  }) => switch (tone) {
    MiftahTone.brass => (
      bg: isDark ? const Color(0x2DC79A3C) : MiftahColors.brassTint,
      fg: isDark ? MiftahColors.brassLight : MiftahColors.brassDeep,
    ),
    MiftahTone.success => (
      bg: isDark ? const Color(0x1F5FA97C) : MiftahColors.successTint,
      fg: isDark ? const Color(0xFF4FC98A) : MiftahColors.success,
    ),
    MiftahTone.danger => (
      bg: isDark ? const Color(0x24E4736A) : MiftahColors.dangerTint,
      fg: isDark ? const Color(0xFFE4736A) : MiftahColors.danger,
    ),
    MiftahTone.warning => (
      bg: isDark ? const Color(0x1FD9A24A) : MiftahColors.warningTint,
      fg: isDark ? MiftahColors.brassLight : MiftahColors.warning,
    ),
    MiftahTone.info => (
      bg: isDark ? const Color(0x224A5B72) : MiftahColors.infoTint,
      fg: isDark ? const Color(0xFFB7C5DC) : MiftahColors.info,
    ),
    MiftahTone.onDark => (bg: Color(0x2DC79A3C), fg: MiftahColors.brassLight),
    MiftahTone.neutral => (
      bg: isDark
          ? Colors.white.withValues(alpha: 0.1)
          : MiftahColors.surfaceAlt,
      fg: isDark ? Colors.white : MiftahColors.textMuted,
    ),
  };

  @override
  Widget build(BuildContext context) {
    final c = colorsFor(tone, isDark: context.miftah.isDark);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: c.bg,
        borderRadius: BorderRadius.circular(MiftahRadii.pill),
      ),
      child: Text(label.toUpperCase(), style: MiftahType.badge(color: c.fg)),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Buttons.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahButton extends StatelessWidget {
  const MiftahButton({
    super.key,
    required this.label,
    this.onPressed,
    this.icon,
    this.height = 54,
    this.expanded = true,
  });

  /// Ink-filled. The default primary action everywhere except money.
  const MiftahButton.primary({
    super.key,
    required this.label,
    this.onPressed,
    this.icon,
    this.height = 54,
    this.expanded = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final Widget? icon;
  final double height;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final button = SizedBox(
      height: height,
      child: FilledButton(
        onPressed: onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: m.isDark
              ? MiftahColors.brassLight
              : MiftahColors.ink,
          foregroundColor: m.isDark ? MiftahColors.ink : Colors.white,
          disabledBackgroundColor: m.border,
          disabledForegroundColor: m.textMuted,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 22),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(MiftahRadii.tile),
          ),
        ),
        child: _Label(label: label, icon: icon),
      ),
    );
    return expanded ? SizedBox(width: double.infinity, child: button) : button;
  }
}

/// Gold gradient button — money actions only ("Sign in", "Scan a pass",
/// "Share with guest").
class MiftahGoldButton extends StatelessWidget {
  const MiftahGoldButton({
    super.key,
    required this.label,
    this.onPressed,
    this.icon,
    this.height = 56,
  });

  final String label;
  final VoidCallback? onPressed;
  final Widget? icon;
  final double height;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: height,
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: MiftahGradients.gold,
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
          boxShadow: onPressed == null ? null : MiftahShadows.gold,
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(MiftahRadii.tile),
            onTap: onPressed,
            child: Center(
              child: DefaultTextStyle(
                style: MiftahType.button(size: 17, color: MiftahColors.ink),
                child: _Label(
                  label: label,
                  icon: icon,
                  color: MiftahColors.ink,
                  size: 17,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Outlined / secondary. Sits beside a primary in a Row.
class MiftahOutlineButton extends StatelessWidget {
  const MiftahOutlineButton({
    super.key,
    required this.label,
    this.onPressed,
    this.height = 54,
    this.tone = MiftahTone.neutral,
  });

  final String label;
  final VoidCallback? onPressed;
  final double height;
  final MiftahTone tone;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final danger = tone == MiftahTone.danger;
    return SizedBox(
      width: double.infinity,
      height: height,
      child: OutlinedButton(
        onPressed: onPressed,
        style: OutlinedButton.styleFrom(
          backgroundColor: danger
              ? (m.isDark ? m.dangerBg : MiftahColors.dangerTint)
              : m.surface,
          foregroundColor: danger ? m.danger : m.textSecondary,
          side: BorderSide(
            color: danger
                ? (m.isDark ? m.danger : MiftahColors.dangerTintBorder)
                : m.borderStrong,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(MiftahRadii.tile),
          ),
        ),
        child: Text(label, style: MiftahType.button(size: 15)),
      ),
    );
  }
}

class _Label extends StatelessWidget {
  const _Label({required this.label, this.icon, this.color, this.size = 16});

  final String label;
  final Widget? icon;
  final Color? color;
  final double size;

  @override
  Widget build(BuildContext context) {
    final text = Text(
      label,
      style: MiftahType.button(size: size, color: color),
    );
    if (icon == null) return text;
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      mainAxisSize: MainAxisSize.min,
      children: [
        IconTheme(
          data: IconThemeData(color: color ?? Colors.white, size: 21),
          child: icon!,
        ),
        const SizedBox(width: 9),
        text,
      ],
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Filter chip — squarish, not a pill. Selected = ink, or brass tint on dark.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahFilterChip extends StatelessWidget {
  const MiftahFilterChip({
    super.key,
    required this.label,
    required this.selected,
    this.onTap,
    this.onDark = false,
  });

  final String label;
  final bool selected;
  final VoidCallback? onTap;
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final dark = onDark || context.miftah.isDark;
    final Color bg;
    final Color fg;
    final Color? border;
    if (selected) {
      bg = dark ? MiftahColors.brassLight : MiftahColors.brassTint;
      fg = dark ? MiftahColors.ink : MiftahColors.brassDeep;
      border = dark ? null : MiftahColors.brassTintBorder;
    } else {
      bg = dark ? Colors.white.withValues(alpha: 0.1) : MiftahColors.surface;
      fg = dark ? Colors.white : MiftahColors.textSecondary;
      border = dark ? null : MiftahColors.borderStrong;
    }
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 9),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(MiftahRadii.chip),
          border: border == null ? null : Border.all(color: border),
        ),
        child: Text(
          label,
          style: MiftahType.cardTitle(color: fg).copyWith(
            fontSize: 12.5,
            fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
            letterSpacing: 0,
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Icon tile — the rounded-square icon that leads most rows.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahIconTile extends StatelessWidget {
  const MiftahIconTile({
    super.key,
    required this.icon,
    this.tone = MiftahTone.brass,
    this.size = 42,
  });

  final IconData icon;
  final MiftahTone tone;
  final double size;

  @override
  Widget build(BuildContext context) {
    final c = MiftahBadge.colorsFor(tone, isDark: context.miftah.isDark);
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: c.bg,
        borderRadius: BorderRadius.circular(size * 0.29),
      ),
      child: Icon(icon, size: size * 0.5, color: c.fg),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section label — "AMENITIES", "PRIORITY".
// ─────────────────────────────────────────────────────────────────────────────

class MiftahSectionLabel extends StatelessWidget {
  const MiftahSectionLabel(this.text, {super.key, this.top = 6});

  final String text;
  final double top;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Padding(
      padding: EdgeInsets.only(top: top, bottom: 10),
      child: Text(
        text.toUpperCase(),
        style: MiftahType.sectionLabel(color: m.textMuted),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Progress bar — gold on a tinted track.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahProgress extends StatelessWidget {
  const MiftahProgress({
    super.key,
    required this.value,
    this.height = 8,
    this.onGold = false,
  });

  final double value; // 0..1
  final double height;

  /// Inside a gold card the bar inverts: ink fill on a translucent track.
  final bool onGold;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(height),
      child: Stack(
        children: [
          Container(
            height: height,
            color: onGold
                ? MiftahColors.ink.withValues(alpha: 0.18)
                : context.miftah.border,
          ),
          FractionallySizedBox(
            widthFactor: value.clamp(0.0, 1.0),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 700),
              curve: Curves.easeOutCubic,
              height: height,
              decoration: BoxDecoration(
                color: onGold ? MiftahColors.ink : null,
                gradient: onGold ? null : MiftahGradients.goldCompact,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom nav — solid, five slots, raised centre action.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahNavItem {
  const MiftahNavItem({required this.icon, required this.label, this.badge});

  final IconData icon;
  final String label;
  final int? badge;
}

/// Replaces the frosted floating pill. Solid white bar, hairline top border,
/// and one gradient circle lifted out of the bar for the app's signature
/// action (renter: Pass · manager: Scan · security: Scan).
class MiftahNavBar extends StatelessWidget {
  const MiftahNavBar({
    super.key,
    required this.items,
    required this.currentIndex,
    required this.onTap,
    this.centreIcon,
    this.centreLabel,
    this.onCentreTap,
  });

  /// Three or four, in the order they appear. Three is what a shell shows when
  /// a capability behind one of the items is switched off for the tenant.
  final List<MiftahNavItem> items;
  final int currentIndex;
  final ValueChanged<int> onTap;

  /// The raised centre action. Omit all three to render a bar without one.
  final IconData? centreIcon;
  final String? centreLabel;
  final VoidCallback? onCentreTap;

  bool get _hasCentre => centreIcon != null && onCentreTap != null;

  @override
  Widget build(BuildContext context) {
    assert(
      items.length == 3 || items.length == 4,
      'MiftahNavBar expects three or four flanking items',
    );
    // Icon + tap without a label used to render a silently blank caption.
    assert(
      !_hasCentre || centreLabel != null,
      'MiftahNavBar centre action needs a label',
    );
    // The raised gold circle only reads as *centred* when the slots either
    // side of it weigh the same, and every slot is an equal-flex Expanded —
    // so the total slot count has to be odd. Four items give 2 + centre + 2
    // and need nothing (this is the layout the design was drawn against).
    // Three items give 2 + centre + 1, which is off by one column: the circle
    // would sit at 5/8 of the width. One empty trailing column restores the
    // balance, so the bar ends in whitespace rather than a misplaced action.
    // In RTL the Row flips with the Directionality, so the gap stays at the
    // bar's end either way.
    final leading = _hasCentre ? (items.length + 1) ~/ 2 : items.length;
    final balance = _hasCentre ? 2 * leading - items.length : 0;
    final slots = <Widget>[
      for (var i = 0; i < leading; i++) _slot(context, i),
      if (_hasCentre) _centre(context),
      for (var i = leading; i < items.length; i++) _slot(context, i),
      for (var i = 0; i < balance; i++) const SizedBox.shrink(),
    ];
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border(top: BorderSide(color: m.border)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 8),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [for (final s in slots) Expanded(child: s)],
          ),
        ),
      ),
    );
  }

  Widget _slot(BuildContext context, int index) {
    final item = items[index];
    final active = index == currentIndex;
    final m = context.miftah;
    final color = active
        ? (m.isDark ? MiftahColors.brassLight : MiftahColors.brassDeep)
        : m.textMuted;
    return InkWell(
      onTap: () => onTap(index),
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Badge(
              isLabelVisible: (item.badge ?? 0) > 0,
              backgroundColor: MiftahColors.dangerBright,
              label: Text('${item.badge}'),
              child: Icon(item.icon, size: 22, color: color),
            ),
            const SizedBox(height: 5),
            Text(
              item.label,
              style: MiftahType.meta(color: color).copyWith(
                fontSize: 10.5,
                fontWeight: active ? FontWeight.w700 : FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _centre(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      onTap: onCentreTap,
      borderRadius: BorderRadius.circular(30),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Transform.translate(
            offset: const Offset(0, -30),
            child: Container(
              width: 52,
              height: 52,
              alignment: Alignment.center,
              decoration: const BoxDecoration(
                shape: BoxShape.circle,
                gradient: MiftahGradients.goldCompact,
                boxShadow: MiftahShadows.gold,
              ),
              child: Icon(centreIcon!, size: 24, color: MiftahColors.ink),
            ),
          ),
          Transform.translate(
            offset: const Offset(0, -26),
            child: Text(
              centreLabel ?? '',
              style: MiftahType.meta(
                color: m.textMuted,
              ).copyWith(fontSize: 10.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom sheet — filters, booking, assignment.
// ─────────────────────────────────────────────────────────────────────────────

Future<T?> showMiftahSheet<T>({
  required BuildContext context,
  required String title,
  String? subtitle,
  required Widget child,
  Widget? action,
}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: MiftahColors.ink.withValues(alpha: 0.5),
    builder: (context) {
      final m = context.miftah;
      return Container(
        decoration: BoxDecoration(
          color: m.background,
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(MiftahRadii.sheet),
          ),
        ),
        padding: EdgeInsets.fromLTRB(
          MiftahSpacing.page,
          14,
          MiftahSpacing.page,
          MediaQuery.of(context).viewInsets.bottom + 30,
        ),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 44,
                  height: 4,
                  decoration: BoxDecoration(
                    color: m.borderStrong,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Text(title, style: MiftahType.title(color: m.textPrimary)),
              if (subtitle != null) ...[
                const SizedBox(height: 6),
                Text(
                  subtitle,
                  style: MiftahType.body(size: 12.5, color: m.textSecondary),
                ),
              ],
              const SizedBox(height: 18),
              Flexible(child: SingleChildScrollView(child: child)),
              if (action != null) ...[const SizedBox(height: 22), action],
            ],
          ),
        ),
      );
    },
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state — always carries an action.
// ─────────────────────────────────────────────────────────────────────────────

class MiftahEmptyState extends StatelessWidget {
  const MiftahEmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(26),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(MiftahRadii.card),
        border: Border.all(color: m.borderStrong, style: BorderStyle.solid),
      ),
      child: Column(
        children: [
          MiftahIconTile(icon: icon, tone: MiftahTone.neutral, size: 52),
          const SizedBox(height: 14),
          Text(
            title,
            style: MiftahType.cardTitle(color: m.textPrimary),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 6),
          Text(
            subtitle,
            style: MiftahType.body(size: 12.5, color: m.textSecondary),
            textAlign: TextAlign.center,
          ),
          if (actionLabel != null) ...[
            const SizedBox(height: 16),
            MiftahButton(label: actionLabel!, onPressed: onAction, height: 44),
          ],
        ],
      ),
    );
  }
}
