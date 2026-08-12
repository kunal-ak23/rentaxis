import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'browse_filters.dart';

// ── Strings (EN/AR) ─────────────────────────────────────────────────────────

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'الفلاتر' : 'Filters';
  String get reset => ar ? 'إعادة تعيين' : 'Reset';
  String get minBedrooms => ar ? 'أدنى عدد غرف نوم' : 'Minimum bedrooms';
  String get annualRentRange =>
      ar ? 'نطاق الإيجار السنوي' : 'Annual rent range';
  String get furnishing => ar ? 'التأثيث' : 'Furnishing';
  String get availableNowOnly => ar ? 'المتاح الآن فقط' : 'Available now only';
  String get applyFilters => ar ? 'تطبيق الفلاتر' : 'Apply filters';
  String get any => ar ? 'الكل' : 'Any';
  String get unfurnished => ar ? 'غير مفروش' : 'Unfurnished';
  String get semiFurnished => ar ? 'مفروش جزئياً' : 'Semi-furnished';
  String get fullyFurnished => ar ? 'مفروش بالكامل' : 'Fully furnished';
}

TextStyle _display(bool ar, {double size = 18, Color? color}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: FontWeight.w700,
        color: color,
      )
    : GoogleFonts.plusJakartaSans(
        fontSize: size,
        fontWeight: FontWeight.w700,
        color: color,
      );

// ── Bottom sheet ──────────────────────────────────────────────────────────────

class BrowseFiltersSheet extends StatefulWidget {
  final BrowseFilters current;
  final ValueChanged<BrowseFilters> onApply;

  const BrowseFiltersSheet({
    super.key,
    required this.current,
    required this.onApply,
  });

  @override
  State<BrowseFiltersSheet> createState() => _BrowseFiltersSheetState();
}

class _BrowseFiltersSheetState extends State<BrowseFiltersSheet> {
  late int? _minBedrooms;
  late RangeValues _rentRange;
  late String? _furnishing;
  late bool _availableNow;

  static const double _maxRentLimit = 500000;

  List<(String, String)> _furnishingOptions(_L l) => [
    ('UNFURNISHED', l.unfurnished),
    ('SEMI_FURNISHED', l.semiFurnished),
    ('FULLY_FURNISHED', l.fullyFurnished),
  ];

  @override
  void initState() {
    super.initState();
    final f = widget.current;
    _minBedrooms = f.minBedrooms;
    _rentRange = RangeValues(f.minRent ?? 0, f.maxRent ?? _maxRentLimit);
    _furnishing = f.furnishing;
    _availableNow = f.availableNow ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final viewInsets = MediaQuery.of(context).viewInsets.bottom;
    final safeBottom = MediaQuery.of(context).padding.bottom;
    // 84 = frosted nav height (72) + gap (6) + extra (6)
    final navOffset = safeBottom + 84.0;
    return Container(
      margin: EdgeInsets.fromLTRB(12, 0, 12, navOffset),
      padding: EdgeInsets.fromLTRB(20, 20, 20, 20 + viewInsets),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(24),
      ),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Text(
                  l.title,
                  style: _display(l.ar, size: 18, color: m.textPrimary),
                ),
                const Spacer(),
                TextButton(
                  onPressed: () {
                    setState(() {
                      _minBedrooms = null;
                      _rentRange = const RangeValues(0, _maxRentLimit);
                      _furnishing = null;
                      _availableNow = false;
                    });
                  },
                  child: Text(
                    l.reset,
                    style: GoogleFonts.plusJakartaSans(
                      color: m.isDark ? AppColors.accent : AppColors.primary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),

            // Bedrooms
            _Label(l.minBedrooms),
            const SizedBox(height: 10),
            _BedroomSelector(
              selected: _minBedrooms,
              anyLabel: l.any,
              onSelect: (v) => setState(() => _minBedrooms = v),
            ),
            const SizedBox(height: 20),

            // Rent range
            _Label(l.annualRentRange),
            const SizedBox(height: 4),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'AED ${(_rentRange.start / 1000).round()}k',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 13,
                    color: m.textSecondary,
                  ),
                ),
                Text(
                  _rentRange.end >= _maxRentLimit
                      ? 'AED 500k+'
                      : 'AED ${(_rentRange.end / 1000).round()}k',
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 13,
                    color: m.textSecondary,
                  ),
                ),
              ],
            ),
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                activeTrackColor: AppColors.accent,
                thumbColor: AppColors.accent,
                inactiveTrackColor: m.border,
                overlayColor: AppColors.accent.withValues(alpha: 0.12),
              ),
              child: RangeSlider(
                values: _rentRange,
                min: 0,
                max: _maxRentLimit,
                divisions: 50,
                onChanged: (r) => setState(() => _rentRange = r),
              ),
            ),
            const SizedBox(height: 16),

            // Furnishing
            _Label(l.furnishing),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              children: _furnishingOptions(l).map((opt) {
                final isSelected = _furnishing == opt.$1;
                return GestureDetector(
                  onTap: () =>
                      setState(() => _furnishing = isSelected ? null : opt.$1),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: isSelected ? AppColors.primary : m.background,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: isSelected ? AppColors.primary : m.borderStrong,
                      ),
                    ),
                    child: Text(
                      opt.$2,
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 13,
                        color: isSelected ? AppColors.accent : m.textSecondary,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.w400,
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 20),

            // Available now
            Row(
              children: [
                Expanded(
                  child: Text(
                    l.availableNowOnly,
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 15,
                      color: m.textPrimary,
                    ),
                  ),
                ),
                Switch.adaptive(
                  value: _availableNow,
                  onChanged: (v) => setState(() => _availableNow = v),
                  activeThumbColor: AppColors.accent,
                ),
              ],
            ),
            const SizedBox(height: 24),

            // Apply
            GoldButton(
              label: l.applyFilters,
              onPressed: () {
                // Preserve proximity coordinates seeded by the location
                // service — they are not exposed as user-editable fields
                // in this sheet.
                widget.onApply(
                  BrowseFilters(
                    minBedrooms: _minBedrooms,
                    minRent: _rentRange.start > 0 ? _rentRange.start : null,
                    maxRent: _rentRange.end < _maxRentLimit
                        ? _rentRange.end
                        : null,
                    furnishing: _furnishing,
                    availableNow: _availableNow ? true : null,
                    nearLat: widget.current.nearLat,
                    nearLng: widget.current.nearLng,
                    radiusKm: widget.current.radiusKm,
                  ),
                );
                Navigator.of(context).pop();
              },
            ),
          ],
        ),
      ),
    );
  }
}

// ── Sub-widgets ───────────────────────────────────────────────────────────────

class _Label extends StatelessWidget {
  final String text;
  const _Label(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: GoogleFonts.plusJakartaSans(
        fontSize: 13,
        fontWeight: FontWeight.w600,
        color: context.miftah.textMuted,
        letterSpacing: 0.3,
      ),
    );
  }
}

class _BedroomSelector extends StatelessWidget {
  final int? selected;
  final String anyLabel;
  final ValueChanged<int?> onSelect;
  const _BedroomSelector({
    required this.selected,
    required this.anyLabel,
    required this.onSelect,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _BedBtn(
          label: anyLabel,
          value: null,
          selected: selected,
          onTap: onSelect,
        ),
        const SizedBox(width: 8),
        for (final n in [1, 2, 3, 4, 5]) ...[
          _BedBtn(label: '$n+', value: n, selected: selected, onTap: onSelect),
          const SizedBox(width: 8),
        ],
      ],
    );
  }
}

class _BedBtn extends StatelessWidget {
  final String label;
  final int? value;
  final int? selected;
  final ValueChanged<int?> onTap;
  const _BedBtn({
    required this.label,
    required this.value,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final isSelected = selected == value;
    return GestureDetector(
      onTap: () => onTap(value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        width: 40,
        height: 36,
        decoration: BoxDecoration(
          color: isSelected ? AppColors.primary : m.background,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: isSelected ? AppColors.primary : m.border),
        ),
        child: Center(
          child: Text(
            label,
            style: GoogleFonts.plusJakartaSans(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: isSelected ? AppColors.accent : m.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}
