import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../auth/firebase_auth_errors.dart';
import '../auth/phone_auth_service.dart';

/// Code entry for guard OTP login.
///
/// The verification session comes directly from Firebase's code-sent callback;
/// the router refuses to build this screen without it.
class OtpScreen extends ConsumerStatefulWidget {
  const OtpScreen({super.key, required this.session});

  final PhoneVerificationSession session;

  @override
  ConsumerState<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends ConsumerState<OtpScreen> {
  static const _resendCooldownSeconds = 60;

  final _codeController = TextEditingController();
  final _codeFocus = FocusNode();

  Timer? _cooldownTimer;
  int _secondsRemaining = 0;

  bool _isSubmitting = false;
  bool _isResending = false;

  late PhoneVerificationSession _session;

  String? _resendError;
  String? _resendNotice;
  String? _verifyError;

  /// Whether the verify error held in [AuthState.error] is still current. A
  /// resend must not leave a stale "invalid code" banner sitting above a fresh
  /// code, and the core notifier only clears that error when a verify starts.
  bool _showAuthError = false;

  @override
  void initState() {
    super.initState();
    _session = widget.session;
    // A code was just requested on the previous screen, so the cooldown starts
    // spent — not idle.
    _startCooldown();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _codeFocus.requestFocus();
    });
  }

  @override
  void dispose() {
    _cooldownTimer?.cancel();
    _codeController.dispose();
    _codeFocus.dispose();
    super.dispose();
  }

  void _startCooldown() {
    _cooldownTimer?.cancel();
    setState(() => _secondsRemaining = _resendCooldownSeconds);
    _cooldownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() => _secondsRemaining--);
      if (_secondsRemaining <= 0) timer.cancel();
    });
  }

  Future<void> _submit() async {
    final ar = context.isAr;
    if (_isSubmitting) return; // auto-submit must not double-fire
    final code = _codeController.text;
    if (code.length != 6) return;

    setState(() {
      _isSubmitting = true;
      _resendError = null;
      _resendNotice = null;
      _verifyError = null;
      _showAuthError = true;
    });

    var success = false;
    try {
      final idToken = await ref
          .read(phoneAuthServiceProvider)
          .verifyCode(_session, code);
      success = await ref
          .read(authProvider.notifier)
          .loginWithFirebase(idToken);
      if (!success) {
        await ref.read(phoneAuthServiceProvider).signOut();
      }
    } catch (error) {
      _verifyError = describePhoneAuthError(error, ar: ar);
    }

    if (!mounted) return;
    setState(() => _isSubmitting = false);

    if (success) {
      // Deliberately no context.go('/') here. routerProvider watches
      // authProvider, so the session landing rebuilds the router and its
      // redirect moves us off /otp on its own. Navigating manually would race
      // that redirect.
      return;
    }

    // Clear so the next attempt starts from an empty field rather than making
    // the guard select-all-delete a wrong code under time pressure.
    _codeController.clear();
    _codeFocus.requestFocus();
  }

  Future<void> _resend() async {
    final ar = context.isAr;
    if (_isResending || _secondsRemaining > 0) return;

    setState(() {
      _isResending = true;
      _resendError = null;
      _resendNotice = null;
      _showAuthError = false;
    });

    try {
      final result = await ref
          .read(phoneAuthServiceProvider)
          .sendCode(_session.phone, forceResendingToken: _session.resendToken);
      if (!mounted) return;
      switch (result) {
        case PhoneVerificationSession():
          _session = result;
          _startCooldown();
          setState(() => _resendNotice = _L(context.isAr).newCodeSent);
        case AutomaticallyVerified(:final idToken):
          final success = await ref
              .read(authProvider.notifier)
              .loginWithFirebase(idToken);
          if (!success) {
            await ref.read(phoneAuthServiceProvider).signOut();
            if (mounted) {
              setState(() => _resendError = ref.read(authProvider).error);
            }
          }
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _resendError = describePhoneAuthError(error, ar: ar));
      _startCooldown();
    } finally {
      if (mounted) setState(() => _isResending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final authError = ref.watch(authProvider).error;
    final l = _L(context.isAr);

    // Resend failures take precedence: they are the most recent thing the guard
    // did. Otherwise show the verify error, but only while it is still current.
    final errorMessage =
        _resendError ?? _verifyError ?? (_showAuthError ? authError : null);
    final canResend = _secondsRemaining <= 0 && !_isResending && !_isSubmitting;

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        backgroundColor: AppColors.navyDark,
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Center(
                    child: Image.asset(
                      'assets/logo_mark.png',
                      width: 64,
                      height: 64,
                    ),
                  ),
                  const SizedBox(height: 22),
                  Text(
                    l.enterCode,
                    textAlign: TextAlign.center,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 22,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accent,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 20,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accent,
                          ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    l.smsSubtitle,
                    textAlign: TextAlign.center,
                    style:
                        (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                          fontSize: 14,
                          color: Colors.white.withValues(alpha: 0.55),
                        ),
                  ),
                  const SizedBox(height: 18),
                  _PhoneHeader(phone: _session.phone),
                  const SizedBox(height: 28),
                  Stack(
                    alignment: Alignment.center,
                    children: [
                      AnimatedBuilder(
                        animation: Listenable.merge([
                          _codeController,
                          _codeFocus,
                        ]),
                        builder: (context, _) {
                          final text = _codeController.text;
                          return Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: List.generate(6, (index) {
                              final digit = index < text.length
                                  ? text[index]
                                  : '';
                              final isActive =
                                  index == text.length &&
                                  _codeFocus.hasFocus &&
                                  !_isSubmitting;
                              final hasError = errorMessage != null;
                              return _OtpBox(
                                digit: digit,
                                active: isActive,
                                hasError: hasError,
                              );
                            }),
                          );
                        },
                      ),
                      // Invisible field absorbing real input; the boxes above
                      // are pure presentation driven by its value.
                      Opacity(
                        opacity: 0,
                        child: TextField(
                          key: const Key('otpCodeField'),
                          controller: _codeController,
                          focusNode: _codeFocus,
                          keyboardType: TextInputType.number,
                          textAlign: TextAlign.center,
                          maxLength: 6,
                          enabled: !_isSubmitting,
                          autofillHints: const [AutofillHints.oneTimeCode],
                          inputFormatters: [
                            FilteringTextInputFormatter.digitsOnly,
                            LengthLimitingTextInputFormatter(6),
                          ],
                          decoration: const InputDecoration(
                            counterText: '',
                            border: InputBorder.none,
                          ),
                          onChanged: (value) {
                            // Auto-submit: the code is a fixed 6 digits, so a
                            // Verify tap would be pure ceremony.
                            if (value.length == 6) _submit();
                          },
                        ),
                      ),
                    ],
                  ),
                  if (_isSubmitting) ...[
                    const SizedBox(height: 20),
                    const Center(
                      child: SizedBox(
                        height: 22,
                        width: 22,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppColors.accent,
                        ),
                      ),
                    ),
                  ],
                  if (errorMessage != null) ...[
                    const SizedBox(height: 16),
                    _Banner(
                      message: errorMessage,
                      color: AppColorsDark.danger,
                      icon: Icons.error_outline,
                      ar: l.ar,
                    ),
                  ],
                  if (_resendNotice != null) ...[
                    const SizedBox(height: 16),
                    _Banner(
                      message: _resendNotice!,
                      color: AppColorsDark.success,
                      icon: Icons.check_circle_outline,
                      ar: l.ar,
                    ),
                  ],
                  const SizedBox(height: 20),
                  TextButton(
                    onPressed: canResend ? _resend : null,
                    child: _isResending
                        ? const SizedBox(
                            height: 16,
                            width: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppColors.accent,
                            ),
                          )
                        : Text(
                            _secondsRemaining > 0
                                ? l.resendIn(_secondsRemaining)
                                : l.resendCode,
                            style:
                                (l.ar
                                ? GoogleFonts.notoNaskhArabic
                                : GoogleFonts.josefinSans)(
                                  fontSize: 13.5,
                                  fontWeight: FontWeight.w600,
                                  color: canResend
                                      ? AppColors.accent
                                      : Colors.white.withValues(alpha: 0.35),
                                ),
                          ),
                  ),
                  TextButton(
                    onPressed: _isSubmitting
                        ? null
                        : () => context.go('/login'),
                    child: Text(
                      l.wrongNumber,
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.josefinSans)(
                            fontSize: 13,
                            color: Colors.white.withValues(alpha: 0.4),
                          ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// One digit slot in the OTP entry row: gold border when active, danger tint
/// on a rejected code, filled digit otherwise.
class _OtpBox extends StatelessWidget {
  const _OtpBox({
    required this.digit,
    required this.active,
    required this.hasError,
  });

  final String digit;
  final bool active;
  final bool hasError;

  @override
  Widget build(BuildContext context) {
    final filled = digit.isNotEmpty;
    final borderColor = hasError
        ? AppColorsDark.danger
        : active
        ? AppColors.accent
        : Colors.white.withValues(alpha: filled ? 0.3 : 0.16);

    return AnimatedContainer(
      duration: const Duration(milliseconds: 150),
      width: 44,
      height: 54,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: borderColor,
          width: active || hasError ? 1.5 : 1,
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        digit,
        style: GoogleFonts.cinzel(
          fontSize: 22,
          fontWeight: FontWeight.w600,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _PhoneHeader extends StatelessWidget {
  const _PhoneHeader({required this.phone});

  final String phone;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.05),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.accent.withValues(alpha: 0.2)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.phone_outlined,
              size: 16,
              color: Colors.white.withValues(alpha: 0.5),
            ),
            const SizedBox(width: 8),
            // The number is the one piece of state a guard can sanity-check
            // themselves, so keep it verbatim and legible.
            Text(
              phone,
              style: GoogleFonts.josefinSans(
                fontSize: 14.5,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({
    required this.message,
    required this.color,
    required this.icon,
    required this.ar,
  });

  final String message;
  final Color color;
  final IconData icon;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: (ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(color: color, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get enterCode => ar ? 'أدخل رمز التحقق' : 'Enter your code';
  String get smsSubtitle => ar
      ? 'أرسلنا رمز التحقق عبر رسالة نصية.'
      : 'We sent a verification code by SMS.';
  String get resendCode => ar ? 'إعادة إرسال الرمز' : 'Resend code';
  String resendIn(int seconds) =>
      ar ? 'إعادة الإرسال خلال $seconds ثانية' : 'Resend code in ${seconds}s';
  String get newCodeSent =>
      ar ? 'رمز جديد في طريقه إليك.' : 'A new SMS code is on its way.';
  String get wrongNumber => ar ? 'رقم خاطئ؟' : 'Wrong number?';
}
