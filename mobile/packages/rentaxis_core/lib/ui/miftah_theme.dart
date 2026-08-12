import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import 'miftah_tokens.dart';

/// Drop-in replacement for `AppTheme`. Point `MaterialApp.theme` at
/// `MiftahTheme.light` and most existing screens re-skin without edits:
/// scaffold background, app bar, cards, inputs, chips and buttons all pick up
/// the new geometry and palette from here.
class MiftahTheme {
  MiftahTheme._();

  static ThemeData get light => _build(Brightness.light);
  static ThemeData get dark => _build(Brightness.dark);

  static ThemeData _build(Brightness brightness) {
    final isDark = brightness == Brightness.dark;

    final canvas = isDark ? MiftahColors.darkCanvas : MiftahColors.canvas;
    final surface = isDark ? MiftahColors.darkSurface : MiftahColors.surface;
    final border = isDark ? MiftahColors.darkBorder : MiftahColors.border;
    final textPrimary =
        isDark ? MiftahColors.darkTextPrimary : MiftahColors.textPrimary;
    final textMuted =
        isDark ? MiftahColors.darkTextMuted : MiftahColors.textMuted;

    final base = ThemeData(brightness: brightness, useMaterial3: true);

    return base.copyWith(
      scaffoldBackgroundColor: canvas,
      canvasColor: canvas,
      splashFactory: InkSparkle.splashFactory,

      colorScheme: ColorScheme.fromSeed(
        seedColor: MiftahColors.brass,
        brightness: brightness,
      ).copyWith(
        primary: isDark ? MiftahColors.brassLight : MiftahColors.ink,
        onPrimary: isDark ? MiftahColors.ink : Colors.white,
        secondary: MiftahColors.brass,
        surface: surface,
        onSurface: textPrimary,
        error: MiftahColors.danger,
        outline: border,
      ),

      textTheme: GoogleFonts.plusJakartaSansTextTheme(base.textTheme).apply(
        bodyColor: textPrimary,
        displayColor: textPrimary,
      ),

      // App bar is part of the page now — white (or ink), flat, no tint.
      appBarTheme: AppBarTheme(
        backgroundColor: surface,
        foregroundColor: textPrimary,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleSpacing: MiftahSpacing.page,
        titleTextStyle: MiftahType.title(color: textPrimary),
        systemOverlayStyle: isDark
            ? SystemUiOverlayStyle.light
            : SystemUiOverlayStyle.dark,
        iconTheme: IconThemeData(color: textPrimary, size: 22),
      ),

      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          side: BorderSide(color: border),
          borderRadius: BorderRadius.circular(MiftahRadii.card),
        ),
      ),

      dividerTheme: DividerThemeData(
        color: isDark ? MiftahColors.darkBorder : const Color(0xFFF2EFF8),
        thickness: 1,
        space: 1,
      ),

      // Inputs read as cards, not boxes: filled, borderless, generous radius.
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
        hintStyle: MiftahType.body(size: 14, color: MiftahColors.textFaint),
        labelStyle: MiftahType.sectionLabel(color: textMuted),
        floatingLabelStyle: MiftahType.sectionLabel(color: textMuted),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
          borderSide: BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
          borderSide: BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
          borderSide: const BorderSide(color: MiftahColors.brass, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
          borderSide: const BorderSide(color: MiftahColors.danger),
        ),
      ),

      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: isDark ? MiftahColors.brassLight : MiftahColors.ink,
          foregroundColor: isDark ? MiftahColors.ink : Colors.white,
          minimumSize: const Size.fromHeight(54),
          elevation: 0,
          textStyle: MiftahType.button(),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(MiftahRadii.tile),
          ),
        ),
      ),

      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimary,
          minimumSize: const Size.fromHeight(54),
          side: BorderSide(color: border),
          textStyle: MiftahType.button(size: 15),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(MiftahRadii.tile),
          ),
        ),
      ),

      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: MiftahColors.brassDeep,
          textStyle: MiftahType.button(size: 12.5),
        ),
      ),

      chipTheme: ChipThemeData(
        backgroundColor: surface,
        selectedColor: MiftahColors.brassTint,
        side: BorderSide(color: border),
        labelStyle: MiftahType.meta(color: textPrimary),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(MiftahRadii.chip),
        ),
      ),

      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: canvas,
        surfaceTintColor: Colors.transparent,
        modalBarrierColor: MiftahColors.ink.withValues(alpha: 0.5),
        shape: const RoundedRectangleBorder(
          borderRadius:
              BorderRadius.vertical(top: Radius.circular(MiftahRadii.sheet)),
        ),
      ),

      snackBarTheme: SnackBarThemeData(
        backgroundColor: MiftahColors.ink,
        contentTextStyle: MiftahType.body(size: 13.5, color: Colors.white),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
        ),
      ),

      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: MiftahColors.brass,
        linearTrackColor: MiftahColors.border,
      ),

      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected)
              ? MiftahColors.brassLight
              : Colors.white,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected)
              ? MiftahColors.ink
              : MiftahColors.border,
        ),
        trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
      ),

      // Page transitions: fade for tab roots, slide-up for pushed detail.
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: FadeUpwardsPageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
        },
      ),

      // Screens not yet migrated read their colours through `context.miftah`,
      // which resolves this extension. Without it registered the getter falls
      // back to its light constant and those screens stay light in dark mode.
      // Retires once every screen reads from the theme directly.
      extensions: [
        isDark ? LegacyMiftahColors.dark : LegacyMiftahColors.light,
      ],
    );
  }
}
