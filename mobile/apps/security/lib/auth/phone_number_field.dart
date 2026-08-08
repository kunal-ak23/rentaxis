import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'phone_country.dart';

/// Country picker + national-number entry, composing to E.164.
///
/// Replaces free-typed country codes on both phone entry points in the guard
/// app. The guard picks 🇦🇪 or 🇮🇳 and keys only the local number, so the two
/// mistakes that used to reach the server as an opaque 400 — omitting the code
/// entirely, and deleting the `+` mid-edit — are no longer expressible.
///
/// The composed number is reported through [onChanged]; the widget owns no
/// controller for it, because the two hosts want different things (the login
/// screen submits it, the walk-in screen also looks it up as it is typed).
class PhoneNumberField extends StatelessWidget {
  const PhoneNumberField({
    super.key,
    required this.controller,
    required this.country,
    required this.onCountryChanged,
    required this.ar,
    this.dark = false,
    this.label,
    this.textInputAction,
    this.onSubmitted,
    this.suffixIcon,
    this.autofocus = false,
  });

  /// Holds the NATIONAL portion only — never the dial code.
  final TextEditingController controller;
  final PhoneCountry country;
  final ValueChanged<PhoneCountry> onCountryChanged;
  final bool ar;

  /// Dark-chrome hosts (the login screen) draw an underline on near-black;
  /// light hosts (walk-in) use the ambient filled decoration.
  final bool dark;
  final String? label;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onSubmitted;
  final Widget? suffixIcon;
  final bool autofocus;

  TextStyle _font({
    double size = 14.5,
    Color? color,
    double? spacing,
    FontWeight? weight,
  }) => (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
    fontSize: size,
    color: color,
    // Arabic must never be letter-spaced: it breaks glyph joining.
    letterSpacing: ar ? null : spacing,
    fontWeight: weight,
  );

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final onChrome = dark ? Colors.white : m.textPrimary;
    final muted = dark ? Colors.white.withValues(alpha: 0.45) : m.textMuted;

    final selector = _CountrySelector(
      country: country,
      onChanged: onCountryChanged,
      ar: ar,
      textColor: onChrome,
      mutedColor: muted,
      dark: dark,
    );

    final field = TextFormField(
      key: const Key('phoneNationalField'),
      controller: controller,
      autofocus: autofocus,
      keyboardType: TextInputType.phone,
      textInputAction: textInputAction,
      autofillHints: const [AutofillHints.telephoneNumberNational],
      // Digits only: the dial code is the picker's job, and a '+' typed here
      // would compose into a number with two of them.
      inputFormatters: [
        FilteringTextInputFormatter.digitsOnly,
        LengthLimitingTextInputFormatter(15),
      ],
      // Latin digits and LTR even in Arabic — a phone number is not prose, and
      // RTL would reorder the groups.
      textDirection: TextDirection.ltr,
      style: GoogleFonts.josefinSans(fontSize: 14.5, color: onChrome),
      cursorColor: AppColors.accent,
      onFieldSubmitted: onSubmitted,
      decoration: InputDecoration(
        isDense: true,
        hintText: country.exampleNational,
        hintStyle: GoogleFonts.josefinSans(
          fontSize: 14.5,
          color: muted.withValues(alpha: 0.6),
        ),
        suffixIcon: suffixIcon,
        // The border is drawn once around the whole row by the parent, so the
        // inner field contributes none of its own.
        filled: false,
        contentPadding: const EdgeInsets.symmetric(vertical: 9),
        border: InputBorder.none,
        enabledBorder: InputBorder.none,
        focusedBorder: InputBorder.none,
        errorBorder: InputBorder.none,
        focusedErrorBorder: InputBorder.none,
        // The row draws the error text, so the field must not reserve space
        // for its own or the two rules render twice.
        errorStyle: const TextStyle(height: 0, fontSize: 0),
      ),
      validator: (value) {
        final national = stripTrunkPrefix(value ?? '');
        if (national.isEmpty) return '';
        if (!country.nationalDigits.contains(national.length)) return '';
        return null;
      },
    );

    return FormField<String>(
      // Mirrors the inner field's rules so the row can own the error text.
      validator: (_) {
        final national = stripTrunkPrefix(controller.text);
        if (national.isEmpty) {
          return ar ? 'رقم الهاتف مطلوب' : 'Phone number is required';
        }
        if (!country.nationalDigits.contains(national.length)) {
          final digits = country.nationalDigits.join('/');
          return ar
              ? 'أدخل $digits أرقام بعد ${country.dialCode}، مثل '
                    '${country.exampleNational}'
              : 'Enter the $digits digits after ${country.dialCode}, '
                    'e.g. ${country.exampleNational}';
        }
        return null;
      },
      builder: (state) {
        final hasError = state.hasError;
        final line = hasError
            ? m.danger
            : (dark ? AppColors.accent.withValues(alpha: 0.3) : m.border);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (label != null) ...[
              Text(
                ar ? label! : label!.toUpperCase(),
                style: _font(size: ar ? 12 : 9, color: muted, spacing: 2.0),
              ),
              const SizedBox(height: 2),
            ],
            Container(
              decoration: dark
                  ? BoxDecoration(
                      border: Border(
                        bottom: BorderSide(
                          color: line,
                          width: hasError ? 1.5 : 1,
                        ),
                      ),
                    )
                  : BoxDecoration(
                      color: m.surface,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: line),
                    ),
              padding: dark
                  ? EdgeInsets.zero
                  : const EdgeInsetsDirectional.only(start: 4, end: 4),
              // Always LTR: '+971' precedes the number in every locale.
              child: Directionality(
                textDirection: TextDirection.ltr,
                child: Row(
                  children: [
                    selector,
                    Container(
                      width: 1,
                      height: 22,
                      margin: const EdgeInsets.symmetric(horizontal: 10),
                      color: muted.withValues(alpha: 0.35),
                    ),
                    Expanded(child: field),
                  ],
                ),
              ),
            ),
            if (hasError) ...[
              const SizedBox(height: 6),
              Text(state.errorText!, style: _font(size: 12, color: m.danger)),
            ],
          ],
        );
      },
    );
  }
}

class _CountrySelector extends StatelessWidget {
  const _CountrySelector({
    required this.country,
    required this.onChanged,
    required this.ar,
    required this.textColor,
    required this.mutedColor,
    required this.dark,
  });

  final PhoneCountry country;
  final ValueChanged<PhoneCountry> onChanged;
  final bool ar;
  final Color textColor;
  final Color mutedColor;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return DropdownButtonHideUnderline(
      child: DropdownButton<PhoneCountry>(
        key: const Key('phoneCountrySelector'),
        value: country,
        onChanged: (value) {
          if (value != null && value != country) onChanged(value);
        },
        isDense: true,
        // The menu otherwise inherits the closed button's width, which fits
        // only the flag and code — the country name then overflows.
        menuWidth: 200,
        borderRadius: BorderRadius.circular(12),
        dropdownColor: dark ? AppColors.navyDark : m.surface,
        focusColor: Colors.transparent,
        icon: Icon(Icons.arrow_drop_down, size: 20, color: mutedColor),
        // The closed button shows flag + code only; the open menu adds the
        // country name, which would otherwise crowd a narrow field.
        selectedItemBuilder: (_) => PhoneCountry.values
            .map(
              (c) => Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(c.flag, style: const TextStyle(fontSize: 17)),
                  const SizedBox(width: 6),
                  Text(
                    c.dialCode,
                    style: GoogleFonts.josefinSans(
                      fontSize: 14.5,
                      color: textColor,
                    ),
                  ),
                ],
              ),
            )
            .toList(),
        items: PhoneCountry.values
            .map(
              (c) => DropdownMenuItem<PhoneCountry>(
                value: c,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(c.flag, style: const TextStyle(fontSize: 17)),
                    const SizedBox(width: 8),
                    Text(
                      c.dialCode,
                      style: GoogleFonts.josefinSans(
                        fontSize: 14,
                        color: dark ? Colors.white : m.textPrimary,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        _name(c, ar),
                        overflow: TextOverflow.ellipsis,
                        style:
                            (ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.josefinSans)(
                              fontSize: 13,
                              color: dark
                                  ? Colors.white.withValues(alpha: 0.6)
                                  : m.textSecondary,
                            ),
                      ),
                    ),
                  ],
                ),
              ),
            )
            .toList(),
      ),
    );
  }

  static String _name(PhoneCountry c, bool ar) => switch (c.isoCode) {
    'IN' => ar ? 'الهند' : 'India',
    _ => ar ? 'الإمارات' : 'UAE',
  };
}
