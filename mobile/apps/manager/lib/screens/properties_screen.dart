import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _unitServiceProvider = Provider<UnitService>((ref) {
  final client = ref.watch(apiClientProvider);
  return UnitService(client.dio);
});

final _propertiesProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

final _allUnitsProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_unitServiceProvider);
  return service.getUnits();
});

/// Properties list, per admin design 1b: dark chrome header with building
/// count overline + search field, property cards with an occupancy bar and
/// monthly/vacant/maintenance stat row.
class PropertiesScreen extends ConsumerStatefulWidget {
  const PropertiesScreen({super.key});

  @override
  ConsumerState<PropertiesScreen> createState() => _PropertiesScreenState();
}

class _PropertiesScreenState extends ConsumerState<PropertiesScreen> {
  String _searchQuery = '';

  Future<void> _refresh() async {
    ref.invalidate(_propertiesProvider);
    ref.invalidate(_allUnitsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final propertiesAsync = ref.watch(_propertiesProvider);
    final unitsAsync = ref.watch(_allUnitsProvider);
    final m = context.miftah;
    final l = _L(context.isAr);
    // POST /v1/properties is SUPER_ADMIN/TENANT_ADMIN only — hide the add
    // action for other roles instead of offering a guaranteed 403.
    final role = ref.watch(authProvider).role;
    final canCreate = role == 'SUPER_ADMIN' || role == 'TENANT_ADMIN';

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(
            l: l,
            propertyCount: propertiesAsync.valueOrNull?.length ?? 0,
            unitCount: unitsAsync.valueOrNull?.length ?? 0,
            searchQuery: _searchQuery,
            onSearchChanged: (v) =>
                setState(() => _searchQuery = v.toLowerCase()),
            onAddTap: canCreate
                ? () => _showCreatePropertySheet(context, l)
                : null,
          ),
          Expanded(
            child: propertiesAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(horizontal: 20),
                child: ListShimmer(itemCount: 3),
              ),
              error: (e, _) =>
                  ErrorState(message: l.loadError, onRetry: _refresh),
              data: (properties) {
                final allUnits = unitsAsync.valueOrNull ?? [];
                final filtered = properties.where((p) {
                  final prop = p['property'] ?? p;
                  final name = (prop['nameEn'] ?? prop['name'] ?? '')
                      .toString()
                      .toLowerCase();
                  final address = (prop['address'] ?? '')
                      .toString()
                      .toLowerCase();
                  return name.contains(_searchQuery) ||
                      address.contains(_searchQuery);
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.apartment_outlined,
                    title: _searchQuery.isEmpty
                        ? l.noPropertiesYet
                        : l.noMatchingProperties,
                    subtitle: _searchQuery.isEmpty ? l.addFirstProperty : null,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 150),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final item = filtered[index];
                      final prop = item['property'] ?? item;
                      final propertyId = prop['id'] ?? item['id'] ?? '';
                      final units = allUnits.where((u) {
                        final unitPropId =
                            u['propertyId'] ?? u['property']?['id'];
                        return unitPropId == propertyId;
                      }).toList();
                      final occupied = units
                          .where((u) => u['status'] == 'OCCUPIED')
                          .length;
                      final total = units.length;
                      final vacancies = item['vacancies'] ?? 0;
                      final unitCount = total > 0
                          ? total
                          : (item['propertyCount'] ?? 0);
                      final occupiedCount = total > 0
                          ? occupied
                          : (unitCount - (vacancies as int));
                      final occupancy = unitCount > 0
                          ? occupiedCount / unitCount
                          : 0.0;
                      // Units carry `expectedRent` (asking) and `actualRent`
                      // (leased); there is no `annualRent` key, which left
                      // this card permanently at AED 0. Prefer the leased
                      // figure when present, else the asking rent.
                      final monthlyRent = units.fold<double>(0, (s, u) {
                        final actual = ((u['actualRent'] ?? 0) as num)
                            .toDouble();
                        final expected = ((u['expectedRent'] ?? 0) as num)
                            .toDouble();
                        return s + (actual > 0 ? actual : expected) / 12;
                      });
                      final maintenanceUnits = units
                          .where((u) => u['status'] == 'MAINTENANCE')
                          .length;

                      return AnimatedListItem(
                        index: index,
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: 10),
                          child: _PropertyCard(
                            name: prop['nameEn'] ?? prop['name'] ?? '',
                            address: prop['address'] ?? '',
                            emirate: prop['emirate'] ?? '',
                            totalUnits: unitCount,
                            occupiedUnits: occupiedCount,
                            occupancy: occupancy,
                            monthlyRent: monthlyRent,
                            vacantUnits: unitCount - occupiedCount,
                            maintenanceUnits: maintenanceUnits,
                            l: l,
                            onTap: () =>
                                context.push('/properties/$propertyId'),
                          ),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  void _showCreatePropertySheet(BuildContext context, _L l) {
    final nameCtrl = TextEditingController();
    final addressCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String selectedEmirate = 'DUBAI';
    String selectedType = 'RESIDENTIAL';

    // PropertyType enum values the backend accepts.
    final propertyTypes = ['RESIDENTIAL', 'COMMERCIAL', 'MIXED'];

    final emirates = [
      'DUBAI',
      'ABU_DHABI',
      'SHARJAH',
      'AJMAN',
      'RAS_AL_KHAIMAH',
      'FUJAIRAH',
      'UMM_AL_QUWAIN',
    ];

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
            24,
            24,
            24,
            MediaQuery.of(ctx).viewInsets.bottom + 24,
          ),
          child: Form(
            key: formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: AppColors.border,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                Text(
                  l.newProperty,
                  style: Theme.of(ctx).textTheme.headlineSmall,
                ),
                const SizedBox(height: 20),
                TextFormField(
                  controller: nameCtrl,
                  decoration: InputDecoration(
                    labelText: l.propertyName,
                    prefixIcon: const Icon(Icons.apartment_outlined),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? l.nameRequired : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: addressCtrl,
                  decoration: InputDecoration(
                    labelText: l.address,
                    prefixIcon: const Icon(Icons.location_on_outlined),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? l.addressRequired : null,
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<String>(
                  initialValue: selectedEmirate,
                  decoration: InputDecoration(
                    labelText: l.emirate,
                    prefixIcon: const Icon(Icons.flag_outlined),
                  ),
                  items: emirates
                      .map(
                        (e) => DropdownMenuItem(
                          value: e,
                          child: Text(e.replaceAll('_', ' ')),
                        ),
                      )
                      .toList(),
                  onChanged: (v) =>
                      setSheetState(() => selectedEmirate = v ?? 'DUBAI'),
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<String>(
                  initialValue: selectedType,
                  decoration: InputDecoration(
                    labelText: l.propertyType,
                    prefixIcon: const Icon(Icons.category_outlined),
                  ),
                  items: propertyTypes
                      .map((t) => DropdownMenuItem(value: t, child: Text(t)))
                      .toList(),
                  onChanged: (v) =>
                      setSheetState(() => selectedType = v ?? 'RESIDENTIAL'),
                ),
                const SizedBox(height: 24),
                GoldButton(
                  label: l.ar
                      ? l.createProperty
                      : l.createProperty.toUpperCase(),
                  onPressed: () async {
                    if (!formKey.currentState!.validate()) return;
                    final service = ref.read(_propertyServiceProvider);
                    try {
                      // CreatePropertyDTO requires nameEn and type; 'name'
                      // is not a DTO field and used to 400 every submit.
                      await service.createProperty({
                        'nameEn': nameCtrl.text.trim(),
                        'type': selectedType,
                        'address': addressCtrl.text.trim(),
                        'emirate': selectedEmirate,
                      });
                      if (ctx.mounted) Navigator.pop(ctx);
                      _refresh();
                    } catch (e) {
                      if (ctx.mounted) {
                        ScaffoldMessenger.of(ctx).showSnackBar(
                          SnackBar(content: Text(l.createPropertyFailed)),
                        );
                      }
                    }
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ─── Chrome header ──────────────────────────────────────────────────────────

class _ChromeHeader extends StatefulWidget {
  final _L l;
  final int propertyCount;
  final int unitCount;
  final String searchQuery;
  final ValueChanged<String> onSearchChanged;
  final VoidCallback? onAddTap;

  const _ChromeHeader({
    required this.l,
    required this.propertyCount,
    required this.unitCount,
    required this.searchQuery,
    required this.onSearchChanged,
    required this.onAddTap,
  });

  @override
  State<_ChromeHeader> createState() => _ChromeHeaderState();
}

class _ChromeHeaderState extends State<_ChromeHeader> {
  late final TextEditingController _ctrl = TextEditingController(
    text: widget.searchQuery,
  );

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = widget.l;
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l.buildingsAndUnits(
                        widget.propertyCount,
                        widget.unitCount,
                      ),
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 11.5,
                              color: AppColors.goldMid,
                            )
                          : GoogleFonts.plusJakartaSans(
                              fontSize: 9,
                              letterSpacing: 2.4,
                              color: AppColors.goldMid,
                            ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      l.title,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 21,
                              fontWeight: FontWeight.w600,
                              color: AppColors.gold400,
                            )
                          : GoogleFonts.plusJakartaSans(
                              fontSize: 21,
                              color: AppColors.gold400,
                            ),
                    ),
                  ],
                ),
              ),
              if (widget.onAddTap != null)
                InkWell(
                  onTap: widget.onAddTap,
                  child: Text(
                    l.ar ? l.addAction : l.addAction.toUpperCase(),
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13,
                            color: AppColors.accent,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 11,
                            letterSpacing: 1.4,
                            color: AppColors.accent,
                          ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.07),
              border: Border.all(
                color: AppColors.accent.withValues(alpha: 0.2),
              ),
              borderRadius: BorderRadius.circular(999),
            ),
            padding: const EdgeInsetsDirectional.only(start: 14, end: 6),
            child: Row(
              children: [
                Icon(
                  Icons.search,
                  size: 17,
                  color: Colors.white.withValues(alpha: 0.45),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _ctrl,
                    onChanged: widget.onSearchChanged,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13,
                            color: Colors.white,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 12.5,
                            color: Colors.white,
                          ),
                    decoration: InputDecoration(
                      isDense: true,
                      // The pill behind this field is the dark header chrome;
                      // without this the theme's light fillColor paints a
                      // white box inside it.
                      filled: false,
                      hintText: l.searchHint,
                      hintStyle: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 13,
                              color: Colors.white.withValues(alpha: 0.45),
                            )
                          : GoogleFonts.plusJakartaSans(
                              fontSize: 12.5,
                              color: Colors.white.withValues(alpha: 0.45),
                            ),
                      border: InputBorder.none,
                      contentPadding: const EdgeInsets.symmetric(vertical: 10),
                    ),
                  ),
                ),
                if (_ctrl.text.isNotEmpty)
                  IconButton(
                    icon: Icon(
                      Icons.close,
                      size: 16,
                      color: Colors.white.withValues(alpha: 0.6),
                    ),
                    onPressed: () {
                      _ctrl.clear();
                      widget.onSearchChanged('');
                    },
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Property card ──────────────────────────────────────────────────────────

class _PropertyCard extends StatelessWidget {
  final String name;
  final String address;
  final String emirate;
  final int totalUnits;
  final int occupiedUnits;
  final double occupancy;
  final double monthlyRent;
  final int vacantUnits;
  final int maintenanceUnits;
  final _L l;
  final VoidCallback onTap;

  const _PropertyCard({
    required this.name,
    required this.address,
    required this.emirate,
    required this.totalUnits,
    required this.occupiedUnits,
    required this.occupancy,
    required this.monthlyRent,
    required this.vacantUnits,
    required this.maintenanceUnits,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(14),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        name,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                                color: m.textPrimary,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 17,
                                color: m.textPrimary,
                              ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 8),
                    _OccupancyPill(occupancy: occupancy, l: l, m: m),
                  ],
                ),
                const SizedBox(height: 3),
                Text(
                  '$address · ${l.unitsCount(totalUnits)}',
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          color: m.textMuted,
                        )
                      : GoogleFonts.plusJakartaSans(
                          fontSize: 11.5,
                          color: m.textMuted,
                        ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 11),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: occupancy.clamp(0.0, 1.0),
                    backgroundColor: m.surfaceAlt,
                    valueColor: AlwaysStoppedAnimation(AppColors.accent),
                    minHeight: 6,
                  ),
                ),
                const SizedBox(height: 11),
                Container(
                  decoration: BoxDecoration(
                    border: Border(top: BorderSide(color: m.divider)),
                  ),
                  padding: const EdgeInsets.only(top: 11),
                  child: Row(
                    children: [
                      Expanded(
                        child: _StatCell(
                          label: l.monthly,
                          value: Formatters.currencyCompact(monthlyRent),
                          m: m,
                          l: l,
                        ),
                      ),
                      Expanded(
                        child: _StatCell(
                          label: l.vacant,
                          value: '$vacantUnits',
                          m: m,
                          l: l,
                        ),
                      ),
                      Expanded(
                        child: _StatCell(
                          label: l.maintenance,
                          value: '$maintenanceUnits',
                          tone: maintenanceUnits > 0 ? AppColors.warning : null,
                          m: m,
                          l: l,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _OccupancyPill extends StatelessWidget {
  final double occupancy;
  final _L l;
  final LegacyMiftahColors m;
  const _OccupancyPill({
    required this.occupancy,
    required this.l,
    required this.m,
  });

  @override
  Widget build(BuildContext context) {
    final pct = (occupancy * 100).round();
    final color = pct >= 95
        ? m.success
        : (pct >= 80 ? AppColors.warning : AppColors.accentDark);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: color.withValues(alpha: 0.1),
        border: Border.all(color: color.withValues(alpha: 0.28)),
      ),
      child: Text(
        l.percentLet(pct),
        style: l.ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.plusJakartaSans(
                fontSize: 9,
                letterSpacing: 1.0,
                fontWeight: FontWeight.w600,
                color: color,
              ),
      ),
    );
  }
}

class _StatCell extends StatelessWidget {
  final String label;
  final String value;
  final Color? tone;
  final LegacyMiftahColors m;
  final _L l;

  const _StatCell({
    required this.label,
    required this.value,
    required this.m,
    required this.l,
    this.tone,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          l.ar ? label : label.toUpperCase(),
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(fontSize: 10.5, color: m.textMuted)
              : GoogleFonts.plusJakartaSans(
                  fontSize: 8.5,
                  letterSpacing: 1.6,
                  color: m.textMuted,
                ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: GoogleFonts.plusJakartaSans(fontSize: 14, color: tone ?? m.textPrimary),
        ),
      ],
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'العقارات' : 'Properties';
  String buildingsAndUnits(int buildings, int units) => ar
      ? '$buildings مبانٍ · $units وحدة'
      : '$buildings building${buildings == 1 ? '' : 's'} · $units units';
  String get addAction => ar ? '+ إضافة' : '+ Add';
  String get searchHint =>
      ar ? 'ابحث عن مبنى، وحدة، مستأجر' : 'Search building, unit, renter';
  String get loadError =>
      ar ? 'تعذّر تحميل العقارات' : 'Failed to load properties';
  String get noPropertiesYet => ar ? 'لا توجد عقارات بعد' : 'No properties yet';
  String get noMatchingProperties =>
      ar ? 'لا توجد نتائج مطابقة' : 'No matching properties';
  String get addFirstProperty =>
      ar ? 'أضف أول عقار للبدء' : 'Add your first property to get started';
  String unitsCount(int n) => ar ? '$n وحدة' : '$n units';
  String percentLet(int pct) => ar ? '$pct% مؤجّر' : '$pct% let';
  String get monthly => ar ? 'شهريًا' : 'Monthly';
  String get vacant => ar ? 'شاغرة' : 'Vacant';
  String get maintenance => ar ? 'الصيانة' : 'Maintenance';

  // Create-property sheet
  String get newProperty => ar ? 'عقار جديد' : 'New Property';
  String get propertyName => ar ? 'اسم العقار' : 'Property Name';
  String get nameRequired => ar ? 'الاسم مطلوب' : 'Name is required';
  String get address => ar ? 'العنوان' : 'Address';
  String get addressRequired => ar ? 'العنوان مطلوب' : 'Address is required';
  String get emirate => ar ? 'الإمارة' : 'Emirate';
  String get propertyType => ar ? 'نوع العقار' : 'Property Type';
  String get createProperty => ar ? 'إنشاء العقار' : 'Create Property';
  String get createPropertyFailed =>
      ar ? 'تعذّر إنشاء العقار' : 'Failed to create property';
}
