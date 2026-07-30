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
    _fadeIn = Tween<double>(begin: 0.0, end: 1.0).animate(
      CurvedAnimation(parent: _fadeController, curve: Curves.easeOut),
    );
    _slideIn = Tween<Offset>(
      begin: const Offset(0, 0.05),
      end: Offset.zero,
    ).animate(
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

    final success = await ref.read(authProvider.notifier).login(
          _emailController.text.trim(),
          _passwordController.text,
        );

    if (!mounted) return;
    setState(() => _isSubmitting = false);

    if (success) {
      context.go('/');
    }
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);

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
                          'Renter Portal',
                          style: GoogleFonts.josefinSans(
                            fontSize: 13,
                            fontWeight: FontWeight.w400,
                            color: AppColors.gold400.withValues(alpha: 0.85),
                            letterSpacing: 1.5,
                          ),
                        ),
                        const SizedBox(height: 48),

                        // Welcome text
                        Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            'Welcome back',
                            style: GoogleFonts.cinzel(
                              fontSize: 22,
                              fontWeight: FontWeight.w600,
                              color: AppColors.accent,
                            ),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            'Sign in to manage your rentals',
                            style: GoogleFonts.josefinSans(
                              fontSize: 14,
                              color: AppColors.accentLight.withValues(alpha: 0.75),
                            ),
                          ),
                        ),
                        const SizedBox(height: 28),

                        // Email field
                        TextFormField(
                          controller: _emailController,
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
                          style: GoogleFonts.josefinSans(
                            color: AppColors.textPrimary,
                            fontSize: 15,
                          ),
                          decoration: _inputDecoration(
                            label: 'Email',
                            icon: Icons.email_outlined,
                          ),
                          validator: (value) {
                            if (value == null || value.trim().isEmpty) {
                              return 'Email is required';
                            }
                            if (!RegExp(r'^[^@]+@[^@]+\.[^@]+$')
                                .hasMatch(value.trim())) {
                              return 'Enter a valid email';
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
                          style: GoogleFonts.josefinSans(
                            color: AppColors.textPrimary,
                            fontSize: 15,
                          ),
                          onFieldSubmitted: (_) => _handleLogin(),
                          decoration: _inputDecoration(
                            label: 'Password',
                            icon: Icons.lock_outlined,
                            suffixIcon: IconButton(
                              icon: Icon(
                                _obscurePassword
                                    ? Icons.visibility_off_outlined
                                    : Icons.visibility_outlined,
                                color: AppColors.textMuted,
                                size: 20,
                              ),
                              onPressed: () => setState(
                                  () => _obscurePassword = !_obscurePassword),
                            ),
                          ),
                          validator: (value) {
                            if (value == null || value.isEmpty) {
                              return 'Password is required';
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
                              position: Tween<Offset>(
                                begin: const Offset(0, -0.3),
                                end: Offset.zero,
                              ).animate(CurvedAnimation(
                                parent: animation,
                                curve: Curves.easeOutCubic,
                              )),
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
                                    color:
                                        AppColors.danger.withValues(alpha: 0.08),
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(
                                      color: AppColors.danger
                                          .withValues(alpha: 0.2),
                                    ),
                                  ),
                                  child: Row(
                                    children: [
                                      const Icon(Icons.error_outline,
                                          color: AppColors.danger, size: 18),
                                      const SizedBox(width: 8),
                                      Expanded(
                                        child: Text(
                                          authState.error!,
                                          style: GoogleFonts.josefinSans(
                                            color: AppColors.danger,
                                            fontSize: 13,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                )
                              : const SizedBox.shrink(
                                  key: ValueKey('no_error')),
                        ),

                        // Sign In button
                        GestureDetector(
                          onTapDown: (_) =>
                              setState(() => _buttonScale = 0.97),
                          onTapUp: (_) =>
                              setState(() => _buttonScale = 1.0),
                          onTapCancel: () =>
                              setState(() => _buttonScale = 1.0),
                          child: AnimatedScale(
                            scale: _buttonScale,
                            duration: const Duration(milliseconds: 150),
                            curve: Curves.easeOut,
                            child: SizedBox(
                              width: double.infinity,
                              height: 52,
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    colors: [
                                      AppColors.accentDark,
                                      AppColors.accent,
                                    ],
                                  ),
                                  borderRadius: BorderRadius.circular(14),
                                  boxShadow: [
                                    BoxShadow(
                                      color: AppColors.accent
                                          .withValues(alpha: 0.25),
                                      blurRadius: 16,
                                      offset: const Offset(0, 6),
                                    ),
                                  ],
                                ),
                                child: ElevatedButton(
                                  onPressed:
                                      _isSubmitting ? null : _handleLogin,
                                  style: ElevatedButton.styleFrom(
                                    backgroundColor: Colors.transparent,
                                    shadowColor: Colors.transparent,
                                    disabledBackgroundColor: Colors.transparent,
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(14),
                                    ),
                                  ),
                                  child: _isSubmitting
                                      ? const SizedBox(
                                          height: 20,
                                          width: 20,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                            color: AppColors.navyDark,
                                          ),
                                        )
                                      : Text(
                                          'Sign In',
                                          style: GoogleFonts.josefinSans(
                                            fontSize: 16,
                                            fontWeight: FontWeight.w600,
                                            color: AppColors.navyDark,
                                            letterSpacing: 0.5,
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
                          'Powered by Miftah',
                          style: GoogleFonts.josefinSans(
                            fontSize: 11,
                            color: AppColors.gold400.withValues(alpha: 0.6),
                            letterSpacing: 0.5,
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

  InputDecoration _inputDecoration({
    required String label,
    required IconData icon,
    Widget? suffixIcon,
  }) {
    return InputDecoration(
      labelText: label,
      labelStyle: GoogleFonts.josefinSans(
        color: AppColors.textMuted,
        fontSize: 14,
      ),
      prefixIcon: Icon(icon, color: AppColors.textMuted, size: 20),
      suffixIcon: suffixIcon,
      filled: true,
      fillColor: Colors.white,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: AppColors.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: AppColors.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: AppColors.accent, width: 1.5),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: AppColors.danger),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: AppColors.danger, width: 1.5),
      ),
      errorStyle:
          GoogleFonts.josefinSans(color: AppColors.dangerLight, fontSize: 12),
    );
  }
}
