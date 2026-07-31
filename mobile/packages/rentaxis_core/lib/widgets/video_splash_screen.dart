import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/l10n.dart';

/// Miftah splash (design 1a): gold lockup over #111 chrome, hairline divider,
/// "PROPERTY, PERFECTED" tagline, and an animated gold progress bar with a
/// "SECURING YOUR SESSION" caption. (Class name is historical — the original
/// splash played a video.)
class VideoSplashScreen extends StatefulWidget {
  final VoidCallback onComplete;

  const VideoSplashScreen({super.key, required this.onComplete});

  @override
  State<VideoSplashScreen> createState() => _VideoSplashScreenState();
}

class _VideoSplashScreenState extends State<VideoSplashScreen>
    with TickerProviderStateMixin {
  late final AnimationController _logoController;
  late final AnimationController _taglineController;
  late final AnimationController _progressController;

  late final Animation<double> _logoFade;
  late final Animation<double> _logoScale;
  late final Animation<double> _taglineFade;

  @override
  void initState() {
    super.initState();

    _logoController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _logoFade = CurvedAnimation(parent: _logoController, curve: Curves.easeOut);
    _logoScale = Tween<double>(begin: 0.92, end: 1.0).animate(
      CurvedAnimation(parent: _logoController, curve: Curves.easeOutCubic),
    );

    _taglineController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );
    _taglineFade = CurvedAnimation(
      parent: _taglineController,
      curve: Curves.easeOut,
    );

    // Gold bar sweeps the track over the splash's lifetime.
    _progressController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2100),
    );

    _logoController.forward();
    Future.delayed(const Duration(milliseconds: 300), () {
      if (mounted) _taglineController.forward();
    });
    Future.delayed(const Duration(milliseconds: 400), () {
      if (mounted) _progressController.forward();
    });

    Future.delayed(const Duration(milliseconds: 2500), () {
      if (mounted) widget.onComplete();
    });
  }

  @override
  void dispose() {
    _logoController.dispose();
    _taglineController.dispose();
    _progressController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.navyDark,
      body: Stack(
        children: [
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                AnimatedBuilder(
                  animation: _logoController,
                  builder: (context, child) => Opacity(
                    opacity: _logoFade.value,
                    child: Transform.scale(
                      scale: _logoScale.value,
                      child: Image.asset(
                        'assets/logo.png',
                        width: 214,
                        fit: BoxFit.contain,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 34),
                FadeTransition(
                  opacity: _taglineFade,
                  child: Column(
                    children: [
                      Container(
                        width: 64,
                        height: 1,
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              Color(0x00EEC046),
                              AppColors.accent,
                              Color(0x00EEC046),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 34),
                      context.isAr
                          ? Text(
                              'إدارة عقارية بأعلى المعايير',
                              style: GoogleFonts.notoNaskhArabic(
                                fontSize: 15,
                                color: AppColors.goldMid,
                              ),
                            )
                          : Text(
                              'PROPERTY, PERFECTED',
                              style: GoogleFonts.cinzel(
                                fontSize: 12,
                                letterSpacing: 5.0,
                                color: AppColors.goldMid,
                              ),
                            ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 76,
            child: Column(
              children: [
                SizedBox(
                  width: 132,
                  height: 2,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: ColoredBox(
                      color: Colors.white.withValues(alpha: 0.12),
                      child: AnimatedBuilder(
                        animation: _progressController,
                        builder: (context, child) => Align(
                          alignment: Alignment.centerLeft,
                          child: FractionallySizedBox(
                            widthFactor: 0.1 + 0.9 * _progressController.value,
                            child: const DecoratedBox(
                              decoration: BoxDecoration(
                                gradient: MiftahGradients.goldProgress,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 18),
                context.isAr
                    ? Text(
                        'جارٍ تأمين جلستك',
                        style: GoogleFonts.notoNaskhArabic(
                          fontSize: 12.5,
                          color: Colors.white.withValues(alpha: 0.4),
                        ),
                      )
                    : Text(
                        'SECURING YOUR SESSION',
                        style: GoogleFonts.josefinSans(
                          fontSize: 11,
                          letterSpacing: 3.4,
                          color: Colors.white.withValues(alpha: 0.35),
                        ),
                      ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
