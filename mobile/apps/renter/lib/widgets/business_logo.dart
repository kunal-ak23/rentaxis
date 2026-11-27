import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// A business logo in a rounded box, with the storefront icon when there is
/// no logo or it fails to load. Shared by the directory
/// category list and the business page.
class BusinessLogo extends StatelessWidget {
  const BusinessLogo({super.key, required this.url, this.size = 44});

  final String? url;
  final double size;

  @override
  Widget build(BuildContext context) {
    final fallback = Icon(
      Icons.storefront_rounded,
      size: size * 0.5,
      color: MiftahColors.brassDeep,
    );
    final src = url;
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        color: const Color(0xFFF4F1FC),
        borderRadius: BorderRadius.circular(size * 0.28),
      ),
      child: (src == null || src.isEmpty)
          ? fallback
          : Image.network(
              src,
              width: size,
              height: size,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => fallback,
            ),
    );
  }
}
