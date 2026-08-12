import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../auth/firebase_auth_errors.dart';
import '../auth/phone_country.dart';
import '../auth/phone_number_field.dart';
import '../auth/phone_auth_service.dart';

/// Phone entry for Firebase SMS authentication.
class PhoneLoginScreen extends ConsumerStatefulWidget {
  const PhoneLoginScreen({super.key});

  @override
  ConsumerState<PhoneLoginScreen> createState() => _PhoneLoginScreenState();
}

class _PhoneLoginScreenState extends ConsumerState<PhoneLoginScreen> {
  final _formKey = GlobalKey<FormState>();

  final _phoneController = TextEditingController();
  late PhoneCountry _country;

  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _country = PhoneCountry.forLocale(
      WidgetsBinding.instance.platformDispatcher.locale.countryCode,
    );
  }

  @override
  void dispose() {
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _requestCode() async {
    final ar = context.isAr;
    // Client-side E.164 check first so Firebase receives the canonical number
    // stored on the guard record.
    if (!_formKey.currentState!.validate()) return;

    final phone = _country.toE164(stripTrunkPrefix(_phoneController.text));

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
      setState(() => _errorMessage = describePhoneAuthError(error, ar: ar));
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        backgroundColor: AppColors.navyDark,
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
                    const SizedBox(height: 10),
                    // Brand mark (Arabic مفتاح).
                    Center(
                      child: Image.asset(
                        'assets/logo_mark.png',
                        width: 90,
                        height: 90,
                      ),
                    ),
                    const SizedBox(height: 14),
                    // Horizontal logo with text.
                    Center(
                      child: Image.asset(
                        'assets/logo_horizontal.png',
                        height: 30,
                        fit: BoxFit.contain,
                      ),
                    ),
                    const SizedBox(height: 20),
                    // Tracked "GUARD CONSOLE" divider.
                    Row(
                      children: [
                        Expanded(
                          child: Divider(
                            color: AppColors.accent.withValues(alpha: 0.22),
                            height: 1,
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                          child: Text(
                            l.guardConsole,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 12,
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
                          child: Divider(
                            color: AppColors.accent.withValues(alpha: 0.22),
                            height: 1,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 34),

                    // Phone entry: country picker + national digits.
                    PhoneNumberField(
                      controller: _phoneController,
                      country: _country,
                      onCountryChanged: (c) => setState(() => _country = c),
                      ar: l.ar,
                      dark: true,
                      label: l.phoneNumber,
                      textInputAction: TextInputAction.done,
                      onSubmitted: (_) => _requestCode(),
                    ),
                    if (_errorMessage != null) ...[
                      const SizedBox(height: 16),
                      _ErrorBanner(message: _errorMessage!, ar: l.ar),
                    ],
                    const SizedBox(height: 30),

                    // Continue button.
                    SizedBox(
                      height: 52,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LegacyMiftahGradients.gold,
                          borderRadius: BorderRadius.circular(10),
                          boxShadow: [
                            BoxShadow(
                              color: AppColors.accent.withValues(alpha: 0.25),
                              blurRadius: 16,
                              offset: const Offset(0, 6),
                            ),
                          ],
                        ),
                        child: ElevatedButton(
                          onPressed: _isSubmitting ? null : _requestCode,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.transparent,
                            shadowColor: Colors.transparent,
                            disabledBackgroundColor: Colors.transparent,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(10),
                            ),
                          ),
                          child: _isSubmitting
                              ? const SizedBox(
                                  height: 20,
                                  width: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: AppColors.primary,
                                  ),
                                )
                              : Text(
                                  l.continueLabel,
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

                    const SizedBox(height: 28),
                    Text(
                      l.disclaimer,
                      textAlign: TextAlign.center,
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.josefinSans)(
                            fontSize: 11,
                            color: Colors.white.withValues(alpha: 0.35),
                          ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      l.poweredBy,
                      textAlign: TextAlign.center,
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
    );
  }
}

/// Shared inline error presentation for both auth screens, on dark chrome.
class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message, required this.ar});

  final String message;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColorsDark.danger.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColorsDark.danger.withValues(alpha: 0.3)),
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
              message,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    color: AppColorsDark.danger,
                    fontSize: 13,
                  ),
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

  String get guardConsole => ar ? 'بوابة الحراسة' : 'Guard Console';
  String get phoneNumber => ar ? 'رقم الهاتف' : 'Phone number';
  String get continueLabel => ar ? 'متابعة' : 'CONTINUE';
  String get phoneRequired =>
      ar ? 'رقم الهاتف مطلوب' : 'Phone number is required';
  String phoneInvalid(String example) => ar
      ? 'أدخل رقمًا كاملاً مع رمز الدولة، مثل $example'
      : 'Enter a full number with country code, e.g. $example';
  String get disclaimer => ar
      ? 'بالمتابعة، أنت توافق على استلام رمز تحقق عبر رسالة نصية. قد تُطبَّق '
            'رسوم الرسائل القياسية.'
      : 'By continuing, you agree to receive an SMS verification code. '
            'Standard messaging rates may apply.';
  String get poweredBy => ar ? 'بدعم من مفتاح' : 'Powered by Miftah';
}

/// EN / ع pill on the dark login chrome, per design.
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
