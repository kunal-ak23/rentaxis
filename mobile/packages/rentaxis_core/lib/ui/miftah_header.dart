import 'package:flutter/material.dart';

import 'miftah_tokens.dart';

/// Header furniture shared by the app-root screens.
///
/// In the redesign there is no shell AppBar — each screen owns its header. The
/// root screens (renter Home, manager Today) all use the same right-hand pair:
/// an outlined bell carrying unread state, and an ink initials avatar that is
/// the way into the profile area. These live here so that pair stays identical
/// across the apps, and so removing a shell AppBar can never orphan the
/// profile route again.

/// Outlined circular icon button, 38px. Shows a dot for "something unread" or
/// a count when the exact number matters.
class MiftahCircleButton extends StatelessWidget {
  const MiftahCircleButton({
    super.key,
    required this.icon,
    required this.onTap,
    this.badgeCount,
    this.showDot = false,
    this.tooltip,
    this.onDark = false,
  });

  final IconData icon;
  final VoidCallback onTap;

  /// When set and > 0, renders the number. Takes precedence over [showDot].
  final int? badgeCount;
  final bool showDot;
  final String? tooltip;

  /// On an ink header the icon and outline must invert, or they vanish into
  /// the field.
  final bool onDark;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final iconColor = onDark ? Colors.white : scheme.onSurface;
    final outline = onDark
        ? Colors.white.withValues(alpha: 0.22)
        : MiftahColors.borderStrong;
    final badgeRing = onDark ? MiftahColors.ink : scheme.surface;
    final count = badgeCount ?? 0;
    final button = GestureDetector(
      onTap: onTap,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: outline),
              color: onDark ? Colors.white.withValues(alpha: 0.1) : null,
            ),
            child: Icon(icon, size: 19, color: iconColor),
          ),
          if (count > 0)
            PositionedDirectional(
              top: -4,
              end: -4,
              child: Container(
                constraints: const BoxConstraints(minWidth: 19),
                height: 19,
                alignment: Alignment.center,
                padding: const EdgeInsets.symmetric(horizontal: 5),
                decoration: BoxDecoration(
                  color: MiftahColors.dangerBright,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: badgeRing, width: 2),
                ),
                child: Text(
                  count > 99 ? '99+' : '$count',
                  style: MiftahType.badge(color: Colors.white).copyWith(
                    fontSize: 10.5,
                    letterSpacing: 0,
                  ),
                ),
              ),
            )
          else if (showDot)
            PositionedDirectional(
              top: 7,
              end: 8,
              child: Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: MiftahColors.dangerBright,
                  border: Border.all(color: badgeRing, width: 2),
                ),
              ),
            ),
        ],
      ),
    );
    return tooltip == null
        ? button
        : Tooltip(message: tooltip!, child: button);
  }
}

/// Ink circle with the user's initials — the entry point to the profile area
/// on every root screen. Gradient variant is used on the Profile screen itself.
class MiftahAvatarButton extends StatelessWidget {
  const MiftahAvatarButton({
    super.key,
    required this.name,
    required this.onTap,
    this.size = 38,
    this.gradient = false,
    this.tooltip,
  });

  final String? name;
  final VoidCallback onTap;
  final double size;
  final bool gradient;
  final String? tooltip;

  static String initialsFor(String? name) {
    final trimmed = (name ?? '').trim();
    if (trimmed.isEmpty) return 'ME';
    final parts = trimmed
        .split(RegExp(r'\s+'))
        .where((p) => p.isNotEmpty)
        .toList();
    if (parts.isEmpty) return 'ME';
    if (parts.length == 1) {
      final one = parts.first;
      return (one.length >= 2 ? one.substring(0, 2) : one).toUpperCase();
    }
    return (parts.first.substring(0, 1) + parts[1].substring(0, 1))
        .toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    final button = GestureDetector(
      onTap: onTap,
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: gradient ? null : MiftahColors.ink,
          gradient: gradient ? MiftahGradients.goldCompact : null,
        ),
        child: Text(
          initialsFor(name),
          style: MiftahType.button(
            size: size * 0.34,
            color: gradient ? MiftahColors.ink : MiftahColors.brassLight,
          ),
        ),
      ),
    );
    return tooltip == null
        ? button
        : Tooltip(message: tooltip!, child: button);
  }
}

/// The standard root-screen header: an eyebrow line, a heavy title, and the
/// bell + avatar pair on the trailing side.
class MiftahScreenHeader extends StatelessWidget {
  const MiftahScreenHeader({
    super.key,
    this.eyebrow,
    this.title,
    this.leading,
    this.actions = const [],
    this.isAr = false,
  });

  /// Small mono/caps line above the title — the date on manager Today.
  final String? eyebrow;
  final String? title;

  /// Replaces eyebrow+title entirely (renter Home puts the wordmark here).
  final Widget? leading;
  final List<Widget> actions;
  final bool isAr;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context).colorScheme.surface,
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child:
                leading ??
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (eyebrow != null) ...[
                      Text(
                        eyebrow!,
                        style: MiftahType.mono(
                          size: 11,
                          color: MiftahColors.brassDeep,
                        ).copyWith(letterSpacing: isAr ? 0 : 0.88),
                      ),
                      const SizedBox(height: 6),
                    ],
                    if (title != null)
                      Text(
                        title!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: isAr
                            ? MiftahType.ar(size: 24, weight: FontWeight.w700)
                            : MiftahType.amount(size: 26),
                      ),
                  ],
                ),
          ),
          if (actions.isNotEmpty) const SizedBox(width: 12),
          for (var i = 0; i < actions.length; i++) ...[
            if (i > 0) const SizedBox(width: 9),
            actions[i],
          ],
        ],
      ),
    );
  }
}
