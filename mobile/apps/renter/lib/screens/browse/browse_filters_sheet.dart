import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

// ── Data model ────────────────────────────────────────────────────────────────

class BrowseFilters {
  final int? minBedrooms;
  final double? minRent;
  final double? maxRent;
  final String? furnishing; // UNFURNISHED | SEMI_FURNISHED | FULLY_FURNISHED
  final bool? availableNow;
  final double? nearLat;
  final double? nearLng;
  final double? radiusKm;

  const BrowseFilters({
    this.minBedrooms,
    this.minRent,
    this.maxRent,
    this.furnishing,
    this.availableNow,
    this.nearLat,
    this.nearLng,
    this.radiusKm,
  });

  int get activeCount => [
        minBedrooms,
        furnishing,
        if (availableNow == true) true,
        if (minRent != null || maxRent != null) true,
      ].whereType<Object>().length;

  BrowseFilters copyWith({
    int? minBedrooms,
    double? minRent,
    double? maxRent,
    String? furnishing,
    bool? availableNow,
    double? nearLat,
    double? nearLng,
    double? radiusKm,
    bool clearMinBedrooms = false,
    bool clearFurnishing = false,
    bool clearAvailableNow = false,
    bool clearRent = false,
  }) {
    return BrowseFilters(
      minBedrooms: clearMinBedrooms ? null : (minBedrooms ?? this.minBedrooms),
      minRent: clearRent ? null : (minRent ?? this.minRent),
      maxRent: clearRent ? null : (maxRent ?? this.maxRent),
      furnishing: clearFurnishing ? null : (furnishing ?? this.furnishing),
      availableNow: clearAvailableNow ? null : (availableNow ?? this.availableNow),
      nearLat: nearLat ?? this.nearLat,
      nearLng: nearLng ?? this.nearLng,
      radiusKm: radiusKm ?? this.radiusKm,
    );
  }
}

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
  static const _furnishingOptions = [
    ('UNFURNISHED', 'Unfurnished'),
    ('SEMI_FURNISHED', 'Semi-furnished'),
    ('FULLY_FURNISHED', 'Fully furnished'),
  ];

  @override
  void initState() {
    super.initState();
    final f = widget.current;
    _minBedrooms = f.minBedrooms;
    _rentRange = RangeValues(
      f.minRent ?? 0,
      f.maxRent ?? _maxRentLimit,
    );
    _furnishing = f.furnishing;
    _availableNow = f.availableNow ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
      padding: EdgeInsets.fromLTRB(20, 20, 20, 20 + bottom),
      decoration: BoxDecoration(
        color: AppColors.surface,
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
                  'Filters',
                  style: GoogleFonts.cinzel(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
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
                    'Reset',
                    style: GoogleFonts.josefinSans(
                        color: AppColors.primary, fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),

            // Bedrooms
            _Label('Minimum bedrooms'),
            const SizedBox(height: 10),
            _BedroomSelector(
              selected: _minBedrooms,
              onSelect: (v) => setState(() => _minBedrooms = v),
            ),
            const SizedBox(height: 20),

            // Rent range
            _Label('Annual rent range'),
            const SizedBox(height: 4),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'AED ${(_rentRange.start / 1000).round()}k',
                  style: GoogleFonts.josefinSans(
                      fontSize: 13, color: AppColors.textSecondary),
                ),
                Text(
                  _rentRange.end >= _maxRentLimit
                      ? 'AED 500k+'
                      : 'AED ${(_rentRange.end / 1000).round()}k',
                  style: GoogleFonts.josefinSans(
                      fontSize: 13, color: AppColors.textSecondary),
                ),
              ],
            ),
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                activeTrackColor: AppColors.primary,
                thumbColor: AppColors.primary,
                inactiveTrackColor: AppColors.border,
                overlayColor: AppColors.primary.withValues(alpha: 0.12),
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
            _Label('Furnishing'),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              children: _furnishingOptions.map((opt) {
                final isSelected = _furnishing == opt.$1;
                return GestureDetector(
                  onTap: () => setState(
                      () => _furnishing = isSelected ? null : opt.$1),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 8),
                    decoration: BoxDecoration(
                      color: isSelected
                          ? AppColors.primary
                          : AppColors.background,
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: isSelected
                            ? AppColors.primary
                            : AppColors.border,
                      ),
                    ),
                    child: Text(
                      opt.$2,
                      style: GoogleFonts.josefinSans(
                        fontSize: 13,
                        color: isSelected
                            ? Colors.white
                            : AppColors.textSecondary,
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
                    'Available now only',
                    style: GoogleFonts.josefinSans(
                        fontSize: 15, color: AppColors.textPrimary),
                  ),
                ),
                Switch.adaptive(
                  value: _availableNow,
                  onChanged: (v) => setState(() => _availableNow = v),
                  activeColor: AppColors.primary,
                ),
              ],
            ),
            const SizedBox(height: 24),

            // Apply
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: () {
                  widget.onApply(BrowseFilters(
                    minBedrooms: _minBedrooms,
                    minRent:
                        _rentRange.start > 0 ? _rentRange.start : null,
                    maxRent: _rentRange.end < _maxRentLimit
                        ? _rentRange.end
                        : null,
                    furnishing: _furnishing,
                    availableNow: _availableNow ? true : null,
                  ));
                  Navigator.of(context).pop();
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.primary,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(
                  'Apply filters',
                  style: GoogleFonts.josefinSans(
                      fontSize: 15, fontWeight: FontWeight.w700),
                ),
              ),
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
      style: GoogleFonts.josefinSans(
        fontSize: 13,
        fontWeight: FontWeight.w600,
        color: AppColors.textMuted,
        letterSpacing: 0.3,
      ),
    );
  }
}

class _BedroomSelector extends StatelessWidget {
  final int? selected;
  final ValueChanged<int?> onSelect;
  const _BedroomSelector({required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _BedBtn(label: 'Any', value: null, selected: selected, onTap: onSelect),
        const SizedBox(width: 8),
        for (final n in [1, 2, 3, 4, 5]) ...[
          _BedBtn(
              label: '$n+', value: n, selected: selected, onTap: onSelect),
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
  const _BedBtn(
      {required this.label,
      required this.value,
      required this.selected,
      required this.onTap});

  @override
  Widget build(BuildContext context) {
    final isSelected = selected == value;
    return GestureDetector(
      onTap: () => onTap(value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        width: 40,
        height: 36,
        decoration: BoxDecoration(
          color: isSelected ? AppColors.primary : AppColors.background,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
              color: isSelected ? AppColors.primary : AppColors.border),
        ),
        child: Center(
          child: Text(
            label,
            style: GoogleFonts.josefinSans(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: isSelected ? Colors.white : AppColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}
