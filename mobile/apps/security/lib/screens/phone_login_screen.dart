import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../auth/firebase_auth_errors.dart';
import '../auth/phone_format.dart';
import '../auth/phone_auth_service.dart';

/// Phone entry for Firebase SMS authentication.
class PhoneLoginScreen extends ConsumerStatefulWidget {
  const PhoneLoginScreen({super.key});

  @override
  ConsumerState<PhoneLoginScreen> createState() => _PhoneLoginScreenState();
}

class _PhoneLoginScreenState extends ConsumerState<PhoneLoginScreen> {
  final _formKey = GlobalKey<FormState>();

  late final TextEditingController _phoneController;
  late final String _phoneExample;

  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    final prefix = defaultPhonePrefixForCountry(
      WidgetsBinding.instance.platformDispatcher.locale.countryCode,
    );
    _phoneController = TextEditingController(text: prefix);
    _phoneExample = examplePhoneForPrefix(prefix);
  }

  @override
  void dispose() {
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _requestCode() async {
    // Client-side E.164 check first so Firebase receives the canonical number
    // stored on the guard record.
    if (!_formKey.currentState!.validate()) return;

    final phone = normalizePhone(_phoneController.text);

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      final result = await ref.read(phoneAuthServiceProvider).sendCode(phone);
      if (!mounted) return;
      switch (result) {
        case PhoneVerificationSession():
          context.push('/otp', extra: result);
        case AutomaticallyVerified(:final idToken):
          final success = await ref
              .read(authProvider.notifier)
              .loginWithFirebase(idToken);
          if (!success) {
            await ref.read(phoneAuthServiceProvider).signOut();
            if (mounted) {
              setState(() => _errorMessage = ref.read(authProvider).error);
            }
          }
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _errorMessage = describePhoneAuthError(error));
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
                        // alphabet our E.164 normalizer accepts.
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
                              'e.g. $_phoneExample';
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
                          disabledBackgroundColor: AppColors.primary.withValues(
                            alpha: 0.4,
                          ),
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
                      'By continuing, you agree to receive an SMS verification '
                      'code. Standard messaging rates may apply.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 11,
                        color: AppColors.textMuted,
                      ),
                    ),
                    const SizedBox(height: 16),
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
