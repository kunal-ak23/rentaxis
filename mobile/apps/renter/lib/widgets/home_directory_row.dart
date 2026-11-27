import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/promotion_provider.dart';
import 'quick_action_tile.dart';

/// Directory category tiles on the renter home, four per row.
///
/// Renders nothing while loading, on error, or when there are no categories,
/// and then leaves no gap: the row owns its own leading gap, like the penalty
/// strip, so home never shows a hole where it would have been. A dead
/// directory endpoint must never put an error on the home screen.
class HomeDirectoryRow extends ConsumerWidget {
  const HomeDirectoryRow({super.key});

  static const _perRow = 4;
  static const _spacing = 10.0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final all = ref.watch(directoryProvider).valueOrNull ?? const [];
    // An empty id would route to `/directory/`.
    final cats = all.where((c) => c.id.isNotEmpty).toList();
    if (cats.isEmpty) return const SizedBox.shrink();
    final isAr = context.isAr;

    final rows = <Widget>[];
    for (var start = 0; start < cats.length; start += _perRow) {
      if (start > 0) rows.add(const SizedBox(height: _spacing));
      final end = start + _perRow < cats.length ? start + _perRow : cats.length;
      rows.add(
        IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var i = 0; i < _perRow; i++) ...[
                if (i > 0) const SizedBox(width: _spacing),
                Expanded(
                  child: start + i < end
                      ? _CategoryTile(category: cats[start + i], isAr: isAr)
                      : const SizedBox.shrink(),
                ),
              ],
            ],
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: MiftahSpacing.gap),
      child: Column(children: rows),
    );
  }
}

class _CategoryTile extends StatelessWidget {
  const _CategoryTile({required this.category, required this.isAr});

  final DirectoryCategory category;
  final bool isAr;

  @override
  Widget build(BuildContext context) {
    const fallback = Icon(
      Icons.storefront_rounded,
      size: 20,
      color: MiftahColors.brassDeep,
    );
    final url = category.iconUrl;
    return QuickActionTile(
      leading: (url == null || url.isEmpty)
          ? fallback
          : Image.network(
              url,
              width: 24,
              height: 24,
              fit: BoxFit.contain,
              errorBuilder: (_, _, _) => fallback,
            ),
      label: category.name(isAr),
      route: '/directory/${category.id}',
      maxLines: 2,
    );
  }
}
