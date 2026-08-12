import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

class AppColors {
  // Retuned to the Miftah 2026 tokens (see ui/miftah_tokens.dart). The field
  // names are kept so the ~500 existing call sites re-skin without edits;
  // MiftahColors is the source of truth for the values.
  static const primary = Color(0xFF12101A); // ink
  static const primaryLight = Color(0xFF2A2536); // inkSoft
  static const accent = Color(0xFFC79A3C); // brass
  static const accentDark = Color(0xFFA87A1E); // brassDeep
  static const accentLight = Color(0xFFFBF3E2); // brassTint
  static const gold400 = Color(0xFFE7C883); // brassPale
  static const goldMid = Color(0xFFC79A3C);
  // Darkest chrome tone (field name kept for compatibility with existing screens)
  static const navyDark = Color(0xFF12101A);
  static const background = Color(0xFFF6F5FA); // canvas
  static const surface = Color(0xFFFFFFFF);
  static const surface2 = Color(0xFFF4F2F9); // surfaceAlt
  static const border = Color(0xFFEDEAF4);
  static const borderStrong = Color(0xFFE6E3EE);
  static const divider = Color(0xFFF2EFF8);
  static const textPrimary = Color(0xFF12101A);
  static const textSecondary = Color(0xFF4A4358);
  static const textMuted = Color(0xFF8E88A0);
  static const success = Color(0xFF1E9E5A);
  static const successLight = Color(0xFFE7F3EC);
  static const warning = Color(0xFF8A6412);
  static const warningLight = Color(0xFFFBF3E2);
  static const danger = Color(0xFFC13B3B);
  static const dangerLight = Color(0xFFFDF0F0);
  // Neutral category icons (HVAC, bank) read as slate in the new system.
  static const info = Color(0xFF4A5B72);

  // Status colors
  static const statusPending = warning;
  static const statusActive = success;
  static const statusDraft = textMuted;
  static const statusOverdue = danger;
  static const statusCleared = success;
  static const statusBounced = danger;
  static const statusCollected = info;
}

/// Dark-mode raw palette: chrome stays #1B1B1B, content drops to #0D0D0D,
/// semantic colours lift so they pass AA on black.
class AppColorsDark {
  static const background = Color(0xFF0E0C14); // darkCanvas
  static const surface = Color(0xFF1B1826); // darkSurface
  static const surfaceDim = Color(0xFF151221);
  static const border = Color(0x1AFFFFFF);
  static const borderStrong = Color(0x2EFFFFFF);
  static const divider = Color(0x0FFFFFFF);
  static const textPrimary = Color(0xFFFFFFFF);
  static const textSecondary = Color(0xB3FFFFFF);
  static const textMuted = Color(0xFF8C86A0);
  static const success = Color(0xFF4FC98A);
  static const warning = Color(0xFFE3BE6E);
  static const danger = Color(0xFFE4736A);
}

/// Brightness-aware semantic tokens. Resolve via `context.miftah`.
@immutable
class LegacyMiftahColors extends ThemeExtension<LegacyMiftahColors> {
  /// App chrome (headers, hero cards) — near-black in both modes.
  final Color chrome;

  /// Hairline that separates chrome from content (gold-tinted in dark mode).
  final Color chromeBorder;
  final Color background;
  final Color surface;

  /// Slightly recessed surface (chips, secondary cards).
  final Color surfaceAlt;

  /// De-emphasised surface (closed/disabled cards).
  final Color surfaceDim;
  final Color border;
  final Color borderStrong;
  final Color divider;
  final Color textPrimary;
  final Color textSecondary;
  final Color textMuted;
  final Color success;
  final Color warning;
  final Color danger;

  /// Subtle fills behind semantic status pills.
  final Color successBg;
  final Color warningBg;
  final Color dangerBg;

  /// Gold border used around gold-accented outlines.
  final Color goldOutline;
  final bool isDark;

  const LegacyMiftahColors({
    required this.chrome,
    required this.chromeBorder,
    required this.background,
    required this.surface,
    required this.surfaceAlt,
    required this.surfaceDim,
    required this.border,
    required this.borderStrong,
    required this.divider,
    required this.textPrimary,
    required this.textSecondary,
    required this.textMuted,
    required this.success,
    required this.warning,
    required this.danger,
    required this.successBg,
    required this.warningBg,
    required this.dangerBg,
    required this.goldOutline,
    required this.isDark,
  });

  static const light = LegacyMiftahColors(
    chrome: AppColors.primary,
    chromeBorder: Color(0x24EEC046),
    background: AppColors.background,
    surface: AppColors.surface,
    surfaceAlt: AppColors.surface2,
    surfaceDim: AppColors.surface2,
    border: AppColors.border,
    borderStrong: AppColors.borderStrong,
    divider: AppColors.divider,
    textPrimary: AppColors.textPrimary,
    textSecondary: AppColors.textSecondary,
    textMuted: AppColors.textMuted,
    success: AppColors.success,
    warning: AppColors.warning,
    danger: AppColors.danger,
    successBg: Color(0x1A2F7B4C),
    warningBg: Color(0x1AB5781E),
    dangerBg: Color(0x1AB33A30),
    goldOutline: Color(0x66EEC046),
    isDark: false,
  );

  static const dark = LegacyMiftahColors(
    chrome: AppColors.primary,
    chromeBorder: Color(0x24EEC046),
    background: AppColorsDark.background,
    surface: AppColorsDark.surface,
    surfaceAlt: AppColorsDark.surfaceDim,
    surfaceDim: AppColorsDark.surfaceDim,
    border: AppColorsDark.border,
    borderStrong: AppColorsDark.borderStrong,
    divider: AppColorsDark.divider,
    textPrimary: AppColorsDark.textPrimary,
    textSecondary: AppColorsDark.textSecondary,
    textMuted: AppColorsDark.textMuted,
    success: AppColorsDark.success,
    warning: AppColorsDark.warning,
    danger: AppColorsDark.danger,
    successBg: Color(0x1F5FA97C),
    warningBg: Color(0x1FD9A24A),
    dangerBg: Color(0x24E4736A),
    goldOutline: Color(0x66EEC046),
    isDark: true,
  );

  @override
  LegacyMiftahColors copyWith({
    Color? chrome,
    Color? chromeBorder,
    Color? background,
    Color? surface,
    Color? surfaceAlt,
    Color? surfaceDim,
    Color? border,
    Color? borderStrong,
    Color? divider,
    Color? textPrimary,
    Color? textSecondary,
    Color? textMuted,
    Color? success,
    Color? warning,
    Color? danger,
    Color? successBg,
    Color? warningBg,
    Color? dangerBg,
    Color? goldOutline,
    bool? isDark,
  }) {
    return LegacyMiftahColors(
      chrome: chrome ?? this.chrome,
      chromeBorder: chromeBorder ?? this.chromeBorder,
      background: background ?? this.background,
      surface: surface ?? this.surface,
      surfaceAlt: surfaceAlt ?? this.surfaceAlt,
      surfaceDim: surfaceDim ?? this.surfaceDim,
      border: border ?? this.border,
      borderStrong: borderStrong ?? this.borderStrong,
      divider: divider ?? this.divider,
      textPrimary: textPrimary ?? this.textPrimary,
      textSecondary: textSecondary ?? this.textSecondary,
      textMuted: textMuted ?? this.textMuted,
      success: success ?? this.success,
      warning: warning ?? this.warning,
      danger: danger ?? this.danger,
      successBg: successBg ?? this.successBg,
      warningBg: warningBg ?? this.warningBg,
      dangerBg: dangerBg ?? this.dangerBg,
      goldOutline: goldOutline ?? this.goldOutline,
      isDark: isDark ?? this.isDark,
    );
  }

  @override
  LegacyMiftahColors lerp(ThemeExtension<LegacyMiftahColors>? other, double t) {
    if (other is! LegacyMiftahColors) return this;
    return LegacyMiftahColors(
      chrome: Color.lerp(chrome, other.chrome, t)!,
      chromeBorder: Color.lerp(chromeBorder, other.chromeBorder, t)!,
      background: Color.lerp(background, other.background, t)!,
      surface: Color.lerp(surface, other.surface, t)!,
      surfaceAlt: Color.lerp(surfaceAlt, other.surfaceAlt, t)!,
      surfaceDim: Color.lerp(surfaceDim, other.surfaceDim, t)!,
      border: Color.lerp(border, other.border, t)!,
      borderStrong: Color.lerp(borderStrong, other.borderStrong, t)!,
      divider: Color.lerp(divider, other.divider, t)!,
      textPrimary: Color.lerp(textPrimary, other.textPrimary, t)!,
      textSecondary: Color.lerp(textSecondary, other.textSecondary, t)!,
      textMuted: Color.lerp(textMuted, other.textMuted, t)!,
      success: Color.lerp(success, other.success, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
      danger: Color.lerp(danger, other.danger, t)!,
      successBg: Color.lerp(successBg, other.successBg, t)!,
      warningBg: Color.lerp(warningBg, other.warningBg, t)!,
      dangerBg: Color.lerp(dangerBg, other.dangerBg, t)!,
      goldOutline: Color.lerp(goldOutline, other.goldOutline, t)!,
      isDark: t < 0.5 ? isDark : other.isDark,
    );
  }
}

extension LegacyMiftahColorsX on BuildContext {
  LegacyMiftahColors get miftah =>
      Theme.of(this).extension<LegacyMiftahColors>() ?? LegacyMiftahColors.light;
}

class LegacyMiftahGradients {
  /// Primary gold CTA fill: bronze → gold → pale gold.
  static const gold = LinearGradient(
    begin: Alignment.centerLeft,
    end: Alignment.centerRight,
    colors: [AppColors.accentDark, AppColors.accent, AppColors.gold400],
    stops: [0.0, 0.62, 1.0],
  );

  /// Progress bar fill: bronze → gold.
  static const goldProgress = LinearGradient(
    colors: [AppColors.accentDark, AppColors.accent],
  );

  /// Dark hero card fill (dark mode): warm charcoal → near-black.
  static const heroDark = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF221E16), Color(0xFF141414)],
    stops: [0.0, 0.6],
  );
}

class AppShadows {
  static List<BoxShadow> get soft => [
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.04),
      blurRadius: 12,
      offset: const Offset(0, 4),
    ),
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.02),
      blurRadius: 4,
      offset: const Offset(0, 2),
    ),
  ];

  static List<BoxShadow> get medium => [
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.06),
      blurRadius: 20,
      offset: const Offset(0, 8),
    ),
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.03),
      blurRadius: 6,
      offset: const Offset(0, 3),
    ),
  ];

  static List<BoxShadow> get elevated => [
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.08),
      blurRadius: 32,
      offset: const Offset(0, 12),
    ),
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.04),
      blurRadius: 8,
      offset: const Offset(0, 4),
    ),
  ];

  /// Deep shadow under the hero payment card.
  static List<BoxShadow> get hero => [
    BoxShadow(
      color: const Color(0xFF111111).withValues(alpha: 0.24),
      blurRadius: 36,
      offset: const Offset(0, 18),
    ),
  ];
}

/// Legacy type helpers, now on Plus Jakarta Sans. Defaults retuned to the
/// redesign: display is heavy with negative tracking (the old Cinzel setting
/// tracked wide, which is what made headings feel dated), and overline keeps
/// tracking because section labels are the one place the new system allows it.
/// Prefer `MiftahType` in new code.
class LegacyMiftahType {
  static TextStyle display({
    double fontSize = 22,
    FontWeight fontWeight = FontWeight.w800,
    Color? color,
    double letterSpacing = -0.44,
    double? height,
  }) => GoogleFonts.plusJakartaSans(
    fontSize: fontSize,
    fontWeight: fontWeight,
    color: color,
    letterSpacing: letterSpacing,
    height: height,
  );

  /// Wide-tracked uppercase label (section headers, overlines).
  static TextStyle overline({
    double fontSize = 11,
    Color? color,
    double letterSpacing = 1.54,
    FontWeight fontWeight = FontWeight.w800,
  }) => GoogleFonts.plusJakartaSans(
    fontSize: fontSize,
    fontWeight: fontWeight,
    color: color,
    letterSpacing: letterSpacing,
  );
}

class AppTheme {
  static TextTheme _headingTextTheme(Color color) =>
      GoogleFonts.plusJakartaSansTextTheme(
        TextTheme(
          headlineLarge: TextStyle(
            fontSize: 26,
            fontWeight: FontWeight.w600,
            color: color,
            letterSpacing: 1.0,
          ),
          headlineMedium: TextStyle(
            fontSize: 21,
            fontWeight: FontWeight.w600,
            color: color,
            letterSpacing: 0.8,
          ),
          headlineSmall: TextStyle(
            fontSize: 17,
            fontWeight: FontWeight.w600,
            color: color,
            letterSpacing: 0.6,
          ),
        ),
      );

  static TextTheme _bodyTextTheme(LegacyMiftahColors c) =>
      GoogleFonts.plusJakartaSansTextTheme(
        TextTheme(
          titleLarge: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
            color: c.textPrimary,
          ),
          titleMedium: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: c.textPrimary,
          ),
          titleSmall: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: c.textSecondary,
          ),
          bodyLarge: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w400,
            color: c.textPrimary,
          ),
          bodyMedium: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w400,
            color: c.textPrimary,
          ),
          bodySmall: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w400,
            color: c.textSecondary,
          ),
          labelLarge: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: c.textPrimary,
          ),
          labelMedium: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w500,
            color: c.textSecondary,
            letterSpacing: 0.6,
          ),
          labelSmall: TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w500,
            color: c.textMuted,
            letterSpacing: 0.8,
          ),
        ),
      );

  static TextTheme _mergedTextTheme(LegacyMiftahColors c) {
    final body = _bodyTextTheme(c);
    final heading = _headingTextTheme(c.textPrimary);
    return body.copyWith(
      headlineLarge: heading.headlineLarge,
      headlineMedium: heading.headlineMedium,
      headlineSmall: heading.headlineSmall,
    );
  }

  static ThemeData get lightTheme => _theme(LegacyMiftahColors.light);

  static ThemeData get darkTheme => _theme(LegacyMiftahColors.dark);

  static ThemeData _theme(LegacyMiftahColors c) {
    final isDark = c.isDark;
    return ThemeData(
      useMaterial3: true,
      brightness: isDark ? Brightness.dark : Brightness.light,
      scaffoldBackgroundColor: c.background,
      extensions: [c],
      colorScheme: isDark
          ? ColorScheme.dark(
              primary: AppColors.accent,
              secondary: AppColors.accent,
              surface: c.surface,
              error: c.danger,
              onPrimary: AppColors.primary,
              onSecondary: AppColors.primary,
              onSurface: c.textPrimary,
            )
          : ColorScheme.light(
              primary: AppColors.primary,
              secondary: AppColors.accent,
              surface: c.surface,
              error: c.danger,
              onPrimary: Colors.white,
              onSecondary: AppColors.navyDark,
              onSurface: c.textPrimary,
            ),
      appBarTheme: AppBarTheme(
        backgroundColor: c.chrome,
        foregroundColor: isDark ? c.textPrimary : Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0.5,
        centerTitle: false,
        titleTextStyle: GoogleFonts.plusJakartaSans(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: 2.4,
          color: Colors.white,
        ),
        iconTheme: const IconThemeData(color: AppColors.accent),
      ),
      bottomNavigationBarTheme: BottomNavigationBarThemeData(
        backgroundColor: c.surface,
        selectedItemColor: AppColors.accent,
        unselectedItemColor: c.textMuted,
        type: BottomNavigationBarType.fixed,
        elevation: 0,
        selectedLabelStyle: GoogleFonts.plusJakartaSans(
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
        unselectedLabelStyle: GoogleFonts.plusJakartaSans(
          fontSize: 12,
          fontWeight: FontWeight.w400,
        ),
      ),
      cardTheme: CardThemeData(
        color: c.surface,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: c.border),
        ),
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: isDark ? AppColors.accent : AppColors.primary,
          foregroundColor: isDark ? AppColors.primary : AppColors.accent,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
          textStyle: GoogleFonts.plusJakartaSans(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            letterSpacing: 2.0,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: isDark ? AppColors.accent : AppColors.primary,
          side: BorderSide(color: isDark ? c.goldOutline : AppColors.primary),
          padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
          textStyle: GoogleFonts.plusJakartaSans(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            letterSpacing: 2.0,
          ),
        ),
      ),
      // Without this the caret falls back to ColorScheme.primary, which in
      // light mode is the near-black brand colour — invisible on the dark
      // chrome the login screens paint regardless of theme mode. Gold reads on
      // both backgrounds, so both modes use it.
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: AppColors.accent,
        selectionHandleColor: AppColors.accent,
        selectionColor: AppColors.accent.withValues(alpha: 0.32),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: c.surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: c.border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: c.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(
            color: isDark ? AppColors.accent : AppColors.primary,
            width: isDark ? 1.5 : 2,
          ),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: c.danger),
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 18,
          vertical: 16,
        ),
        hintStyle: GoogleFonts.plusJakartaSans(color: c.textMuted, fontSize: 14),
        labelStyle: GoogleFonts.plusJakartaSans(
          color: c.textSecondary,
          fontSize: 14,
        ),
        floatingLabelStyle: GoogleFonts.plusJakartaSans(
          color: isDark ? AppColors.accent : AppColors.primary,
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
      ),
      dividerTheme: DividerThemeData(
        color: c.divider,
        thickness: 0.5,
        space: 1,
      ),
      chipTheme: ChipThemeData(
        backgroundColor: c.background,
        labelStyle: GoogleFonts.plusJakartaSans(fontSize: 12, color: c.textPrimary),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: c.border),
        ),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: isDark ? AppColors.accent : AppColors.primary,
        foregroundColor: isDark ? AppColors.primary : AppColors.accent,
        elevation: 4,
        shape: const CircleBorder(),
      ),
      textTheme: _mergedTextTheme(c),
    );
  }
}
