import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class LoadingOverlay extends StatelessWidget {
  final bool isLoading;
  final Widget child;

  const LoadingOverlay({
    super.key,
    required this.isLoading,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Stack(
      children: [
        child,
        if (isLoading)
          Container(
            color: Colors.black.withValues(alpha: m.isDark ? 0.5 : 0.26),
            child: Center(
              child: CircularProgressIndicator(
                color: m.isDark ? AppColors.accent : AppColors.primary,
              ),
            ),
          ),
      ],
    );
  }
}
