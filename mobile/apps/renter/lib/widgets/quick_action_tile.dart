import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The home screen's quick-action tile: a 38px tinted box over a one-line
/// label. Shared by the fixed quick actions and the directory category row so
/// the two always look the same. [leading] fills the 38px box.
class QuickActionTile extends StatelessWidget {
  const QuickActionTile({
    super.key,
    required this.leading,
    required this.label,
    required this.route,
    this.maxLines = 1,
  });

  final Widget leading;
  final String label;
  final String route;

  /// 1 (the default) keeps the label on one line, scaled down to fit: the four
  /// fixed quick actions. More than 1 lets the label wrap at full size and
  /// ellipsise: directory category names, which run up to 60 characters.
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    return MiftahCard(
      padding: const EdgeInsets.fromLTRB(6, 13, 6, 11),
      radius: MiftahRadii.tile,
      onTap: () => context.push(route),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 38,
            height: 38,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: const Color(0xFFF4F1FC),
              borderRadius: BorderRadius.circular(11),
            ),
            child: leading,
          ),
          const SizedBox(height: 8),
          if (maxLines == 1)
            FittedBox(fit: BoxFit.scaleDown, child: _label(context))
          else
            _label(context),
        ],
      ),
    );
  }

  Widget _label(BuildContext context) {
    final style = context.isAr
        ? MiftahType.ar(
            size: 11,
            weight: FontWeight.w600,
            color: context.miftah.textPrimary,
          )
        : MiftahType.meta(
            color: context.miftah.textSecondary,
          ).copyWith(fontSize: 11, fontWeight: FontWeight.w600);
    return Text(
      label,
      maxLines: maxLines,
      overflow: maxLines == 1 ? null : TextOverflow.ellipsis,
      textAlign: TextAlign.center,
      style: style,
    );
  }
}
