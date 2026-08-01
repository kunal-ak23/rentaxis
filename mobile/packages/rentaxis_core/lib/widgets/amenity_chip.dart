import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';
import '../utils/l10n.dart';

/// All amenity keys supported by the backend, in display order.
const kAllAmenities = [
  'POOL',
  'GYM',
  'SAUNA',
  'STEAM_ROOM',
  'JACUZZI',
  'KIDS_PLAY_AREA',
  'KIDS_POOL',
  'BBQ_AREA',
  'GARDEN',
  'ROOFTOP_LOUNGE',
  'SECURITY_24_7',
  'CCTV',
  'CONCIERGE',
  'INTERCOM',
  'COVERED_PARKING',
  'VISITOR_PARKING',
  'EV_CHARGING',
  'ELEVATOR',
  'CENTRAL_AC',
  'DISTRICT_COOLING',
  'MAIDS_ROOM',
  'STUDY_ROOM',
  'STORAGE_ROOM',
  'LAUNDRY_ROOM',
  'BUILT_IN_WARDROBES',
  'BALCONY',
  'PRIVATE_GARDEN',
  'MAID_SERVICE',
  'PET_FRIENDLY',
  'SMART_HOME',
  'SOLAR_POWER',
  'NEAR_METRO',
  'NEAR_SCHOOL',
  'NEAR_MALL',
  'SEA_VIEW',
];

/// EN/AR labels for [kAllAmenities] codes, matching the web app's terms
/// (`web/messages/ar.json` → `amenity.*`). Falls back to a title-cased
/// version of the code (EN) when a code isn't in the map.
const Map<String, String> _kAmenityLabelsAr = {
  'POOL': 'مسبح',
  'GYM': 'صالة رياضية',
  'SAUNA': 'ساونا',
  'STEAM_ROOM': 'غرفة البخار',
  'JACUZZI': 'جاكوزي',
  'KIDS_PLAY_AREA': 'منطقة ألعاب الأطفال',
  'KIDS_POOL': 'مسبح الأطفال',
  'BBQ_AREA': 'منطقة شواء',
  'GARDEN': 'حديقة',
  'ROOFTOP_LOUNGE': 'صالة السطح',
  'SECURITY_24_7': 'أمن على مدار الساعة',
  'CCTV': 'كاميرات مراقبة',
  'CONCIERGE': 'استقبال',
  'INTERCOM': 'اتصال داخلي',
  'COVERED_PARKING': 'موقف مسقوف',
  'VISITOR_PARKING': 'موقف الزوار',
  'EV_CHARGING': 'شحن السيارات الكهربائية',
  'ELEVATOR': 'مصعد',
  'CENTRAL_AC': 'تكييف مركزي',
  'DISTRICT_COOLING': 'تبريد مركزي',
  'MAIDS_ROOM': 'غرفة العمالة',
  'STUDY_ROOM': 'غرفة دراسة',
  'STORAGE_ROOM': 'غرفة تخزين',
  'LAUNDRY_ROOM': 'غرفة غسيل',
  'BUILT_IN_WARDROBES': 'خزائن مدمجة',
  'BALCONY': 'شرفة',
  'PRIVATE_GARDEN': 'حديقة خاصة',
  'MAID_SERVICE': 'خدمة عمالة',
  'PET_FRIENDLY': 'مسموح بالحيوانات الأليفة',
  'SMART_HOME': 'منزل ذكي',
  'SOLAR_POWER': 'طاقة شمسية',
  'NEAR_METRO': 'قريب من المترو',
  'NEAR_SCHOOL': 'قريب من مدرسة',
  'NEAR_MALL': 'قريب من مركز تجاري',
  'SEA_VIEW': 'إطلالة بحرية',
};

/// Resolves the display label for an amenity code, preferring
/// [customLabel] (backend-supplied, not translated) when present.
String amenityLabel(String amenity, {required bool ar, String? customLabel}) {
  if (customLabel != null && customLabel.isNotEmpty) return customLabel;
  if (ar) {
    final label = _kAmenityLabelsAr[amenity];
    if (label != null) return label;
  }
  return amenity
      .split('_')
      .map(
        (w) =>
            w.isEmpty ? w : w[0].toUpperCase() + w.substring(1).toLowerCase(),
      )
      .join(' ');
}

/// Compact chip for a single listing amenity.
class AmenityChip extends StatelessWidget {
  final String amenity; // e.g. "POOL", "GYM"
  final String? customLabel;

  const AmenityChip({super.key, required this.amenity, this.customLabel});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final label = amenityLabel(
      amenity,
      ar: context.isAr,
      customLabel: customLabel,
    );
    final icon = _icon(amenity);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: m.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: m.textPrimary),
          const SizedBox(width: 4),
          Text(
            label,
            style: GoogleFonts.josefinSans(
              fontSize: 11,
              color: m.textPrimary,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }

  static IconData _icon(String amenity) {
    return switch (amenity) {
      'POOL' || 'KIDS_POOL' => Icons.pool_outlined,
      'GYM' => Icons.fitness_center_outlined,
      'SAUNA' || 'STEAM_ROOM' || 'JACUZZI' => Icons.spa_outlined,
      'KIDS_PLAY_AREA' => Icons.child_care_outlined,
      'BBQ_AREA' => Icons.outdoor_grill_outlined,
      'GARDEN' || 'PRIVATE_GARDEN' => Icons.park_outlined,
      'ROOFTOP_LOUNGE' => Icons.roofing_outlined,
      'SECURITY_24_7' || 'CCTV' || 'INTERCOM' => Icons.security_outlined,
      'CONCIERGE' => Icons.room_service_outlined,
      'COVERED_PARKING' ||
      'VISITOR_PARKING' ||
      'EV_CHARGING' => Icons.local_parking_outlined,
      'ELEVATOR' => Icons.elevator_outlined,
      'CENTRAL_AC' || 'DISTRICT_COOLING' => Icons.ac_unit_outlined,
      'BALCONY' => Icons.balcony_outlined,
      'MAIDS_ROOM' ||
      'LAUNDRY_ROOM' ||
      'STORAGE_ROOM' => Icons.door_front_door_outlined,
      'BUILT_IN_WARDROBES' => Icons.checkroom_outlined,
      'PET_FRIENDLY' => Icons.pets_outlined,
      'SMART_HOME' => Icons.home_filled,
      'NEAR_METRO' => Icons.train_outlined,
      'NEAR_SCHOOL' => Icons.school_outlined,
      'NEAR_MALL' => Icons.local_mall_outlined,
      'SEA_VIEW' => Icons.water_outlined,
      _ => Icons.check_circle_outline,
    };
  }
}
