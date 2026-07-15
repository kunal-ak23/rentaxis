import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../auth/otp_errors.dart';

/// Code entry for guard OTP login.
///
/// [phone] is the already-normalized E.164 number the phone screen requested a
/// code for; the router refuses to build this screen without one, so it is
/// non-null here rather than defensively re-checked.
class OtpScreen extends ConsumerStatefulWidget {
  const OtpScreen({super.key, required this.phone});

  final String phone;

  @override
  ConsumerState<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends ConsumerState<OtpScreen> {
  /// Sits well inside `OtpLoginService.CODE_TTL_MINUTES` (5), so a guard who
  /// genuinely lost the message can ask again without waiting out the code they
  /// already hold.
  static const _resendCooldownSeconds = 60;

  final _codeController = TextEditingController();
  final _codeFocus = FocusNode();

  Timer? _cooldownTimer;
  int _secondsRemaining = 0;

  bool _isSubmitting = false;
  bool _isResending = false;

  /// Errors from resend (`/auth/otp/request`), which this screen owns.
  String? _resendError;

  /// Neutral confirmation after a resend. Hedged on purpose — see [_resend].
  String? _resendNotice;

  /// Whether the verify error held in [AuthState.error] is still current. A
  /// resend must not leave a stale "invalid code" banner sitting above a fresh
  /// code, and the core notifier only clears that error when a verify starts.
  bool _showAuthError = false;

  @override
  void initState() {
    super.initState();
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
    if (_isSubmitting) return; // auto-submit must not double-fire
    final code = _codeController.text;
    if (code.length != 6) return;

    setState(() {
      _isSubmitting = true;
      _resendError = null;
      _resendNotice = null;
      _showAuthError = true;
    });

    final success =
        await ref.read(authProvider.notifier).loginWithOtp(widget.phone, code);

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
    if (_isResending || _secondsRemaining > 0) return;

    setState(() {
      _isResending = true;
      _resendError = null;
      _resendNotice = null;
      _showAuthError = false;
    });

    try {
      await ref.read(authServiceProvider).requestOtp(widget.phone);
      if (!mounted) return;
      _startCooldown();
      setState(() {
        // Hedged, like the subtitle: a 200 from /auth/otp/request does not mean
        // a code went anywhere. Claiming "sent" would confirm the number belongs
        // to a guard — exactly what the endpoint's blanket 200 exists to avoid.
        _resendNotice =
            'If that number is registered, a new code is on its way.';
      });
    } on DioException catch (e) {
      if (!mounted) return;
      setState(() => _resendError = describeOtpRequestError(e));
      // A throttled resend is a wait, not a mistake: restart the cooldown so the
      // button does not invite the retry that would extend the throttle.
      if (isThrottled(e)) _startCooldown();
    } catch (_) {
      if (!mounted) return;
      setState(
          () => _resendError = 'Could not send a new code. Please try again.');
    } finally {
      if (mounted) setState(() => _isResending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final authError = ref.watch(authProvider).error;

    // Resend failures take precedence: they are the most recent thing the guard
    // did. Otherwise show the verify error, but only while it is still current.
    final errorMessage = _resendError ?? (_showAuthError ? authError : null);
    final canResend = _secondsRemaining <= 0 && !_isResending && !_isSubmitting;

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        backgroundColor: AppColors.background,
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'Enter your code',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.w600,
                      color: AppColors.navyDark,
                    ),
                  ),
                  const SizedBox(height: 8),
                  // Hedged wording, not a confirmation. /auth/otp/request answers
                  // 200 for any well-formed number, registered or not, so the app
                  // genuinely does not know whether a code was sent — and saying
                  // otherwise would turn the guard app into the enumeration
                  // oracle the backend declines to be.
                  const Text(
                    "If that number is registered, you'll get a code on WhatsApp.",
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 14,
                      color: AppColors.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 20),
                  _PhoneHeader(phone: widget.phone),
                  const SizedBox(height: 24),
                  TextField(
                    // /login stays mounted under this pushed route, so its phone
                    // field is in the tree too — the key keeps "the code field"
                    // unambiguous for tests and for focus traversal.
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
                    style: const TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 12,
                      color: AppColors.textPrimary,
                    ),
                    decoration: InputDecoration(
                      counterText: '',
                      hintText: '------',
                      hintStyle: TextStyle(
                        fontSize: 28,
                        letterSpacing: 12,
                        color: AppColors.textMuted.withValues(alpha: 0.4),
                      ),
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
                        borderSide: const BorderSide(
                            color: AppColors.primary, width: 1.5),
                      ),
                    ),
                    onChanged: (value) {
                      // Auto-submit: the code is a fixed 6 digits, so a Verify
                      // tap would be pure ceremony.
                      if (value.length == 6) _submit();
                    },
                  ),
                  if (_isSubmitting) ...[
                    const SizedBox(height: 20),
                    const Center(
                      child: SizedBox(
                        height: 22,
                        width: 22,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppColors.primary,
                        ),
                      ),
                    ),
                  ],
                  if (errorMessage != null) ...[
                    const SizedBox(height: 16),
                    _Banner(
                      message: errorMessage,
                      color: AppColors.danger,
                      icon: Icons.error_outline,
                    ),
                  ],
                  if (_resendNotice != null) ...[
                    const SizedBox(height: 16),
                    _Banner(
                      message: _resendNotice!,
                      color: AppColors.success,
                      icon: Icons.check_circle_outline,
                    ),
                  ],
                  const SizedBox(height: 20),
                  TextButton(
                    onPressed: canResend ? _resend : null,
                    child: _isResending
                        ? const SizedBox(
                            height: 16,
                            width: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(
                            _secondsRemaining > 0
                                ? 'Resend code in ${_secondsRemaining}s'
                                : 'Resend code',
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              color: canResend
                                  ? AppColors.primary
                                  : AppColors.textMuted,
                            ),
                          ),
                  ),
                  TextButton(
                    onPressed: _isSubmitting ? null : () => context.go('/login'),
                    child: const Text(
                      'Wrong number?',
                      style: TextStyle(fontSize: 13, color: AppColors.textMuted),
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

class _PhoneHeader extends StatelessWidget {
  const _PhoneHeader({required this.phone});

  final String phone;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.surface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.phone_outlined, size: 16, color: AppColors.textMuted),
          const SizedBox(width: 8),
          // The number is the one piece of state a guard can sanity-check
          // themselves, so keep it verbatim and legible.
          Text(
            phone,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
        ],
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({
    required this.message,
    required this.color,
    required this.icon,
  });

  final String message;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.2)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: color, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}
