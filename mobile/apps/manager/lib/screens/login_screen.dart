import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Admin console login, per design 1j: near-black chrome (#111), Arabic
/// مفتاح mark over the MIFTAH wordmark, tracked "ADMIN CONSOLE" divider,
/// underline-style fields, gold gradient sign-in.
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

  void _handleForgotPassword() {
    final l = _L(context.isAr);
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(l.forgotPasswordHint)));
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);
    final l = _L(context.isAr);

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        backgroundColor: const Color(0xFF111111),
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
                        // Language pill, top-end.
                        Align(
                          alignment: AlignmentDirectional.centerEnd,
                          child: _LanguagePill(
                            language: ref.watch(appLanguageProvider),
                            onChanged: (lang) => ref
                                .read(appLanguageProvider.notifier)
                                .setLanguage(lang),
                          ),
                        ),
                        const SizedBox(height: 16),

                        // Brand mark (Arabic مفتاح)
                        Image.asset(
                          'assets/logo_mark.png',
                          width: 150,
                          fit: BoxFit.contain,
                        ),
                        const SizedBox(height: 16),

                        // MIFTAH wordmark
                        Image.asset(
                          'assets/logo_horizontal.png',
                          width: 196,
                          fit: BoxFit.contain,
                        ),
                        const SizedBox(height: 20),

                        // "ADMIN CONSOLE" tracked divider
                        Row(
                          children: [
                            Expanded(
                              child: Container(
                                height: 1,
                                color: AppColors.accent.withValues(alpha: 0.22),
                              ),
                            ),
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                              ),
                              child: Text(
                                l.adminConsole,
                                style: l.ar
                                    ? GoogleFonts.notoNaskhArabic(
                                        fontSize: 11,
                                        color: AppColors.goldMid,
                                      )
                                    : GoogleFonts.josefinSans(
                                        fontSize: 9.5,
                                        letterSpacing: 3.4,
                                        color: AppColors.goldMid,
                                      ),
                              ),
                            ),
                            Expanded(
                              child: Container(
                                height: 1,
                                color: AppColors.accent.withValues(alpha: 0.22),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 38),

                        // Email field (underline style)
                        _UnderlineField(
                          controller: _emailController,
                          label: l.workEmail,
                          ar: l.ar,
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
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
                        const SizedBox(height: 22),

                        // Password field (underline style, "Show" toggle)
                        _UnderlineField(
                          controller: _passwordController,
                          label: l.password,
                          ar: l.ar,
                          obscureText: _obscurePassword,
                          textInputAction: TextInputAction.done,
                          onFieldSubmitted: (_) => _handleLogin(),
                          trailing: GestureDetector(
                            onTap: () => setState(
                              () => _obscurePassword = !_obscurePassword,
                            ),
                            child: Text(
                              _obscurePassword ? l.show : l.hide,
                              style: l.ar
                                  ? GoogleFonts.notoNaskhArabic(
                                      fontSize: 11,
                                      color: AppColors.goldMid,
                                    )
                                  : GoogleFonts.josefinSans(
                                      fontSize: 10,
                                      letterSpacing: 1.4,
                                      color: AppColors.goldMid,
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
                        const SizedBox(height: 6),

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
                                  borderRadius: BorderRadius.circular(12),
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
                                      borderRadius: BorderRadius.circular(12),
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
                        const SizedBox(height: 16),

                        // Forgot password
                        TextButton(
                          onPressed: _handleForgotPassword,
                          child: Text(
                            l.forgotPassword,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 12.5,
                                    color: Colors.white.withValues(alpha: 0.45),
                                  )
                                : GoogleFonts.josefinSans(
                                    fontSize: 11.5,
                                    color: Colors.white.withValues(alpha: 0.45),
                                  ),
                          ),
                        ),

                        const SizedBox(height: 16),

                        // Footer
                        Text(
                          l.footer,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.josefinSans)(
                                fontSize: 9.5,
                                letterSpacing: l.ar ? 0 : 2.0,
                                color: Colors.white.withValues(alpha: 0.28),
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
}

/// Sign-in loading state: a thin sweeping bar rendered dark-on-gold inside
/// the CTA with a tracked caption.
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

/// Underline-style field per design 1j: uppercase tracked label above a
/// hairline-bottom-bordered input, optional trailing action (e.g. "Show").
class _UnderlineField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final bool ar;
  final bool obscureText;
  final TextInputType? keyboardType;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onFieldSubmitted;
  final String? Function(String?)? validator;
  final Widget? trailing;

  const _UnderlineField({
    required this.controller,
    required this.label,
    required this.ar,
    this.obscureText = false,
    this.keyboardType,
    this.textInputAction,
    this.onFieldSubmitted,
    this.validator,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 11,
                  color: Colors.white.withValues(alpha: 0.4),
                )
              : GoogleFonts.josefinSans(
                  fontSize: 9,
                  letterSpacing: 2.0,
                  color: Colors.white.withValues(alpha: 0.4),
                ),
        ),
        Row(
          children: [
            Expanded(
              child: TextFormField(
                controller: controller,
                obscureText: obscureText,
                keyboardType: keyboardType,
                textInputAction: textInputAction,
                onFieldSubmitted: onFieldSubmitted,
                validator: validator,
                style: (ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts
                          .josefinSans)(fontSize: 14.5, color: Colors.white),
                decoration: InputDecoration(
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(vertical: 9),
                  border: UnderlineInputBorder(
                    borderSide: BorderSide(
                      color: AppColors.accent.withValues(alpha: 0.3),
                    ),
                  ),
                  enabledBorder: UnderlineInputBorder(
                    borderSide: BorderSide(
                      color: AppColors.accent.withValues(alpha: 0.3),
                    ),
                  ),
                  focusedBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: AppColors.accent, width: 1.5),
                  ),
                  errorBorder: UnderlineInputBorder(
                    borderSide: BorderSide(color: AppColorsDark.danger),
                  ),
                  focusedErrorBorder: UnderlineInputBorder(
                    borderSide: BorderSide(
                      color: AppColorsDark.danger,
                      width: 1.5,
                    ),
                  ),
                  errorStyle:
                      (ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        color: AppColorsDark.danger,
                        fontSize: 11.5,
                      ),
                ),
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 10), trailing!],
          ],
        ),
      ],
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get adminConsole => ar ? 'وحدة تحكم الإدارة' : 'Admin Console';
  String get workEmail => ar ? 'البريد الإلكتروني للعمل' : 'Work email';
  String get password => ar ? 'كلمة المرور' : 'Password';
  String get show => ar ? 'إظهار' : 'Show';
  String get hide => ar ? 'إخفاء' : 'Hide';
  String get emailRequired =>
      ar ? 'البريد الإلكتروني مطلوب' : 'Email is required';
  String get emailInvalid =>
      ar ? 'أدخل بريدًا إلكترونيًا صالحًا' : 'Enter a valid email';
  String get passwordRequired =>
      ar ? 'كلمة المرور مطلوبة' : 'Password is required';
  String get signIn => ar ? 'تسجيل الدخول' : 'SIGN IN';
  String get forgotPassword => ar ? 'نسيت كلمة المرور؟' : 'Forgot password?';
  String get forgotPasswordHint => ar
      ? 'يرجى التواصل مع مسؤول حسابك لإعادة تعيين كلمة المرور'
      : 'Contact your account admin to reset your password';
  String get footer =>
      ar ? 'مفتاح · miftah.ae/admin' : 'MIFTAH · MIFTAH.AE/ADMIN';
}

/// EN / ع pill on the dark login chrome, matching the renter login pattern.
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
