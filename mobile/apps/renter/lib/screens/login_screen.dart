import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen>
    with SingleTickerProviderStateMixin {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _obscurePassword = true;
  bool _isSubmitting = false;
  double _buttonScale = 1.0;

  late AnimationController _fadeController;
  late Animation<double> _fadeIn;
  late Animation<Offset> _slideIn;

  @override
  void initState() {
    super.initState();
    _fadeController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _fadeIn = Tween<double>(
      begin: 0.0,
      end: 1.0,
    ).animate(CurvedAnimation(parent: _fadeController, curve: Curves.easeOut));
    _slideIn = Tween<Offset>(begin: const Offset(0, 0.05), end: Offset.zero)
        .animate(
          CurvedAnimation(parent: _fadeController, curve: Curves.easeOutCubic),
        );
    _fadeController.forward();
  }

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    _fadeController.dispose();
    super.dispose();
  }

  Future<void> _handleLogin() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSubmitting = true);

    final success = await ref
        .read(authProvider.notifier)
        .login(_emailController.text.trim(), _passwordController.text);

    if (!mounted) return;
    setState(() => _isSubmitting = false);

    if (success) {
      context.go('/');
    }
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        backgroundColor: AppColors.navyDark,
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: SlideTransition(
                position: _slideIn,
                child: FadeTransition(
                  opacity: _fadeIn,
                  child: Form(
                    key: _formKey,
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        // Language pill, top-end per design 1b.
                        Align(
                          alignment: AlignmentDirectional.centerEnd,
                          child: _LanguagePill(
                            language: ref.watch(appLanguageProvider),
                            onChanged: (lang) => ref
                                .read(appLanguageProvider.notifier)
                                .setLanguage(lang),
                          ),
                        ),
                        const SizedBox(height: 10),
                        // Brand mark (Arabic مفتاح)
                        Image.asset(
                          'assets/logo_mark.png',
                          width: 100,
                          height: 100,
                        ),
                        const SizedBox(height: 16),

                        // Horizontal logo with text
                        Image.asset(
                          'assets/logo_horizontal.png',
                          height: 32,
                          fit: BoxFit.contain,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          l.renterPortal,
                          style: l.ar
                              ? GoogleFonts.notoNaskhArabic(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w400,
                                  color: AppColors.gold400.withValues(
                                    alpha: 0.85,
                                  ),
                                )
                              : GoogleFonts.josefinSans(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w400,
                                  color: AppColors.gold400.withValues(
                                    alpha: 0.85,
                                  ),
                                  letterSpacing: 1.5,
                                ),
                        ),
                        const SizedBox(height: 48),

                        // Welcome text
                        Align(
                          alignment: AlignmentDirectional.centerStart,
                          child: Text(
                            l.welcomeBack,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 22,
                                    fontWeight: FontWeight.w600,
                                    color: AppColors.accent,
                                  )
                                : GoogleFonts.cinzel(
                                    fontSize: 22,
                                    fontWeight: FontWeight.w600,
                                    color: AppColors.accent,
                                  ),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Align(
                          alignment: AlignmentDirectional.centerStart,
                          child: Text(
                            l.signInSubtitle,
                            style:
                                (l.ar
                                ? GoogleFonts.notoNaskhArabic
                                : GoogleFonts.josefinSans)(
                                  fontSize: 14,
                                  color: AppColors.accentLight.withValues(
                                    alpha: 0.75,
                                  ),
                                ),
                          ),
                        ),
                        const SizedBox(height: 28),

                        // Email field
                        TextFormField(
                          controller: _emailController,
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.josefinSans)(
                                color: m.textPrimary,
                                fontSize: 15,
                              ),
                          decoration: _inputDecoration(
                            context,
                            l,
                            label: l.email,
                            icon: Icons.email_outlined,
                          ),
                          validator: (value) {
                            if (value == null || value.trim().isEmpty) {
                              return l.emailRequired;
                            }
                            if (!RegExp(
                              r'^[^@]+@[^@]+\.[^@]+$',
                            ).hasMatch(value.trim())) {
                              return l.emailInvalid;
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 16),

                        // Password field
                        TextFormField(
                          controller: _passwordController,
                          obscureText: _obscurePassword,
                          textInputAction: TextInputAction.done,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.josefinSans)(
                                color: m.textPrimary,
                                fontSize: 15,
                              ),
                          onFieldSubmitted: (_) => _handleLogin(),
                          decoration: _inputDecoration(
                            context,
                            l,
                            label: l.password,
                            icon: Icons.lock_outlined,
                            suffixIcon: IconButton(
                              icon: Icon(
                                _obscurePassword
                                    ? Icons.visibility_off_outlined
                                    : Icons.visibility_outlined,
                                color: m.textMuted,
                                size: 20,
                              ),
                              onPressed: () => setState(
                                () => _obscurePassword = !_obscurePassword,
                              ),
                            ),
                          ),
                          validator: (value) {
                            if (value == null || value.isEmpty) {
                              return l.passwordRequired;
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 24),

                        // Error message
                        AnimatedSwitcher(
                          duration: const Duration(milliseconds: 300),
                          transitionBuilder: (child, animation) {
                            return SlideTransition(
                              position:
                                  Tween<Offset>(
                                    begin: const Offset(0, -0.3),
                                    end: Offset.zero,
                                  ).animate(
                                    CurvedAnimation(
                                      parent: animation,
                                      curve: Curves.easeOutCubic,
                                    ),
                                  ),
                              child: FadeTransition(
                                opacity: animation,
                                child: child,
                              ),
                            );
                          },
                          child: authState.error != null
                              ? Container(
                                  key: ValueKey(authState.error),
                                  width: double.infinity,
                                  padding: const EdgeInsets.all(12),
                                  margin: const EdgeInsets.only(bottom: 16),
                                  decoration: BoxDecoration(
                                    color: AppColorsDark.danger.withValues(
                                      alpha: 0.12,
                                    ),
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(
                                      color: AppColorsDark.danger.withValues(
                                        alpha: 0.3,
                                      ),
                                    ),
                                  ),
                                  child: Row(
                                    children: [
                                      const Icon(
                                        Icons.error_outline,
                                        color: AppColorsDark.danger,
                                        size: 18,
                                      ),
                                      const SizedBox(width: 8),
                                      Expanded(
                                        child: Text(
                                          authState.error!,
                                          style:
                                              (l.ar
                                              ? GoogleFonts.notoNaskhArabic
                                              : GoogleFonts.josefinSans)(
                                                color: AppColorsDark.danger,
                                                fontSize: 13,
                                              ),
                                        ),
                                      ),
                                    ],
                                  ),
                                )
                              : const SizedBox.shrink(
                                  key: ValueKey('no_error'),
                                ),
                        ),

                        // Sign In button
                        GestureDetector(
                          onTapDown: (_) => setState(() => _buttonScale = 0.97),
                          onTapUp: (_) => setState(() => _buttonScale = 1.0),
                          onTapCancel: () => setState(() => _buttonScale = 1.0),
                          child: AnimatedScale(
                            scale: _buttonScale,
                            duration: const Duration(milliseconds: 150),
                            curve: Curves.easeOut,
                            child: SizedBox(
                              width: double.infinity,
                              height: 52,
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  gradient: MiftahGradients.gold,
                                  borderRadius: BorderRadius.circular(10),
                                  boxShadow: [
                                    BoxShadow(
                                      color: AppColors.accent.withValues(
                                        alpha: 0.25,
                                      ),
                                      blurRadius: 16,
                                      offset: const Offset(0, 6),
                                    ),
                                  ],
                                ),
                                child: ElevatedButton(
                                  onPressed: _isSubmitting
                                      ? null
                                      : _handleLogin,
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: Colors.transparent,
                                    shadowColor: Colors.transparent,
                                    disabledBackgroundColor: Colors.transparent,
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(10),
                                    ),
                                  ),
                                  child: _isSubmitting
                                      ? const _SigningInBar()
                                      : Text(
                                          l.signIn,
                                          style: l.ar
                                              ? GoogleFonts.notoNaskhArabic(
                                                  fontSize: 14.5,
                                                  fontWeight: FontWeight.w600,
                                                  color: AppColors.primary,
                                                )
                                              : GoogleFonts.josefinSans(
                                                  fontSize: 13.5,
                                                  fontWeight: FontWeight.w600,
                                                  color: AppColors.primary,
                                                  letterSpacing: 2.8,
                                                ),
                                        ),
                                ),
                              ),
                            ),
                          ),
                        ),

                        const SizedBox(height: 32),

                        // Footer
                        Text(
                          l.poweredBy,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.josefinSans)(
                                fontSize: 11,
                                color: AppColors.gold400.withValues(alpha: 0.6),
                              ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  InputDecoration _inputDecoration(
    BuildContext context,
    _L l, {
    required String label,
    required IconData icon,
    Widget? suffixIcon,
  }) {
    final m = context.miftah;
    return InputDecoration(
      labelText: label,
      labelStyle: (l.ar
          ? GoogleFonts.notoNaskhArabic
          : GoogleFonts.josefinSans)(color: m.textMuted, fontSize: 14),
      prefixIcon: Icon(icon, color: m.textMuted, size: 20),
      suffixIcon: suffixIcon,
      filled: true,
      fillColor: m.surface,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: m.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: m.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: AppColors.accent, width: 1.5),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: AppColorsDark.danger),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: AppColorsDark.danger, width: 1.5),
      ),
      errorStyle: (l.ar
          ? GoogleFonts.notoNaskhArabic
          : GoogleFonts.josefinSans)(color: AppColorsDark.danger, fontSize: 12),
    );
  }
}

/// Sign-in loading state: the design's thin sweeping bar motif (splash 1a),
/// rendered dark-on-gold inside the CTA with a tracked caption.
class _SigningInBar extends StatefulWidget {
  const _SigningInBar();

  @override
  State<_SigningInBar> createState() => _SigningInBarState();
}

class _SigningInBarState extends State<_SigningInBar>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: 72,
          height: 2,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(2),
            child: ColoredBox(
              color: AppColors.primary.withValues(alpha: 0.2),
              child: AnimatedBuilder(
                animation: _controller,
                builder: (context, child) {
                  // A 40%-wide dark segment sweeps left-to-right.
                  final t = _controller.value;
                  return Align(
                    alignment: Alignment(-1.0 + 2.8 * t, 0),
                    child: const FractionallySizedBox(
                      widthFactor: 0.4,
                      child: ColoredBox(color: AppColors.primary),
                    ),
                  );
                },
              ),
            ),
          ),
        ),
        const SizedBox(width: 14),
        Text(
          ar ? 'جارٍ تسجيل الدخول' : 'SIGNING IN',
          style: ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: AppColors.primary.withValues(alpha: 0.85),
                )
              : GoogleFonts.josefinSans(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 2.8,
                  color: AppColors.primary.withValues(alpha: 0.85),
                ),
        ),
      ],
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get renterPortal => ar ? 'بوابة المستأجر' : 'Renter Portal';
  String get welcomeBack => ar ? 'أهلاً بعودتك إلى مسكنك' : 'Welcome back';
  String get signInSubtitle =>
      ar ? 'سجّل الدخول لإدارة إيجارك' : 'Sign in to manage your rentals';
  String get email => ar ? 'البريد الإلكتروني' : 'Email';
  String get password => ar ? 'كلمة المرور' : 'Password';
  String get emailRequired =>
      ar ? 'البريد الإلكتروني مطلوب' : 'Email is required';
  String get emailInvalid =>
      ar ? 'أدخل بريدًا إلكترونيًا صالحًا' : 'Enter a valid email';
  String get passwordRequired =>
      ar ? 'كلمة المرور مطلوبة' : 'Password is required';
  String get signIn => ar ? 'تسجيل الدخول' : 'SIGN IN';
  String get poweredBy => ar ? 'بدعم من Miftah' : 'Powered by Miftah';
}

/// EN / ع pill on the dark login chrome, per design 1b.
class _LanguagePill extends StatelessWidget {
  final AppLanguage language;
  final ValueChanged<AppLanguage> onChanged;
  const _LanguagePill({required this.language, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    Widget option(AppLanguage value, Widget child) {
      final selected = language == value;
      return GestureDetector(
        onTap: () => onChanged(value),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(999),
            color: selected ? AppColors.accent : Colors.transparent,
          ),
          child: child,
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          option(
            AppLanguage.en,
            Text(
              'EN',
              style: GoogleFonts.josefinSans(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.4,
                color: language == AppLanguage.en
                    ? AppColors.primary
                    : Colors.white.withValues(alpha: 0.5),
              ),
            ),
          ),
          option(
            AppLanguage.ar,
            Text(
              'ع',
              style: GoogleFonts.notoNaskhArabic(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                height: 1.1,
                color: language == AppLanguage.ar
                    ? AppColors.primary
                    : Colors.white.withValues(alpha: 0.5),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
