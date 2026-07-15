import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../auth/otp_errors.dart';
import '../auth/phone_format.dart';

/// Phone entry for guard OTP login.
///
/// Calls `POST /auth/otp/request` and forwards the normalized E.164 phone to
/// `/otp` as GoRouter `extra`. Deliberately does NOT report whether the number
/// belongs to a registered guard — see [_requestCode].
class PhoneLoginScreen extends ConsumerStatefulWidget {
  const PhoneLoginScreen({super.key});

  @override
  ConsumerState<PhoneLoginScreen> createState() => _PhoneLoginScreenState();
}

class _PhoneLoginScreenState extends ConsumerState<PhoneLoginScreen> {
  final _formKey = GlobalKey<FormState>();

  /// Guards are UAE-based (Dubai deployment), so the country code is prefilled
  /// rather than offered as a picker — one less thing to get wrong at a gate.
  final _phoneController = TextEditingController(text: '+971');

  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void dispose() {
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _requestCode() async {
    // Client-side E.164 check first: the backend answers 400 for a malformed
    // phone, and "must start with + and country code" is a better message than
    // whatever a failed round-trip would surface.
    if (!_formKey.currentState!.validate()) return;

    final phone = normalizePhone(_phoneController.text);

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      await ref.read(authServiceProvider).requestOtp(phone);
      if (!mounted) return;
      // Success here means "the server accepted the request", NOT "a code was
      // sent". /auth/otp/request answers 200 for an unregistered number too, on
      // purpose — it refuses to confirm which numbers belong to guards. The OTP
      // screen's copy is hedged to match ("if that number is registered..."); do
      // not "improve" it into a confirmation, that would hand an attacker the
      // enumeration oracle the backend is spending a silent 200 to deny.
      context.push('/otp', extra: phone);
    } on DioException catch (e) {
      if (!mounted) return;
      setState(() => _errorMessage = describeOtpRequestError(e));
    } catch (_) {
      if (!mounted) return;
      setState(() => _errorMessage = 'Something went wrong. Please try again.');
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        backgroundColor: AppColors.background,
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Form(
                key: _formKey,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Icon(
                      Icons.shield_outlined,
                      size: 56,
                      color: AppColors.primary,
                    ),
                    const SizedBox(height: 16),
                    const Text(
                      'Security',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w600,
                        color: AppColors.navyDark,
                      ),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      'Enter your registered phone number to get a login code.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 14,
                        color: AppColors.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 32),
                    TextFormField(
                      key: const Key('phoneField'),
                      controller: _phoneController,
                      keyboardType: TextInputType.phone,
                      textInputAction: TextInputAction.done,
                      autofillHints: const [AutofillHints.telephoneNumber],
                      inputFormatters: [
                        // '+', digits, spaces and hyphens only — the same
                        // alphabet the server's normalize() accepts.
                        FilteringTextInputFormatter.allow(RegExp(r'[\d\s+-]')),
                      ],
                      style: const TextStyle(
                        fontSize: 16,
                        color: AppColors.textPrimary,
                      ),
                      decoration: _inputDecoration(
                        label: 'Phone number',
                        icon: Icons.phone_outlined,
                      ),
                      onFieldSubmitted: (_) => _requestCode(),
                      validator: (value) {
                        final normalized = normalizePhone(value ?? '');
                        if (normalized.isEmpty) {
                          return 'Phone number is required';
                        }
                        if (!isValidE164(normalized)) {
                          return 'Enter a full number with country code, '
                              'e.g. +971501234567';
                        }
                        return null;
                      },
                    ),
                    if (_errorMessage != null) ...[
                      const SizedBox(height: 16),
                      _ErrorBanner(message: _errorMessage!),
                    ],
                    const SizedBox(height: 24),
                    SizedBox(
                      height: 52,
                      child: ElevatedButton(
                        onPressed: _isSubmitting ? null : _requestCode,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppColors.primary,
                          disabledBackgroundColor:
                              AppColors.primary.withValues(alpha: 0.4),
                          foregroundColor: Colors.white,
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
                                  color: Colors.white,
                                ),
                              )
                            : const Text(
                                'Continue',
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w600,
                                  letterSpacing: 0.5,
                                ),
                              ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    const Text(
                      'Powered by RentAxis',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 11,
                        color: AppColors.textMuted,
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
    );
  }
}

/// Shared inline error presentation for both auth screens.
class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.danger.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.danger.withValues(alpha: 0.2)),
      ),
      child: Row(
        children: [
          const Icon(Icons.error_outline, color: AppColors.danger, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(color: AppColors.danger, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

InputDecoration _inputDecoration({
  required String label,
  required IconData icon,
}) {
  return InputDecoration(
    labelText: label,
    labelStyle: const TextStyle(color: AppColors.textMuted, fontSize: 14),
    prefixIcon: Icon(icon, color: AppColors.textMuted, size: 20),
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
      borderSide: const BorderSide(color: AppColors.primary, width: 1.5),
    ),
    errorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: const BorderSide(color: AppColors.danger),
    ),
    focusedErrorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: const BorderSide(color: AppColors.danger, width: 1.5),
    ),
    errorStyle: const TextStyle(color: AppColors.danger, fontSize: 12),
  );
}
