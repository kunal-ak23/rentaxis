import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/formatters.dart';

/// Prominent annual rent label for listing cards.
class PriceLabel extends StatelessWidget {
  final double annualRent;
  final double fontSize;

  const PriceLabel({
    super.key,
    required this.annualRent,
    this.fontSize = 14,
  });

  @override
  Widget build(BuildContext context) {
    return Text(
      '${Formatters.currencyCompact(annualRent)}/yr',
      style: GoogleFonts.josefinSans(
        fontSize: fontSize,
        fontWeight: FontWeight.w700,
        color: AppColors.primary,
      ),
    );
  }
}
