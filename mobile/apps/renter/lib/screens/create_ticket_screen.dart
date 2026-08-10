import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

final _myLeasesForTicketProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) {
  final service = ref.watch(_leaseServiceProvider);
  return service.getMyLeases();
});

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get createTicket => ar ? 'إنشاء طلب' : 'Create Ticket';
  String get propertyUnit => ar ? 'العقار / الوحدة' : 'Property / Unit';
  String get property => ar ? 'عقار' : 'Property';
  String get title => ar ? 'العنوان' : 'Title';
  String get titleHint =>
      ar ? 'وصف موجز للمشكلة' : 'Brief description of the issue';
  String get titleRequired => ar ? 'العنوان مطلوب' : 'Title is required';
  String get category => ar ? 'الفئة' : 'Category';
  String get description => ar ? 'الوصف' : 'Description';
  String get descriptionHint => ar
      ? 'قدّم مزيدًا من التفاصيل حول المشكلة...'
      : 'Provide more details about the issue...';
  String get priority => ar ? 'الأولوية' : 'Priority';
  String get attachments => ar ? 'المرفقات' : 'Attachments';
  String get camera => ar ? 'الكاميرا' : 'Camera';
  String get gallery => ar ? 'المعرض' : 'Gallery';
  String get document => ar ? 'مستند' : 'Document';
  String get submitTicket => ar ? 'إرسال الطلب' : 'Submit Ticket';
  String get cameraPermissionRequired =>
      ar ? 'إذن الكاميرا مطلوب' : 'Camera permission required';
  String get selectCategory =>
      ar ? 'الرجاء اختيار فئة' : 'Please select a category';
  String get selectPropertyUnit =>
      ar ? 'الرجاء اختيار العقار / الوحدة' : 'Please select your property/unit';
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
        return value;
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
  final _titleController = TextEditingController();
  final _descriptionController = TextEditingController();

  String? _selectedLeaseId;
  String? _selectedPropertyId;
  String? _selectedUnitId;
  String? _selectedCategory;
  String _selectedPriority = 'MEDIUM';
  final List<File> _attachments = [];
  bool _isSubmitting = false;

  final _categories = [
    {'label': 'Plumbing', 'value': 'PLUMBING', 'icon': Icons.plumbing},
    {
      'label': 'Electrical',
      'value': 'ELECTRICAL',
      'icon': Icons.electrical_services,
    },
    {'label': 'HVAC', 'value': 'HVAC', 'icon': Icons.ac_unit},
    {'label': 'Appliance', 'value': 'APPLIANCE', 'icon': Icons.kitchen},
    {'label': 'Structural', 'value': 'STRUCTURAL', 'icon': Icons.foundation},
    {
      'label': 'Pest Control',
      'value': 'PEST_CONTROL',
      'icon': Icons.pest_control,
    },
    {'label': 'Cleaning', 'value': 'CLEANING', 'icon': Icons.cleaning_services},
    {'label': 'Security', 'value': 'SECURITY', 'icon': Icons.security},
    {'label': 'Other', 'value': 'OTHER', 'icon': Icons.more_horiz},
  ];

  final _priorities = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];

  @override
  void dispose() {
    _titleController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _pickFromCamera() async {
    final status = await Permission.camera.request();
    if (!status.isGranted) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_L(context.isAr).cameraPermissionRequired)),
        );
      }
      return;
    }

    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: ImageSource.camera,
      maxWidth: 1920,
      maxHeight: 1080,
      imageQuality: 80,
    );
    if (image != null) {
      setState(() => _attachments.add(File(image.path)));
    }
  }

  Future<void> _pickFromGallery() async {
    final status = await Permission.photos.request();
    // On some platforms, photos permission might not exist
    if (!status.isGranted && !status.isLimited) {
      // Try picking anyway - some platforms don't need explicit permission
    }

    final picker = ImagePicker();
    final images = await picker.pickMultiImage(
      maxWidth: 1920,
      maxHeight: 1080,
      imageQuality: 80,
    );
    if (images.isNotEmpty) {
      setState(() {
        for (final image in images) {
          _attachments.add(File(image.path));
        }
      });
    }
  }

  Future<void> _pickDocument() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['pdf', 'doc', 'docx'],
      allowMultiple: true,
    );
    if (result != null) {
      setState(() {
        for (final file in result.files) {
          if (file.path != null) {
            _attachments.add(File(file.path!));
          }
        }
      });
    }
  }

  void _removeAttachment(int index) {
    setState(() => _attachments.removeAt(index));
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final l = _L(context.isAr);
    if (_selectedCategory == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.selectCategory)));
      return;
    }
    // Backend requires a property on POST /v1/tickets — a null propertyId
    // is rejected, so surface the problem here instead of a generic failure.
    if (_selectedPropertyId == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.selectPropertyUnit)));
      return;
    }

    setState(() => _isSubmitting = true);

    try {
      final data = <String, dynamic>{
        'title': _titleController.text.trim(),
        'description': _descriptionController.text.trim(),
        'category': _selectedCategory,
        'priority': _selectedPriority,
        'propertyId': _selectedPropertyId,
        if (_selectedUnitId != null) 'unitId': _selectedUnitId,
      };

      final ticket = await ref.read(_ticketServiceProvider).createTicket(data);

      // Upload attachments
      if (_attachments.isNotEmpty && ticket['id'] != null) {
        final service = ref.read(_ticketServiceProvider);
        for (final file in _attachments) {
          try {
            await service.uploadAttachment(ticket['id'], file.path);
          } catch (_) {
            // Continue uploading remaining attachments
          }
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
    }

    if (mounted) setState(() => _isSubmitting = false);
  }

  @override
  Widget build(BuildContext context) {
    final leasesAsync = ref.watch(_myLeasesForTicketProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: Scaffold(
        appBar: AppBar(
          backgroundColor: m.surface,
          foregroundColor: m.textPrimary,
          elevation: 0,
          title: Text(l.createTicket),
        ),
        body: LoadingOverlay(
          isLoading: _isSubmitting,
          child: Form(
            key: _formKey,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 150),
              children: [
                // Property/Unit selector
                leasesAsync.when(
                  data: (leases) {
                    if (leases.length == 1 && _selectedLeaseId == null) {
                      WidgetsBinding.instance.addPostFrameCallback((_) {
                        if (mounted) {
                          setState(() {
                            _selectedLeaseId = leases[0]['id'];
                            _selectedPropertyId =
                                leases[0]['propertyId'] ??
                                leases[0]['property']?['id'];
                            _selectedUnitId =
                                leases[0]['unitId'] ?? leases[0]['unit']?['id'];
                          });
                        }
                      });
                    }

                    if (leases.isEmpty) return const SizedBox.shrink();

                    return DropdownButtonFormField<String>(
                      initialValue: _selectedLeaseId,
                      decoration: InputDecoration(
                        labelText: l.propertyUnit,
                        prefixIcon: const Icon(Icons.apartment, size: 20),
                      ),
                      items: leases.map<DropdownMenuItem<String>>((lease) {
                        final propName =
                            lease['propertyName'] ??
                            lease['property']?['nameEn'] ??
                            l.property;
                        final unitId =
                            lease['unitIdentifier'] ??
                            lease['unit']?['unitNumber'] ??
                            '';
                        final display = unitId.isNotEmpty
                            ? '$propName - $unitId'
                            : propName;
                        return DropdownMenuItem(
                          value: lease['id'] as String,
                          child: Text(display, overflow: TextOverflow.ellipsis),
                        );
                      }).toList(),
                      onChanged: (value) {
                        final lease = leases.firstWhere(
                          (lse) => lse['id'] == value,
                        );
                        setState(() {
                          _selectedLeaseId = value;
                          _selectedPropertyId =
                              lease['propertyId'] ?? lease['property']?['id'];
                          _selectedUnitId =
                              lease['unitId'] ?? lease['unit']?['id'];
                        });
                      },
                    );
                  },
                  loading: () => const LinearProgressIndicator(),
                  error: (_, _) => const SizedBox.shrink(),
                ),
                const SizedBox(height: 16),

                // Title (first for quick input)
                TextFormField(
                  controller: _titleController,
                  decoration: InputDecoration(
                    labelText: l.title,
                    hintText: l.titleHint,
                    prefixIcon: const Icon(Icons.title, size: 20),
                  ),
                  validator: (value) {
                    if (value == null || value.trim().isEmpty) {
                      return l.titleRequired;
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Category grid (compact)
                Text(
                  l.category,
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(color: m.textSecondary),
                ),
                const SizedBox(height: 8),
                GridView.builder(
                  // Nested in a scroll view: without this the sliver auto-pads
                  // with MediaQuery.padding, which under extendBody carries the
                  // floating nav height and opens a gap below the content.
                  padding: EdgeInsets.zero,
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 3,
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                    childAspectRatio: 1.5,
                  ),
                  itemCount: _categories.length,
                  itemBuilder: (context, index) {
                    final cat = _categories[index];
                    final isSelected = _selectedCategory == cat['value'];
                    return InkWell(
                      onTap: () => setState(
                        () => _selectedCategory = cat['value'] as String,
                      ),
                      borderRadius: BorderRadius.circular(10),
                      child: Container(
                        decoration: BoxDecoration(
                          color: isSelected
                              ? AppColors.primary.withValues(alpha: 0.1)
                              : m.surface,
                          borderRadius: BorderRadius.circular(10),
                          border: Border.all(
                            color: isSelected ? AppColors.primary : m.border,
                            width: isSelected ? 1.5 : 1,
                          ),
                        ),
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(
                              cat['icon'] as IconData,
                              color: isSelected
                                  ? AppColors.primary
                                  : m.textSecondary,
                              size: 20,
                            ),
                            const SizedBox(height: 2),
                            Text(
                              l.category_(cat['value'] as String),
                              style: TextStyle(
                                fontSize: 9,
                                fontWeight: isSelected
                                    ? FontWeight.w600
                                    : FontWeight.w400,
                                color: isSelected
                                    ? AppColors.primary
                                    : m.textSecondary,
                              ),
                              textAlign: TextAlign.center,
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 16),

                // Description
                TextFormField(
                  controller: _descriptionController,
                  decoration: InputDecoration(
                    labelText: l.description,
                    hintText: l.descriptionHint,
                    alignLabelWithHint: true,
                  ),
                  maxLines: 4,
                  minLines: 3,
                ),
                const SizedBox(height: 16),

                // Priority
                Text(
                  l.priority,
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(color: m.textSecondary),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  children: _priorities.map((priority) {
                    final isSelected = _selectedPriority == priority;
                    final color = StatusHelper.getPriorityColor(priority);
                    return ChoiceChip(
                      label: Text(l.priority_(priority)),
                      selected: isSelected,
                      selectedColor: color.withValues(alpha: 0.2),
                      backgroundColor: m.background,
                      side: BorderSide(color: isSelected ? color : m.border),
                      labelStyle: TextStyle(
                        color: isSelected ? color : m.textSecondary,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.w400,
                        fontSize: 12,
                      ),
                      onSelected: (_) =>
                          setState(() => _selectedPriority = priority),
                    );
                  }).toList(),
                ),
                const SizedBox(height: 20),

                // Attachments
                Text(
                  l.attachments,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    _AttachButton(
                      icon: Icons.camera_alt_outlined,
                      label: l.camera,
                      onTap: _pickFromCamera,
                    ),
                    const SizedBox(width: 10),
                    _AttachButton(
                      icon: Icons.photo_library_outlined,
                      label: l.gallery,
                      onTap: _pickFromGallery,
                    ),
                    const SizedBox(width: 10),
                    _AttachButton(
                      icon: Icons.attach_file,
                      label: l.document,
                      onTap: _pickDocument,
                    ),
                  ],
                ),

                if (_attachments.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  SizedBox(
                    height: 90,
                    child: ListView.builder(
                      scrollDirection: Axis.horizontal,
                      itemCount: _attachments.length,
                      itemBuilder: (context, index) {
                        final file = _attachments[index];
                        final isImage =
                            file.path.endsWith('.jpg') ||
                            file.path.endsWith('.jpeg') ||
                            file.path.endsWith('.png') ||
                            file.path.endsWith('.heic');

                        return Padding(
                          padding: const EdgeInsetsDirectional.only(end: 8),
                          child: Stack(
                            children: [
                              Container(
                                width: 80,
                                height: 80,
                                decoration: BoxDecoration(
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(color: m.border),
                                ),
                                clipBehavior: Clip.hardEdge,
                                child: isImage
                                    ? Image.file(file, fit: BoxFit.cover)
                                    : Column(
                                        mainAxisAlignment:
                                            MainAxisAlignment.center,
                                        children: [
                                          Icon(
                                            Icons.insert_drive_file,
                                            color: m.textMuted,
                                          ),
                                          Text(
                                            file.path.split('/').last,
                                            style: const TextStyle(fontSize: 8),
                                            maxLines: 2,
                                            overflow: TextOverflow.ellipsis,
                                            textAlign: TextAlign.center,
                                          ),
                                        ],
                                      ),
                              ),
                              PositionedDirectional(
                                top: -4,
                                end: -4,
                                child: GestureDetector(
                                  onTap: () => _removeAttachment(index),
                                  child: Container(
                                    width: 22,
                                    height: 22,
                                    decoration: BoxDecoration(
                                      color: m.danger,
                                      shape: BoxShape.circle,
                                    ),
                                    child: const Icon(
                                      Icons.close,
                                      color: Colors.white,
                                      size: 14,
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                ],
                const SizedBox(height: 32),

                // Submit
                SizedBox(
                  width: double.infinity,
                  height: 50,
                  child: ElevatedButton(
                    onPressed: _isSubmitting ? null : _submit,
                    child: Text(l.submitTicket),
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _AttachButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const _AttachButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: OutlinedButton.icon(
        onPressed: onTap,
        icon: Icon(icon, size: 18),
        label: Text(label, style: const TextStyle(fontSize: 12)),
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(vertical: 12),
        ),
      ),
    );
  }
}
