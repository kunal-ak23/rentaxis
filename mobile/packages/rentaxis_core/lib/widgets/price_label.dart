import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/formatters.dart';
import '../utils/l10n.dart';

/// Prominent annual rent label for listing cards.
class PriceLabel extends StatelessWidget {
  final double annualRent;
  final double fontSize;

  const PriceLabel({super.key, required this.annualRent, this.fontSize = 14});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final suffix = context.isAr ? '/سنة' : '/yr';
    return Text(
      '${Formatters.currencyCompact(annualRent)}$suffix',
      style: GoogleFonts.josefinSans(
        fontSize: fontSize,
        fontWeight: FontWeight.w700,
        color: m.textPrimary,
      ),
    );
  }
}
