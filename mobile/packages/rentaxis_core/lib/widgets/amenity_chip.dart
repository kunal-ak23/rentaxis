import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../theme/app_theme.dart';

/// All amenity keys supported by the backend, in display order.
const kAllAmenities = [
  'POOL', 'GYM', 'SAUNA', 'STEAM_ROOM', 'JACUZZI',
  'KIDS_PLAY_AREA', 'KIDS_POOL', 'BBQ_AREA', 'GARDEN', 'ROOFTOP_LOUNGE',
  'SECURITY_24_7', 'CCTV', 'CONCIERGE', 'INTERCOM',
  'COVERED_PARKING', 'VISITOR_PARKING', 'EV_CHARGING', 'ELEVATOR',
  'CENTRAL_AC', 'DISTRICT_COOLING', 'MAIDS_ROOM', 'STUDY_ROOM',
  'STORAGE_ROOM', 'LAUNDRY_ROOM', 'BUILT_IN_WARDROBES',
  'BALCONY', 'PRIVATE_GARDEN', 'MAID_SERVICE', 'PET_FRIENDLY',
  'SMART_HOME', 'SOLAR_POWER', 'NEAR_METRO', 'NEAR_SCHOOL',
  'NEAR_MALL', 'SEA_VIEW',
];

/// Compact chip for a single listing amenity.
class AmenityChip extends StatelessWidget {
  final String amenity; // e.g. "POOL", "GYM"
  final String? customLabel;

  const AmenityChip({
    super.key,
    required this.amenity,
    this.customLabel,
  });

  @override
  Widget build(BuildContext context) {
    final label = customLabel ??
        amenity
            .split('_')
            .map((w) => w[0].toUpperCase() + w.substring(1).toLowerCase())
            .join(' ');
    final icon = _icon(amenity);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: AppColors.primary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(20),
        border:
            Border.all(color: AppColors.primary.withValues(alpha: 0.2)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: AppColors.primary),
          const SizedBox(width: 4),
          Text(
            label,
            style: GoogleFonts.josefinSans(
              fontSize: 11,
              color: AppColors.primary,
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
      'EV_CHARGING' =>
        Icons.local_parking_outlined,
      'ELEVATOR' => Icons.elevator_outlined,
      'CENTRAL_AC' || 'DISTRICT_COOLING' => Icons.ac_unit_outlined,
      'BALCONY' => Icons.balcony_outlined,
      'MAIDS_ROOM' || 'LAUNDRY_ROOM' || 'STORAGE_ROOM' => Icons.door_front_door_outlined,
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
