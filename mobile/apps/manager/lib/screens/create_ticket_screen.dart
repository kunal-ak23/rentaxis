import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _unitServiceProvider = Provider<UnitService>((ref) {
  final client = ref.watch(apiClientProvider);
  return UnitService(client.dio);
});

final _renterServiceProvider = Provider<RenterService>((ref) {
  final client = ref.watch(apiClientProvider);
  return RenterService(client.dio);
});

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Category/priority terms mirror the renter app's create_ticket_screen so
/// vocabulary stays identical across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String get newTicket => ar ? 'طلب جديد' : 'New Ticket';
  String get property => ar ? 'العقار' : 'Property';
  String get unitOptional => ar ? 'الوحدة (اختياري)' : 'Unit (optional)';
  String unitLabel(String n) => ar ? 'وحدة $n' : 'Unit $n';
  String get onBehalfOf =>
      ar ? 'نيابة عن (اختياري)' : 'On behalf of (optional)';
  String get category => ar ? 'الفئة' : 'Category';
  String get title => ar ? 'العنوان' : 'Title';
  String get titleRequired => ar ? 'العنوان مطلوب' : 'Title is required';
  String get description => ar ? 'الوصف' : 'Description';
  String get descriptionRequired =>
      ar ? 'الوصف مطلوب' : 'Description is required';
  String get priority => ar ? 'الأولوية' : 'Priority';
  String get attachments => ar ? 'المرفقات' : 'Attachments';
  String get add => ar ? 'إضافة' : 'Add';
  String get camera => ar ? 'الكاميرا' : 'Camera';
  String get gallery => ar ? 'المعرض' : 'Gallery';
  String get file => ar ? 'ملف' : 'File';
  String get submitTicket => ar ? 'إرسال الطلب' : 'Submit Ticket';
  String get ticketCreated =>
      ar ? 'تم إنشاء الطلب بنجاح' : 'Ticket created successfully';
  String get ticketCreateFailed =>
      ar ? 'فشل إنشاء الطلب' : 'Failed to create ticket';

  String category_(String value) {
    switch (value) {
      case 'PLUMBING':
        return ar ? 'سباكة' : 'Plumbing';
      case 'ELECTRICAL':
        return ar ? 'كهرباء' : 'Electrical';
      case 'HVAC':
        return ar ? 'تكييف' : 'HVAC';
      case 'APPLIANCE':
        return ar ? 'أجهزة' : 'Appliance';
      case 'STRUCTURAL':
        return ar ? 'إنشائي' : 'Structural';
      case 'PEST_CONTROL':
        return ar ? 'مكافحة حشرات' : 'Pest Control';
      case 'CLEANING':
        return ar ? 'تنظيف' : 'Cleaning';
      case 'SECURITY':
        return ar ? 'أمن' : 'Security';
      case 'OTHER':
        return ar ? 'أخرى' : 'Other';
      default:
        return value.replaceAll('_', ' ');
    }
  }

  String priority_(String value) {
    switch (value) {
      case 'LOW':
        return ar ? 'منخفضة' : 'LOW';
      case 'MEDIUM':
        return ar ? 'متوسطة' : 'MEDIUM';
      case 'HIGH':
        return ar ? 'مرتفعة' : 'HIGH';
      case 'URGENT':
        return ar ? 'عاجلة' : 'URGENT';
      default:
        return value;
    }
  }
}

class CreateTicketScreen extends ConsumerStatefulWidget {
  const CreateTicketScreen({super.key});

  @override
  ConsumerState<CreateTicketScreen> createState() => _CreateTicketScreenState();
}

class _CreateTicketScreenState extends ConsumerState<CreateTicketScreen> {
  final _formKey = GlobalKey<FormState>();
  final _titleCtrl = TextEditingController();
  final _descCtrl = TextEditingController();

  List<dynamic> _properties = [];
  List<dynamic> _units = [];
  List<dynamic> _renters = [];
  String? _selectedPropertyId;
  String? _selectedUnitId;
  String? _selectedRenterId;
  String _selectedCategory = 'PLUMBING';
  String _selectedPriority = 'MEDIUM';
  final List<String> _attachmentPaths = [];
  bool _isLoading = false;
  bool _isSubmitting = false;

  // Backend TicketCategory enum values, mirroring the renter app's list.
  final _categories = [
    'PLUMBING',
    'ELECTRICAL',
    'HVAC',
    'APPLIANCE',
    'STRUCTURAL',
    'PEST_CONTROL',
    'CLEANING',
    'SECURITY',
    'OTHER',
  ];

  final _categoryIcons = {
    'PLUMBING': Icons.plumbing_outlined,
    'ELECTRICAL': Icons.electrical_services_outlined,
    'HVAC': Icons.ac_unit_outlined,
    'APPLIANCE': Icons.kitchen_outlined,
    'STRUCTURAL': Icons.foundation,
    'PEST_CONTROL': Icons.pest_control_outlined,
    'CLEANING': Icons.cleaning_services_outlined,
    'SECURITY': Icons.security_outlined,
    'OTHER': Icons.more_horiz,
  };

  final _priorities = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];

  @override
  void initState() {
    super.initState();
    _loadDropdowns();
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadDropdowns() async {
    setState(() => _isLoading = true);
    try {
      final propService = ref.read(_propertyServiceProvider);
      final renterService = ref.read(_renterServiceProvider);
      final results = await Future.wait([
        propService.getProperties(),
        renterService.getRenters(),
      ]);
      if (!mounted) return;
      setState(() {
        _properties = results[0];
        _renters = results[1];
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
    }
  }

  Future<void> _loadUnits(String propertyId) async {
    try {
      final unitService = ref.read(_unitServiceProvider);
      final units = await unitService.getUnitsByProperty(propertyId);
      if (!mounted) return;
      setState(() {
        _units = units;
        _selectedUnitId = null;
      });
    } catch (_) {}
  }

  Future<void> _addAttachment() async {
    final l = _L(context.isAr);
    final m = context.miftah;
    final source = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: m.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(
                Icons.camera_alt_outlined,
                color: AppColors.accent,
              ),
              title: Text(
                l.camera,
                style: GoogleFonts.josefinSans(color: m.textPrimary),
              ),
              onTap: () => Navigator.pop(ctx, 'camera'),
            ),
            ListTile(
              leading: const Icon(
                Icons.photo_library_outlined,
                color: AppColors.accent,
              ),
              title: Text(
                l.gallery,
                style: GoogleFonts.josefinSans(color: m.textPrimary),
              ),
              onTap: () => Navigator.pop(ctx, 'gallery'),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file, color: AppColors.accent),
              title: Text(
                l.file,
                style: GoogleFonts.josefinSans(color: m.textPrimary),
              ),
              onTap: () => Navigator.pop(ctx, 'file'),
            ),
          ],
        ),
      ),
    );
    if (source == null) return;

    if (source == 'camera' || source == 'gallery') {
      final picker = ImagePicker();
      final image = await picker.pickImage(
        source: source == 'camera' ? ImageSource.camera : ImageSource.gallery,
        imageQuality: 80,
      );
      if (image != null) {
        setState(() => _attachmentPaths.add(image.path));
      }
    } else {
      final result = await FilePicker.platform.pickFiles(allowMultiple: true);
      if (result != null) {
        for (final file in result.files) {
          if (file.path != null) {
            setState(() => _attachmentPaths.add(file.path!));
          }
        }
      }
    }
  }

  /// Locale-aware renter display name; CreateTicketDTO's onBehalfOf is a
  /// free-text name (web sends the typed name), not a renter id.
  String _renterDisplayName(dynamic r, _L l) =>
      ((l.ar ? (r['nameAr'] ?? r['nameEn']) : r['nameEn']) ?? r['email'] ?? '')
          .toString();

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final l = _L(context.isAr);

    setState(() => _isSubmitting = true);
    try {
      final ticketService = ref.read(_ticketServiceProvider);

      final selectedRenter = _selectedRenterId == null
          ? null
          : _renters.firstWhere(
              (r) => r['id'] == _selectedRenterId,
              orElse: () => null,
            );
      final onBehalfOf = selectedRenter == null
          ? ''
          : _renterDisplayName(selectedRenter, l);

      final ticket = await ticketService.createTicket({
        'title': _titleCtrl.text.trim(),
        'description': _descCtrl.text.trim(),
        'category': _selectedCategory,
        'priority': _selectedPriority,
        if (_selectedPropertyId != null) 'propertyId': _selectedPropertyId,
        if (_selectedUnitId != null) 'unitId': _selectedUnitId,
        if (onBehalfOf.isNotEmpty) 'onBehalfOf': onBehalfOf,
      });

      final ticketId = ticket['id'];
      if (ticketId != null) {
        for (final path in _attachmentPaths) {
          try {
            await ticketService.uploadAttachment(ticketId, path);
          } catch (_) {}
        }
      }

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.ticketCreated),
            backgroundColor: AppColors.success,
          ),
        );
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.ticketCreateFailed),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: m.surface,
        foregroundColor: m.textPrimary,
        elevation: 0,
        title: Text(
          l.newTicket,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(fontWeight: FontWeight.w600)
              : GoogleFonts.cinzel(fontSize: 16, letterSpacing: 1.2),
        ),
      ),
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(color: AppColors.accent),
            )
          : LoadingOverlay(
              isLoading: _isSubmitting,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _dropdownContainer(
                        m: m,
                        child: DropdownButtonFormField<String>(
                          initialValue: _selectedPropertyId,
                          decoration: InputDecoration(
                            labelText: l.property,
                            prefixIcon: const Icon(
                              Icons.apartment_outlined,
                              color: AppColors.accentDark,
                            ),
                            border: InputBorder.none,
                          ),
                          items: _properties
                              .map(
                                (p) => DropdownMenuItem<String>(
                                  value: p['id'],
                                  child: Text(
                                    p['name'] ?? '',
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                              )
                              .toList(),
                          onChanged: (v) {
                            setState(() => _selectedPropertyId = v);
                            if (v != null) _loadUnits(v);
                          },
                        ),
                      ),
                      const SizedBox(height: 14),

                      _dropdownContainer(
                        m: m,
                        child: DropdownButtonFormField<String>(
                          initialValue: _selectedUnitId,
                          decoration: InputDecoration(
                            labelText: l.unitOptional,
                            prefixIcon: const Icon(
                              Icons.door_front_door_outlined,
                              color: AppColors.accentDark,
                            ),
                            border: InputBorder.none,
                          ),
                          items: _units
                              .map(
                                (u) => DropdownMenuItem<String>(
                                  value: u['id'],
                                  child: Text(
                                    l.unitLabel(
                                      u['unitNumber']?.toString() ?? '',
                                    ),
                                  ),
                                ),
                              )
                              .toList(),
                          onChanged: (v) => setState(() => _selectedUnitId = v),
                        ),
                      ),
                      const SizedBox(height: 14),

                      _dropdownContainer(
                        m: m,
                        child: DropdownButtonFormField<String>(
                          initialValue: _selectedRenterId,
                          decoration: InputDecoration(
                            labelText: l.onBehalfOf,
                            prefixIcon: const Icon(
                              Icons.person_outline,
                              color: AppColors.accentDark,
                            ),
                            border: InputBorder.none,
                          ),
                          items: _renters
                              .map(
                                (r) => DropdownMenuItem<String>(
                                  value: r['id'],
                                  child: Text(
                                    _renterDisplayName(r, l),
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                              )
                              .toList(),
                          onChanged: (v) =>
                              setState(() => _selectedRenterId = v),
                        ),
                      ),
                      const SizedBox(height: 24),

                      _sectionLabel(l.category, l, m),
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: _categories.map((cat) {
                          final isSelected = _selectedCategory == cat;
                          return GestureDetector(
                            onTap: () =>
                                setState(() => _selectedCategory = cat),
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 150),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 9,
                              ),
                              decoration: BoxDecoration(
                                color: isSelected
                                    ? AppColors.accent
                                    : m.surface,
                                borderRadius: BorderRadius.circular(999),
                                border: Border.all(
                                  color: isSelected
                                      ? AppColors.accent
                                      : m.border,
                                ),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    _categoryIcons[cat] ??
                                        Icons.category_outlined,
                                    size: 15,
                                    color: isSelected
                                        ? AppColors.primary
                                        : m.textSecondary,
                                  ),
                                  const SizedBox(width: 6),
                                  Text(
                                    l.category_(cat),
                                    style: GoogleFonts.josefinSans(
                                      fontSize: 12,
                                      fontWeight: isSelected
                                          ? FontWeight.w600
                                          : FontWeight.w400,
                                      color: isSelected
                                          ? AppColors.primary
                                          : m.textSecondary,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 24),

                      TextFormField(
                        controller: _titleCtrl,
                        style: GoogleFonts.josefinSans(color: m.textPrimary),
                        decoration: InputDecoration(
                          labelText: l.title,
                          prefixIcon: const Icon(
                            Icons.title,
                            color: AppColors.accentDark,
                          ),
                        ),
                        validator: (v) => v == null || v.trim().isEmpty
                            ? l.titleRequired
                            : null,
                      ),
                      const SizedBox(height: 16),

                      TextFormField(
                        controller: _descCtrl,
                        maxLines: 4,
                        style: GoogleFonts.josefinSans(color: m.textPrimary),
                        decoration: InputDecoration(
                          labelText: l.description,
                          alignLabelWithHint: true,
                          prefixIcon: const Padding(
                            padding: EdgeInsets.only(bottom: 60),
                            child: Icon(
                              Icons.description_outlined,
                              color: AppColors.accentDark,
                            ),
                          ),
                        ),
                        validator: (v) => v == null || v.trim().isEmpty
                            ? l.descriptionRequired
                            : null,
                      ),
                      const SizedBox(height: 24),

                      _sectionLabel(l.priority, l, m),
                      const SizedBox(height: 12),
                      Row(
                        children: _priorities.map((p) {
                          final isSelected = _selectedPriority == p;
                          final color = StatusHelper.getPriorityColor(p);
                          return Expanded(
                            child: GestureDetector(
                              onTap: () =>
                                  setState(() => _selectedPriority = p),
                              child: Container(
                                margin: EdgeInsetsDirectional.only(
                                  end: p != 'URGENT' ? 8 : 0,
                                ),
                                padding: const EdgeInsets.symmetric(
                                  vertical: 10,
                                ),
                                decoration: BoxDecoration(
                                  color: isSelected
                                      ? color.withValues(alpha: 0.15)
                                      : m.surface,
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: isSelected ? color : m.border,
                                    width: isSelected ? 2 : 1,
                                  ),
                                ),
                                child: Text(
                                  l.priority_(p),
                                  textAlign: TextAlign.center,
                                  style: GoogleFonts.josefinSans(
                                    fontSize: 12,
                                    letterSpacing: l.ar ? 0 : 0.8,
                                    fontWeight: isSelected
                                        ? FontWeight.w700
                                        : FontWeight.w500,
                                    color: isSelected ? color : m.textSecondary,
                                  ),
                                ),
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 24),

                      Row(
                        children: [
                          _sectionLabel(l.attachments, l, m),
                          const Spacer(),
                          TextButton.icon(
                            onPressed: _addAttachment,
                            icon: const Icon(
                              Icons.add_photo_alternate_outlined,
                              size: 18,
                              color: AppColors.accentDark,
                            ),
                            label: Text(
                              l.add,
                              style: GoogleFonts.josefinSans(
                                color: AppColors.accentDark,
                              ),
                            ),
                          ),
                        ],
                      ),
                      if (_attachmentPaths.isNotEmpty) ...[
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: _attachmentPaths.asMap().entries.map((
                            entry,
                          ) {
                            return Chip(
                              backgroundColor: m.surfaceAlt,
                              side: BorderSide(color: m.border),
                              avatar: const Icon(
                                Icons.insert_drive_file_outlined,
                                size: 16,
                                color: AppColors.accentDark,
                              ),
                              label: Text(
                                entry.value.split('/').last,
                                style: GoogleFonts.josefinSans(
                                  fontSize: 12,
                                  color: m.textPrimary,
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                              deleteIcon: const Icon(Icons.close, size: 16),
                              onDeleted: () => setState(
                                () => _attachmentPaths.removeAt(entry.key),
                              ),
                            );
                          }).toList(),
                        ),
                      ],
                      const SizedBox(height: 32),

                      GoldButton(
                        label: l.submitTicket,
                        height: 50,
                        onPressed: _isSubmitting ? null : _submit,
                      ),
                    ],
                  ),
                ),
              ),
            ),
    );
  }

  Widget _sectionLabel(String text, _L l, MiftahColors m) {
    return Text(
      text,
      style: l.ar
          ? GoogleFonts.notoNaskhArabic(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            )
          : GoogleFonts.josefinSans(
              fontSize: 11,
              letterSpacing: 1.6,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            ),
    );
  }

  Widget _dropdownContainer({required MiftahColors m, required Widget child}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
      ),
      child: child,
    );
  }
}
