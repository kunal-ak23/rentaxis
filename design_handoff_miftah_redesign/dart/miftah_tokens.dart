import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Miftah 2026 design tokens.
///
/// Drop this in `packages/rentaxis_core/lib/ui/` and re-export it from
/// `rentaxis_core.dart`. It replaces the navy/gold chrome palette with the
/// ink/paper/brass system: one dark ink, a light lavender-neutral canvas,
/// white cards, and a single gold gradient reserved for money and primary
/// action. Nothing else is allowed to be gold.
class MiftahColors {
  MiftahColors._();

  // Ink — headers, primary buttons, selected chips, nav icons when active.
  static const ink = Color(0xFF12101A);
  static const inkSoft = Color(0xFF2A2536);

  // Canvas & surfaces.
  static const canvas = Color(0xFFF6F5FA);
  static const surface = Color(0xFFFFFFFF);
  static const surfaceAlt = Color(0xFFF4F2F9);
  static const border = Color(0xFFEDEAF4);
  static const borderStrong = Color(0xFFE6E3EE);

  // Text.
  static const textPrimary = Color(0xFF12101A);
  static const textSecondary = Color(0xFF4A4358);
  static const textMuted = Color(0xFF8E88A0);
  static const textFaint = Color(0xFFA39CB8);

  // Brass — money, primary accent. Never as a large flat fill except the
  // gradient below.
  static const brass = Color(0xFFC79A3C);
  static const brassDeep = Color(0xFFA87A1E);
  static const brassLight = Color(0xFFE3BE6E);
  static const brassPale = Color(0xFFE7C883);
  static const brassTint = Color(0xFFFBF3E2); // chip / badge background
  static const brassTintBorder = Color(0xFFEBD7A8);

  // Semantic.
  static const success = Color(0xFF1E9E5A);
  static const successDeep = Color(0xFF1E7A4A);
  static const successTint = Color(0xFFE7F3EC);
  static const danger = Color(0xFFC13B3B);
  static const dangerBright = Color(0xFFD64545);
  static const dangerTint = Color(0xFFFDF0F0);
  static const dangerTintBorder = Color(0xFFF5DADA);
  static const warning = Color(0xFF8A6412);
  static const warningTint = Color(0xFFFBF3E2);
  static const info = Color(0xFF4A5B72);
  static const infoTint = Color(0xFFE7EAEF);

  // Dark mode.
  static const darkCanvas = Color(0xFF0E0C14);
  static const darkSurface = Color(0xFF1B1826);
  static const darkBorder = Color(0x1AFFFFFF);
  static const darkTextPrimary = Color(0xFFFFFFFF);
  static const darkTextMuted = Color(0xFF8C86A0);
}

/// The one gradient in the system. Money, primary CTAs, the raised nav action.
class MiftahGradients {
  MiftahGradients._();

  static const gold = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      MiftahColors.brassDeep,
      MiftahColors.brass,
      MiftahColors.brassPale,
    ],
    stops: [0.0, 0.55, 1.0],
  );

  /// Slightly tighter version for small fills (nav FAB, avatars).
  static const goldCompact = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [MiftahColors.brass, MiftahColors.brassDeep],
  );
}

/// Corner radii. The redesign's single biggest visual lever — the old app's
/// 8–14px corners are what read as dated.
class MiftahRadii {
  MiftahRadii._();

  static const card = 20.0; // list cards, panels
  static const hero = 22.0; // gradient money cards
  static const tile = 16.0; // quick actions, inputs, buttons
  static const chip = 11.0; // filter chips (squarish, not pills)
  static const sheet = 30.0; // bottom sheets
  static const phone = 46.0; // device frame, for mock parity
  static const pill = 999.0; // status badges, avatars

  static BorderRadius all(double r) => BorderRadius.circular(r);
}

class MiftahSpacing {
  MiftahSpacing._();

  static const page = 20.0; // horizontal page gutter
  static const gap = 11.0; // between stacked cards
  static const cardPad = 17.0; // inside a list card
  static const heroPad = 20.0; // inside a gradient card
}

/// Type. One family, weight does the work. No tracked uppercase headings, no
/// serif display — that pairing is what made the old UI feel old.
class MiftahType {
  MiftahType._();

  static TextStyle _jakarta({
    required double size,
    required FontWeight weight,
    double? letterSpacing,
    double? height,
    Color? color,
  }) =>
      GoogleFonts.plusJakartaSans(
        fontSize: size,
        fontWeight: weight,
        letterSpacing: letterSpacing,
        height: height,
        color: color,
      );

  /// Screen title — "Your cheques", "Portfolio".
  static TextStyle title({Color? color}) => _jakarta(
        size: 22,
        weight: FontWeight.w800,
        letterSpacing: -0.44,
        color: color ?? MiftahColors.textPrimary,
      );

  /// Big statement on login / empty heroes.
  static TextStyle display({Color? color}) => _jakarta(
        size: 38,
        weight: FontWeight.w800,
        letterSpacing: -1.14,
        height: 1.1,
        color: color ?? MiftahColors.textPrimary,
      );

  /// Money. Tabular-feeling, tight, heavy.
  static TextStyle amount({double size = 36, Color? color}) => _jakarta(
        size: size,
        weight: FontWeight.w800,
        letterSpacing: size * -0.025,
        color: color ?? MiftahColors.textPrimary,
      );

  /// Card heading.
  static TextStyle cardTitle({Color? color}) => _jakarta(
        size: 15.5,
        weight: FontWeight.w800,
        letterSpacing: -0.16,
        color: color ?? MiftahColors.textPrimary,
      );

  static TextStyle body({double size = 13, Color? color}) => _jakarta(
        size: size,
        weight: FontWeight.w400,
        height: 1.5,
        color: color ?? MiftahColors.textSecondary,
      );

  static TextStyle meta({Color? color}) => _jakarta(
        size: 11.5,
        weight: FontWeight.w500,
        color: color ?? MiftahColors.textMuted,
      );

  /// Section label — "AMENITIES", "PRIORITY". The ONLY place tracking is used.
  static TextStyle sectionLabel({Color? color}) => _jakarta(
        size: 11,
        weight: FontWeight.w800,
        letterSpacing: 1.54,
        color: color ?? MiftahColors.textMuted,
      );

  /// Status badge text.
  static TextStyle badge({Color? color}) => _jakarta(
        size: 10.5,
        weight: FontWeight.w800,
        letterSpacing: 0.63,
        color: color,
      );

  static TextStyle button({double size = 16, Color? color}) => _jakarta(
        size: size,
        weight: FontWeight.w800,
        color: color,
      );

  /// Reference numbers, plates, cheque numbers, codes, dates in captions.
  static TextStyle mono({double size = 12, Color? color}) =>
      GoogleFonts.ibmPlexMono(
        fontSize: size,
        fontWeight: FontWeight.w400,
        color: color ?? MiftahColors.textMuted,
      );

  /// Arabic replacement for any of the above — Naskh, no letterSpacing.
  static TextStyle ar({
    double size = 14,
    FontWeight weight = FontWeight.w400,
    Color? color,
  }) =>
      GoogleFonts.notoNaskhArabic(
        fontSize: size + 0.5,
        fontWeight: weight,
        color: color,
      );
}

class MiftahShadows {
  MiftahShadows._();

  /// Cards do NOT get a shadow in this system — they get a 1px border.
  /// Shadow is reserved for things that float.
  static const List<BoxShadow> raised = [
    BoxShadow(
      color: Color(0x1F12101A),
      blurRadius: 24,
      offset: Offset(0, 10),
    ),
  ];

  static const List<BoxShadow> gold = [
    BoxShadow(
      color: Color(0x52C79A3C),
      blurRadius: 28,
      offset: Offset(0, 12),
    ),
  ];
}
