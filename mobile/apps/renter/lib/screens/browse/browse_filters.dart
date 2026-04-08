// ── BrowseFilters data model ──────────────────────────────────────────────────

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
      availableNow:
          clearAvailableNow ? null : (availableNow ?? this.availableNow),
      nearLat: nearLat ?? this.nearLat,
      nearLng: nearLng ?? this.nearLng,
      radiusKm: radiusKm ?? this.radiusKm,
    );
  }
}
