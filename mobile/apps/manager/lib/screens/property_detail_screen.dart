import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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

final _contactServiceProvider = Provider<PropertyContactService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyContactService(client.dio);
});

final _buildingServiceProvider = Provider<BuildingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return BuildingService(client.dio);
});

/// Property detail, per admin design 1c: hero card with gradient overlay,
/// a spec strip (units / let% / buildings / type), a unit grid with
/// status-color top bars, a rent-roll summary, and existing contacts /
/// buildings sections restyled to match.
class PropertyDetailScreen extends ConsumerStatefulWidget {
  final String propertyId;
  const PropertyDetailScreen({super.key, required this.propertyId});

  @override
  ConsumerState<PropertyDetailScreen> createState() =>
      _PropertyDetailScreenState();
}

class _PropertyDetailScreenState extends ConsumerState<PropertyDetailScreen> {
  Map<String, dynamic>? _property;
  List<dynamic> _units = [];
  List<dynamic> _contacts = [];
  List<dynamic> _buildings = [];
  bool _isLoading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final propService = ref.read(_propertyServiceProvider);
      final unitService = ref.read(_unitServiceProvider);
      final contactService = ref.read(_contactServiceProvider);
      final buildingService = ref.read(_buildingServiceProvider);
      final results = await Future.wait([
        propService.getPropertyById(widget.propertyId),
        unitService.getUnitsByProperty(widget.propertyId),
        contactService.getContacts(widget.propertyId),
        buildingService.getBuildingsByProperty(widget.propertyId),
      ]);
      if (!mounted) return;
      setState(() {
        _property = results[0] as Map<String, dynamic>;
        _units = results[1] as List<dynamic>;
        _contacts = results[2] as List<dynamic>;
        _buildings = results[3] as List<dynamic>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      // A 404 means the property is gone — a different message than a
      // transient load failure.
      final gone = e is DioException && e.response?.statusCode == 404;
      setState(() {
        _error = gone ? 'not-found' : 'load-failed';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);

    if (_isLoading) {
      return Scaffold(
        backgroundColor: m.background,
        appBar: AppBar(title: Text(l.property)),
        body: Center(child: CircularProgressIndicator(color: AppColors.accent)),
      );
    }

    if (_error != null || _property == null) {
      // A clean-but-empty response means the property is gone, not that the
      // request failed — say so instead of a misleading "failed to load".
      final notFound =
          _error == 'not-found' || (_error == null && _property == null);
      return Scaffold(
        backgroundColor: m.background,
        appBar: AppBar(title: Text(l.property)),
        body: notFound
            ? EmptyState(icon: Icons.apartment_outlined, title: l.notFound)
            : ErrorState(message: l.loadError, onRetry: _loadData),
      );
    }

    final property = _property!;
    final totalUnits = _units.length;
    final occupied = _units.where((u) => u['status'] == 'OCCUPIED').length;
    final occupancy = totalUnits > 0 ? occupied / totalUnits : 0.0;

    return Scaffold(
      backgroundColor: m.background,
      body: RefreshIndicator(
        onRefresh: _loadData,
        color: AppColors.accent,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: EdgeInsets.only(bottom: AppInsets.bottomNav(context)),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _HeroHeader(property: property, l: l),
              _SpecStrip(
                totalUnits: totalUnits,
                occupancy: occupancy,
                buildingCount: _buildings.length,
                type: (property['type'] ?? '').toString(),
                m: m,
                l: l,
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _SectionHeader(
                      label: l.unitsSection,
                      trailing: _UnitLegend(l: l, m: m),
                    ),
                    const SizedBox(height: 10),
                    if (_units.isEmpty)
                      EmptyState(
                        icon: Icons.door_front_door_outlined,
                        title: l.noUnitsYet,
                        subtitle: l.addUnitsPrompt,
                      )
                    else
                      GridView.count(
                        crossAxisCount: 4,
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        crossAxisSpacing: 8,
                        mainAxisSpacing: 8,
                        childAspectRatio: 0.92,
                        children: _units
                            .map(
                              (unit) => _UnitTile(
                                unit: unit,
                                l: l,
                                onTap: () {
                                  // Unit detail - future enhancement
                                },
                              ),
                            )
                            .toList(),
                      ),
                    const SizedBox(height: 22),
                    _SectionHeader(label: l.rentRollSection),
                    const SizedBox(height: 10),
                    _RentRollCard(units: _units, m: m, l: l),
                    const SizedBox(height: 22),
                    _SectionHeader(
                      label: l.contactsSection,
                      trailing: InkWell(
                        onTap: () => _showAddContactSheet(context, l),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              Icons.add,
                              size: 15,
                              color: AppColors.accentDark,
                            ),
                            const SizedBox(width: 3),
                            Text(
                              l.ar ? l.add : l.add.toUpperCase(),
                              style: l.ar
                                  ? GoogleFonts.notoNaskhArabic(
                                      fontSize: 12,
                                      color: AppColors.accentDark,
                                    )
                                  : GoogleFonts.josefinSans(
                                      fontSize: 10.5,
                                      letterSpacing: 1.2,
                                      color: AppColors.accentDark,
                                    ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    if (_contacts.isEmpty)
                      Text(
                        l.noContacts,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 13,
                                color: m.textSecondary,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 12.5,
                                color: m.textSecondary,
                              ),
                      )
                    else
                      ..._contacts.map(
                        (contact) => _ContactRow(
                          contact: contact,
                          l: l,
                          m: m,
                          onDelete: () async {
                            final confirmed = await showDialog<bool>(
                              context: context,
                              builder: (ctx) => AlertDialog(
                                title: Text(l.deleteContact),
                                content: Text(l.deleteContactConfirm),
                                actions: [
                                  TextButton(
                                    onPressed: () => Navigator.pop(ctx, false),
                                    child: Text(l.cancel),
                                  ),
                                  ElevatedButton(
                                    onPressed: () => Navigator.pop(ctx, true),
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: m.danger,
                                    ),
                                    child: Text(l.delete),
                                  ),
                                ],
                              ),
                            );
                            if (confirmed == true) {
                              try {
                                await ref
                                    .read(_contactServiceProvider)
                                    .deleteContact(
                                      widget.propertyId,
                                      contact['id'],
                                    );
                                _loadData();
                              } catch (_) {}
                            }
                          },
                        ),
                      ),
                    const SizedBox(height: 22),
                    _SectionHeader(label: l.buildingsSection),
                    const SizedBox(height: 10),
                    if (_buildings.isEmpty)
                      Text(
                        l.noBuildings,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 13,
                                color: m.textSecondary,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 12.5,
                                color: m.textSecondary,
                              ),
                      )
                    else
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: _buildings
                            .map(
                              (b) => Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 7,
                                ),
                                decoration: BoxDecoration(
                                  color: m.surface,
                                  border: Border.all(color: m.border),
                                  borderRadius: BorderRadius.circular(999),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      Icons.domain,
                                      size: 15,
                                      color: AppColors.accentDark,
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      b['name'] ?? l.building,
                                      style: l.ar
                                          ? GoogleFonts.notoNaskhArabic(
                                              fontSize: 12.5,
                                              color: m.textPrimary,
                                            )
                                          : GoogleFonts.josefinSans(
                                              fontSize: 12,
                                              color: m.textPrimary,
                                            ),
                                    ),
                                  ],
                                ),
                              ),
                            )
                            .toList(),
                      ),
                    const SizedBox(height: 20),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () => _showCreateUnitSheet(context, l),
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }

  void _showCreateUnitSheet(BuildContext context, _L l) {
    final unitNumberCtrl = TextEditingController();
    final sizeCtrl = TextEditingController();
    final rentCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String unitType = 'APARTMENT';

    final unitTypes = [
      'APARTMENT',
      'VILLA',
      'STUDIO',
      'OFFICE',
      'SHOP',
      'WAREHOUSE',
      'TOWNHOUSE',
      'PENTHOUSE',
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
            child: SingleChildScrollView(
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
                  Text(l.newUnit, style: Theme.of(ctx).textTheme.headlineSmall),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: unitNumberCtrl,
                    decoration: InputDecoration(
                      labelText: l.unitNumber,
                      prefixIcon: const Icon(Icons.tag),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? l.required : null,
                  ),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String>(
                    value: unitType,
                    decoration: InputDecoration(
                      labelText: l.type,
                      prefixIcon: const Icon(Icons.category_outlined),
                    ),
                    items: unitTypes
                        .map((t) => DropdownMenuItem(value: t, child: Text(t)))
                        .toList(),
                    onChanged: (v) =>
                        setSheetState(() => unitType = v ?? 'APARTMENT'),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: sizeCtrl,
                    keyboardType: TextInputType.number,
                    decoration: InputDecoration(
                      labelText: l.sizeSqft,
                      prefixIcon: const Icon(Icons.square_foot_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: rentCtrl,
                    keyboardType: TextInputType.number,
                    decoration: InputDecoration(
                      labelText: l.annualRent,
                      prefixIcon: const Icon(Icons.attach_money),
                    ),
                  ),
                  const SizedBox(height: 24),
                  GoldButton(
                    label: l.ar ? l.createUnit : l.createUnit.toUpperCase(),
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service = ref.read(_unitServiceProvider);
                      try {
                        await service.createUnit({
                          'unitNumber': unitNumberCtrl.text.trim(),
                          'propertyId': widget.propertyId,
                          'type': unitType,
                          if (sizeCtrl.text.isNotEmpty)
                            'size': double.tryParse(sizeCtrl.text.trim()),
                          if (rentCtrl.text.isNotEmpty)
                            'annualRent': double.tryParse(rentCtrl.text.trim()),
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _loadData();
                      } catch (e) {
                        if (ctx.mounted) {
                          ScaffoldMessenger.of(ctx).showSnackBar(
                            SnackBar(content: Text(l.createUnitFailed)),
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
      ),
    );
  }

  void _showAddContactSheet(BuildContext context, _L l) {
    final nameCtrl = TextEditingController();
    final roleCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: EdgeInsets.fromLTRB(
          24,
          24,
          24,
          MediaQuery.of(ctx).viewInsets.bottom + 24,
        ),
        child: Form(
          key: formKey,
          child: SingleChildScrollView(
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
                  l.newContact,
                  style: Theme.of(ctx).textTheme.headlineSmall,
                ),
                const SizedBox(height: 20),
                TextFormField(
                  controller: nameCtrl,
                  decoration: InputDecoration(
                    labelText: l.name,
                    prefixIcon: const Icon(Icons.person_outline),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? l.required : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: roleCtrl,
                  decoration: InputDecoration(
                    labelText: l.role,
                    prefixIcon: const Icon(Icons.work_outline),
                  ),
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: phoneCtrl,
                  keyboardType: TextInputType.phone,
                  decoration: InputDecoration(
                    labelText: l.phone,
                    prefixIcon: const Icon(Icons.phone_outlined),
                  ),
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: emailCtrl,
                  keyboardType: TextInputType.emailAddress,
                  decoration: InputDecoration(
                    labelText: l.email,
                    prefixIcon: const Icon(Icons.email_outlined),
                  ),
                ),
                const SizedBox(height: 24),
                GoldButton(
                  label: l.ar ? l.addContact : l.addContact.toUpperCase(),
                  onPressed: () async {
                    if (!formKey.currentState!.validate()) return;
                    try {
                      await ref
                          .read(_contactServiceProvider)
                          .createContact(widget.propertyId, {
                            'name': nameCtrl.text.trim(),
                            if (roleCtrl.text.isNotEmpty)
                              'role': roleCtrl.text.trim(),
                            if (phoneCtrl.text.isNotEmpty)
                              'phone': phoneCtrl.text.trim(),
                            if (emailCtrl.text.isNotEmpty)
                              'email': emailCtrl.text.trim(),
                          });
                      if (ctx.mounted) Navigator.pop(ctx);
                      _loadData();
                    } catch (e) {
                      if (ctx.mounted) {
                        ScaffoldMessenger.of(ctx).showSnackBar(
                          SnackBar(content: Text(l.addContactFailed)),
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

// ─── Hero header ────────────────────────────────────────────────────────────

class _HeroHeader extends StatelessWidget {
  final Map<String, dynamic> property;
  final _L l;
  const _HeroHeader({required this.property, required this.l});

  @override
  Widget build(BuildContext context) {
    final name = property['nameEn'] ?? property['name'] ?? '';
    final emirate = (property['emirate'] ?? '').toString().replaceAll('_', ' ');
    return SizedBox(
      height: 210,
      child: Stack(
        fit: StackFit.expand,
        children: [
          // No property photo available yet — a chrome-toned gradient fills
          // the hero in its place.
          const DecoratedBox(
            decoration: BoxDecoration(gradient: MiftahGradients.heroDark),
          ),
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  AppColors.primary.withValues(alpha: 0.72),
                  AppColors.primary.withValues(alpha: 0.0),
                  AppColors.primary.withValues(alpha: 0.6),
                ],
                stops: const [0, 0.46, 1],
              ),
            ),
          ),
          PositionedDirectional(
            top: MediaQuery.of(context).padding.top + 4,
            start: 8,
            child: IconButton(
              onPressed: () => Navigator.of(context).maybePop(),
              icon: Icon(
                context.isAr ? Icons.arrow_forward : Icons.arrow_back,
                color: Colors.white.withValues(alpha: 0.85),
              ),
            ),
          ),
          PositionedDirectional(
            start: 20,
            end: 20,
            bottom: 14,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  address(property, emirate),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          color: AppColors.accent,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 9,
                          letterSpacing: 2.4,
                          color: AppColors.accent,
                        ),
                ),
                const SizedBox(height: 4),
                Text(
                  name,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 22,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        )
                      : GoogleFonts.cinzel(fontSize: 24, color: Colors.white),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String address(Map<String, dynamic> property, String emirate) {
    final addr = property['address']?.toString() ?? '';
    if (addr.isNotEmpty && emirate.isNotEmpty) return '$emirate · $addr';
    return emirate.isNotEmpty ? emirate : addr;
  }
}

// ─── Spec strip ─────────────────────────────────────────────────────────────

class _SpecStrip extends StatelessWidget {
  final int totalUnits;
  final double occupancy;
  final int buildingCount;
  final String type;
  final MiftahColors m;
  final _L l;

  const _SpecStrip({
    required this.totalUnits,
    required this.occupancy,
    required this.buildingCount,
    required this.type,
    required this.m,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final cells = [
      (value: '$totalUnits', label: l.units),
      (value: '${(occupancy * 100).round()}%', label: l.let),
      (value: '$buildingCount', label: l.buildings),
      (value: type.isEmpty ? '—' : type, label: l.type),
    ];
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border(bottom: BorderSide(color: m.border)),
      ),
      child: Row(
        children: [
          for (var i = 0; i < cells.length; i++)
            Expanded(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  vertical: 12,
                  horizontal: 6,
                ),
                decoration: BoxDecoration(
                  border: BorderDirectional(
                    end: i < cells.length - 1
                        ? BorderSide(color: m.border)
                        : BorderSide.none,
                  ),
                ),
                child: Column(
                  children: [
                    Text(
                      cells[i].value,
                      textAlign: TextAlign.center,
                      style: GoogleFonts.cinzel(
                        fontSize: 16,
                        color: m.textPrimary,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 3),
                    Text(
                      l.ar ? cells[i].label : cells[i].label.toUpperCase(),
                      textAlign: TextAlign.center,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 10,
                              color: m.textMuted,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 8,
                              letterSpacing: 1.4,
                              color: m.textMuted,
                            ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ─── Section header ─────────────────────────────────────────────────────────

class _SectionHeader extends StatelessWidget {
  final String label;
  final Widget? trailing;
  const _SectionHeader({required this.label, this.trailing});

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          ar ? label : label.toUpperCase(),
          style: ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 13,
                  color: AppColors.accentDark,
                )
              : GoogleFonts.josefinSans(
                  fontSize: 10,
                  letterSpacing: 2.4,
                  color: AppColors.accentDark,
                ),
        ),
        ?trailing,
      ],
    );
  }
}

// ─── Unit legend + grid ─────────────────────────────────────────────────────

class _UnitLegend extends StatelessWidget {
  final _L l;
  final MiftahColors m;
  const _UnitLegend({required this.l, required this.m});

  @override
  Widget build(BuildContext context) {
    Widget dot(Color c, String label) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 7,
          height: 7,
          decoration: BoxDecoration(
            color: c,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        const SizedBox(width: 4),
        Text(
          label,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(fontSize: 10.5, color: m.textMuted)
              : GoogleFonts.josefinSans(fontSize: 10, color: m.textMuted),
        ),
      ],
    );
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        dot(m.success, l.legendLet),
        const SizedBox(width: 10),
        dot(AppColors.warning, l.legendMaintenance),
        const SizedBox(width: 10),
        dot(m.borderStrong, l.legendVacant),
      ],
    );
  }
}

class _UnitTile extends StatelessWidget {
  final Map<String, dynamic> unit;
  final _L l;
  final VoidCallback onTap;

  const _UnitTile({required this.unit, required this.l, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = unit['status'] ?? 'VACANT';
    final topColor = switch (status) {
      'OCCUPIED' => m.success,
      'MAINTENANCE' => AppColors.warning,
      _ => m.borderStrong,
    };
    final subLabel = switch (status) {
      'MAINTENANCE' => l.maintenanceShort,
      'VACANT' => l.vacantShort,
      _ => (unit['type'] ?? '').toString(),
    };

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(10),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(height: 3, color: topColor),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    unit['unitNumber']?.toString() ?? '-',
                    style: GoogleFonts.cinzel(
                      fontSize: 13,
                      color: m.textPrimary,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subLabel,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 10,
                            color: status == 'MAINTENANCE'
                                ? AppColors.warning
                                : m.textMuted,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 9.5,
                            color: status == 'MAINTENANCE'
                                ? AppColors.warning
                                : m.textMuted,
                          ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Rent roll ──────────────────────────────────────────────────────────────

class _RentRollCard extends StatelessWidget {
  final List<dynamic> units;
  final MiftahColors m;
  final _L l;
  const _RentRollCard({required this.units, required this.m, required this.l});

  @override
  Widget build(BuildContext context) {
    double monthlyOf(bool Function(dynamic) test) => units
        .where(test)
        .fold<double>(
          0,
          (s, u) => s + (((u['annualRent'] ?? 0) as num).toDouble() / 12),
        );

    final total = monthlyOf((_) => true);
    final fromOccupied = monthlyOf((u) => u['status'] == 'OCCUPIED');
    final vacantPotential = monthlyOf((u) => u['status'] == 'VACANT');

    Widget row(String label, double amount, {Color? tone, bool last = false}) {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 9),
        decoration: BoxDecoration(
          border: last ? null : Border(bottom: BorderSide(color: m.divider)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              label,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 13,
                      color: m.textSecondary,
                    ),
            ),
            Text(
              Formatters.currencyCompact(amount),
              style: GoogleFonts.cinzel(
                fontSize: 15,
                color: tone ?? m.textPrimary,
              ),
            ),
          ],
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        children: [
          row(l.monthlyRentRoll, total),
          row(l.fromOccupiedUnits, fromOccupied, tone: m.success),
          row(
            l.vacantPotential,
            vacantPotential,
            tone: vacantPotential > 0 ? AppColors.warning : null,
            last: true,
          ),
        ],
      ),
    );
  }
}

// ─── Contacts ───────────────────────────────────────────────────────────────

class _ContactRow extends StatelessWidget {
  final Map<String, dynamic> contact;
  final _L l;
  final MiftahColors m;
  final VoidCallback onDelete;

  const _ContactRow({
    required this.contact,
    required this.l,
    required this.m,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          CircleAvatar(
            backgroundColor: AppColors.accent.withValues(alpha: 0.12),
            child: const Icon(
              Icons.person_outline,
              color: AppColors.accentDark,
              size: 20,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  contact['name'] ?? l.contact,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                ),
                if (contact['role'] != null)
                  Text(
                    contact['role'],
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 12,
                            color: m.textSecondary,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 12,
                            color: m.textSecondary,
                          ),
                  ),
                if (contact['phone'] != null)
                  Text(
                    contact['phone'],
                    style: GoogleFonts.josefinSans(
                      fontSize: 12,
                      color: m.textMuted,
                    ),
                  ),
                if (contact['email'] != null)
                  Text(
                    contact['email'],
                    style: GoogleFonts.josefinSans(
                      fontSize: 12,
                      color: m.textMuted,
                    ),
                  ),
              ],
            ),
          ),
          IconButton(
            icon: Icon(Icons.delete_outline, size: 18, color: m.danger),
            onPressed: onDelete,
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get property => ar ? 'العقار' : 'Property';
  String get notFound => ar ? 'العقار غير موجود' : 'Property not found';
  String get loadError =>
      ar ? 'تعذّر تحميل تفاصيل العقار' : 'Failed to load property details';

  // Spec strip
  String get units => ar ? 'الوحدات' : 'Units';
  String get let => ar ? 'مؤجّر' : 'Let';
  String get buildings => ar ? 'المباني' : 'Buildings';
  String get type => ar ? 'النوع' : 'Type';

  // Units section
  String get unitsSection => ar ? 'الوحدات' : 'Units';
  String get legendLet => ar ? 'مؤجّرة' : 'Let';
  String get legendMaintenance => ar ? 'صيانة' : 'Maintenance';
  String get legendVacant => ar ? 'شاغرة' : 'Vacant';
  String get maintenanceShort => ar ? 'صيانة' : 'Maint.';
  String get vacantShort => ar ? 'شاغرة' : 'Vacant';
  String get noUnitsYet => ar ? 'لا توجد وحدات بعد' : 'No units yet';
  String get addUnitsPrompt =>
      ar ? 'أضف وحدات لهذا العقار' : 'Add units to this property';

  // Rent roll
  String get rentRollSection =>
      ar ? 'إيرادات الإيجار · شهريًا' : 'Rent roll · monthly';
  String get monthlyRentRoll =>
      ar ? 'إجمالي الإيجار الشهري' : 'Monthly rent roll';
  String get fromOccupiedUnits =>
      ar ? 'من الوحدات المؤجّرة' : 'From occupied units';
  String get vacantPotential =>
      ar ? 'إمكانية الوحدات الشاغرة' : 'Vacant potential';

  // Contacts / buildings
  String get contactsSection => ar ? 'جهات الاتصال' : 'Contacts';
  String get buildingsSection => ar ? 'المباني' : 'Buildings';
  String get add => ar ? 'إضافة' : 'Add';
  String get noContacts => ar ? 'لا توجد جهات اتصال' : 'No contacts';
  String get noBuildings => ar ? 'لا توجد مبانٍ' : 'No buildings';
  String get building => ar ? 'مبنى' : 'Building';
  String get contact => ar ? 'جهة اتصال' : 'Contact';
  String get deleteContact => ar ? 'حذف جهة الاتصال' : 'Delete Contact';
  String get deleteContactConfirm =>
      ar ? 'هل تريد إزالة جهة الاتصال هذه؟' : 'Remove this contact?';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get delete => ar ? 'حذف' : 'Delete';

  // Create-unit sheet
  String get newUnit => ar ? 'وحدة جديدة' : 'New Unit';
  String get unitNumber => ar ? 'رقم الوحدة' : 'Unit Number';
  String get required => ar ? 'مطلوب' : 'Required';
  String get sizeSqft => ar ? 'المساحة (قدم مربع)' : 'Size (sq ft)';
  String get annualRent => ar ? 'الإيجار السنوي (درهم)' : 'Annual Rent (AED)';
  String get createUnit => ar ? 'إنشاء الوحدة' : 'Create Unit';
  String get createUnitFailed =>
      ar ? 'تعذّر إنشاء الوحدة' : 'Failed to create unit';

  // Add-contact sheet
  String get newContact => ar ? 'جهة اتصال جديدة' : 'New Contact';
  String get name => ar ? 'الاسم' : 'Name';
  String get role => ar ? 'الدور' : 'Role';
  String get phone => ar ? 'الهاتف' : 'Phone';
  String get email => ar ? 'البريد الإلكتروني' : 'Email';
  String get addContact => ar ? 'إضافة جهة الاتصال' : 'Add Contact';
  String get addContactFailed =>
      ar ? 'تعذّر إضافة جهة الاتصال' : 'Failed to add contact';
}
