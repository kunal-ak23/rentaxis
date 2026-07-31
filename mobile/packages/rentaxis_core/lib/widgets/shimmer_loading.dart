import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class ShimmerLoading extends StatefulWidget {
  final double width;
  final double height;
  final double borderRadius;

  const ShimmerLoading({
    super.key,
    this.width = double.infinity,
    required this.height,
    this.borderRadius = 8,
  });

  @override
  State<ShimmerLoading> createState() => _ShimmerLoadingState();
}

class _ShimmerLoadingState extends State<ShimmerLoading>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _animation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    )..repeat();

    _animation = Tween<double>(begin: -2.0, end: 2.0).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final base = m.surfaceAlt;
    // Composite the (semi-transparent) hairline border over the base tone so
    // the shimmer sweep reads as a visible highlight in both themes.
    final highlight = Color.alphaBlend(m.border, base);
    return AnimatedBuilder(
      animation: _animation,
      builder: (context, child) {
        return Container(
          width: widget.width,
          height: widget.height,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(widget.borderRadius),
            gradient: LinearGradient(
              begin: Alignment(_animation.value - 1, 0),
              end: Alignment(_animation.value + 1, 0),
              colors: [base, highlight, base],
              stops: const [0.0, 0.5, 1.0],
            ),
          ),
        );
      },
    );
  }
}

class CardShimmer extends StatelessWidget {
  const CardShimmer({super.key});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: m.border),
        boxShadow: m.isDark ? null : AppShadows.soft,
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ShimmerLoading(height: 16, width: 180, borderRadius: 4),
                    SizedBox(height: 8),
                    ShimmerLoading(height: 12, width: 120, borderRadius: 4),
                  ],
                ),
              ),
              ShimmerLoading(height: 24, width: 72, borderRadius: 12),
            ],
          ),
          SizedBox(height: 16),
          ShimmerLoading(height: 1),
          SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: ShimmerLoading(height: 14, borderRadius: 4),
              ),
              SizedBox(width: 24),
              Expanded(
                child: ShimmerLoading(height: 14, borderRadius: 4),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class ListShimmer extends StatelessWidget {
  final int itemCount;

  const ListShimmer({super.key, this.itemCount = 3});

  @override
  Widget build(BuildContext context) {
    // Skeletons can briefly render inside a tighter box than their content
    // (e.g. while a layout is still settling); clip instead of overflowing.
    return SingleChildScrollView(
      physics: const NeverScrollableScrollPhysics(),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: List.generate(
          itemCount,
          (index) => const CardShimmer(),
        ),
      ),
    );
  }
}
