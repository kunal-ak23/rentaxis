import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';

class VideoSplashScreen extends StatefulWidget {
  final VoidCallback onComplete;

  const VideoSplashScreen({super.key, required this.onComplete});

  @override
  State<VideoSplashScreen> createState() => _VideoSplashScreenState();
}

class _VideoSplashScreenState extends State<VideoSplashScreen>
    with TickerProviderStateMixin {
  late AnimationController _logoController;
  late AnimationController _titleController;
  late AnimationController _subtitleController;

  late Animation<double> _logoFade;
  late Animation<double> _logoScale;
  late Animation<double> _titleFade;
  late Animation<double> _subtitleFade;

  @override
  void initState() {
    super.initState();

    // Logo: fade + scale over 800ms
    _logoController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _logoFade = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _logoController, curve: Curves.easeOut),
    );
    _logoScale = Tween<double>(begin: 0.9, end: 1.0).animate(
      CurvedAnimation(parent: _logoController, curve: Curves.easeOutCubic),
    );

    // Title: fade in 500ms, starts 200ms after logo begins
    _titleController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );
    _titleFade = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _titleController, curve: Curves.easeOut),
    );

    // Subtitle: fade in 500ms, starts 200ms after title begins
    _subtitleController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );
    _subtitleFade = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _subtitleController, curve: Curves.easeOut),
    );

    // Sequence the animations
    _logoController.forward();
    Future.delayed(const Duration(milliseconds: 200), () {
      if (mounted) _titleController.forward();
    });
    Future.delayed(const Duration(milliseconds: 400), () {
      if (mounted) _subtitleController.forward();
    });

    // Navigate after 2.5 seconds
    Future.delayed(const Duration(milliseconds: 2500), () {
      if (mounted) widget.onComplete();
    });
  }

  @override
  void dispose() {
    _logoController.dispose();
    _titleController.dispose();
    _subtitleController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Square logo with fade + scale
            AnimatedBuilder(
              animation: _logoController,
              builder: (context, child) {
                return Opacity(
                  opacity: _logoFade.value,
                  child: Transform.scale(
                    scale: _logoScale.value,
                    child: Image.asset(
                      'assets/logo.png',
                      width: 140,
                      height: 140,
                    ),
                  ),
                );
              },
            ),
            const SizedBox(height: 20),

            // Horizontal logo with text (fades in after)
            AnimatedBuilder(
              animation: _titleController,
              builder: (context, child) {
                return Opacity(
                  opacity: _titleFade.value,
                  child: Image.asset(
                    'assets/logo_horizontal.png',
                    height: 36,
                    fit: BoxFit.contain,
                  ),
                );
              },
            ),
            const SizedBox(height: 12),

            // Subtitle
            AnimatedBuilder(
              animation: _subtitleController,
              builder: (context, child) {
                return Opacity(
                  opacity: _subtitleFade.value,
                  child: Text(
                    'Property Management',
                    style: GoogleFonts.josefinSans(
                      fontSize: 13,
                      fontWeight: FontWeight.w400,
                      color: AppColors.textMuted,
                      letterSpacing: 1.5,
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}
