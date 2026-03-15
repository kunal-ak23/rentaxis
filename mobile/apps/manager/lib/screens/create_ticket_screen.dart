import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
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
  String _selectedCategory = 'MAINTENANCE';
  String _selectedPriority = 'MEDIUM';
  final List<String> _attachmentPaths = [];
  bool _isLoading = false;
  bool _isSubmitting = false;

  final _categories = [
    'MAINTENANCE',
    'PLUMBING',
    'ELECTRICAL',
    'HVAC',
    'PEST_CONTROL',
    'CLEANING',
    'SECURITY',
    'GENERAL',
    'OTHER',
  ];

  final _categoryIcons = {
    'MAINTENANCE': Icons.build_outlined,
    'PLUMBING': Icons.plumbing_outlined,
    'ELECTRICAL': Icons.electrical_services_outlined,
    'HVAC': Icons.ac_unit_outlined,
    'PEST_CONTROL': Icons.pest_control_outlined,
    'CLEANING': Icons.cleaning_services_outlined,
    'SECURITY': Icons.security_outlined,
    'GENERAL': Icons.chat_outlined,
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
    final source = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.camera_alt_outlined),
              title: const Text('Camera'),
              onTap: () => Navigator.pop(ctx, 'camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('Gallery'),
              onTap: () => Navigator.pop(ctx, 'gallery'),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file),
              title: const Text('File'),
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
        source:
            source == 'camera' ? ImageSource.camera : ImageSource.gallery,
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

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSubmitting = true);
    try {
      final ticketService = ref.read(_ticketServiceProvider);

      final ticket = await ticketService.createTicket({
        'title': _titleCtrl.text.trim(),
        'description': _descCtrl.text.trim(),
        'category': _selectedCategory,
        'priority': _selectedPriority,
        if (_selectedPropertyId != null) 'propertyId': _selectedPropertyId,
        if (_selectedUnitId != null) 'unitId': _selectedUnitId,
        if (_selectedRenterId != null) 'renterId': _selectedRenterId,
      });

      // Upload attachments
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
          const SnackBar(content: Text('Ticket created successfully')),
        );
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to create ticket')),
        );
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('New Ticket')),
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(color: AppColors.primary))
          : LoadingOverlay(
              isLoading: _isSubmitting,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Property dropdown
                      DropdownButtonFormField<String>(
                        value: _selectedPropertyId,
                        decoration: const InputDecoration(
                          labelText: 'Property',
                          prefixIcon: Icon(Icons.apartment_outlined),
                        ),
                        items: _properties
                            .map((p) => DropdownMenuItem<String>(
                                  value: p['id'],
                                  child: Text(p['name'] ?? '',
                                      overflow: TextOverflow.ellipsis),
                                ))
                            .toList(),
                        onChanged: (v) {
                          setState(() => _selectedPropertyId = v);
                          if (v != null) _loadUnits(v);
                        },
                      ),
                      const SizedBox(height: 16),

                      // Unit dropdown
                      DropdownButtonFormField<String>(
                        value: _selectedUnitId,
                        decoration: const InputDecoration(
                          labelText: 'Unit (optional)',
                          prefixIcon: Icon(Icons.door_front_door_outlined),
                        ),
                        items: _units
                            .map((u) => DropdownMenuItem<String>(
                                  value: u['id'],
                                  child: Text(
                                      'Unit ${u['unitNumber'] ?? ''}'),
                                ))
                            .toList(),
                        onChanged: (v) =>
                            setState(() => _selectedUnitId = v),
                      ),
                      const SizedBox(height: 16),

                      // Renter selector
                      DropdownButtonFormField<String>(
                        value: _selectedRenterId,
                        decoration: const InputDecoration(
                          labelText: 'On behalf of (optional)',
                          prefixIcon: Icon(Icons.person_outline),
                        ),
                        items: _renters
                            .map((r) => DropdownMenuItem<String>(
                                  value: r['id'],
                                  child: Text(r['name'] ?? r['email'] ?? '',
                                      overflow: TextOverflow.ellipsis),
                                ))
                            .toList(),
                        onChanged: (v) =>
                            setState(() => _selectedRenterId = v),
                      ),
                      const SizedBox(height: 24),

                      // Category grid
                      Text('Category',
                          style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: _categories.map((cat) {
                          final isSelected = _selectedCategory == cat;
                          return ChoiceChip(
                            label: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  _categoryIcons[cat] ??
                                      Icons.category_outlined,
                                  size: 16,
                                  color: isSelected
                                      ? Colors.white
                                      : AppColors.textSecondary,
                                ),
                                const SizedBox(width: 6),
                                Text(cat.replaceAll('_', ' ')),
                              ],
                            ),
                            selected: isSelected,
                            selectedColor: AppColors.primary,
                            labelStyle: TextStyle(
                              fontSize: 12,
                              color: isSelected
                                  ? Colors.white
                                  : AppColors.textPrimary,
                            ),
                            onSelected: (_) =>
                                setState(() => _selectedCategory = cat),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 24),

                      // Title
                      TextFormField(
                        controller: _titleCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Title',
                          prefixIcon: Icon(Icons.title),
                        ),
                        validator: (v) => v == null || v.trim().isEmpty
                            ? 'Title is required'
                            : null,
                      ),
                      const SizedBox(height: 16),

                      // Description
                      TextFormField(
                        controller: _descCtrl,
                        maxLines: 4,
                        decoration: const InputDecoration(
                          labelText: 'Description',
                          alignLabelWithHint: true,
                          prefixIcon: Padding(
                            padding: EdgeInsets.only(bottom: 60),
                            child: Icon(Icons.description_outlined),
                          ),
                        ),
                        validator: (v) => v == null || v.trim().isEmpty
                            ? 'Description is required'
                            : null,
                      ),
                      const SizedBox(height: 24),

                      // Priority
                      Text('Priority',
                          style: Theme.of(context).textTheme.titleMedium),
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
                                margin: EdgeInsets.only(
                                    right: p != 'URGENT' ? 8 : 0),
                                padding: const EdgeInsets.symmetric(
                                    vertical: 10),
                                decoration: BoxDecoration(
                                  color: isSelected
                                      ? color.withValues(alpha: 0.15)
                                      : AppColors.surface,
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: isSelected
                                        ? color
                                        : AppColors.border,
                                    width: isSelected ? 2 : 1,
                                  ),
                                ),
                                child: Text(
                                  p,
                                  textAlign: TextAlign.center,
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: isSelected
                                        ? FontWeight.w700
                                        : FontWeight.w500,
                                    color: isSelected
                                        ? color
                                        : AppColors.textSecondary,
                                  ),
                                ),
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 24),

                      // Attachments
                      Row(
                        children: [
                          Text('Attachments',
                              style:
                                  Theme.of(context).textTheme.titleMedium),
                          const Spacer(),
                          TextButton.icon(
                            onPressed: _addAttachment,
                            icon: const Icon(Icons.add_photo_alternate_outlined,
                                size: 18),
                            label: const Text('Add'),
                          ),
                        ],
                      ),
                      if (_attachmentPaths.isNotEmpty) ...[
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: _attachmentPaths
                              .asMap()
                              .entries
                              .map((entry) => Chip(
                                    avatar: const Icon(
                                        Icons.insert_drive_file_outlined,
                                        size: 16),
                                    label: Text(
                                      entry.value.split('/').last,
                                      style: const TextStyle(fontSize: 12),
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                    deleteIcon:
                                        const Icon(Icons.close, size: 16),
                                    onDeleted: () => setState(() =>
                                        _attachmentPaths
                                            .removeAt(entry.key)),
                                  ))
                              .toList(),
                        ),
                      ],
                      const SizedBox(height: 32),

                      // Submit
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton(
                          onPressed: _isSubmitting ? null : _submit,
                          style: ElevatedButton.styleFrom(
                            padding:
                                const EdgeInsets.symmetric(vertical: 16),
                          ),
                          child: _isSubmitting
                              ? const SizedBox(
                                  height: 20,
                                  width: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Text('Submit Ticket'),
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
