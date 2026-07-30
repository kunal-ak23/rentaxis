import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/gate_pass_provider.dart';

class WalkInScreen extends ConsumerStatefulWidget {
  const WalkInScreen({super.key});

  @override
  ConsumerState<WalkInScreen> createState() => _WalkInScreenState();
}

class _WalkInScreenState extends ConsumerState<WalkInScreen> {
  final _formKey = GlobalKey<FormState>();
  final _phone = TextEditingController();
  final _name = TextEditingController();
  final _purpose = TextEditingController();
  final _vehicle = TextEditingController();
  final _picker = ImagePicker();

  String? _propertyId;
  String? _unitId;
  String _visitorType = 'DELIVERY';
  List<Map<String, dynamic>> _destinations = const [];
  XFile? _photo;
  bool _loadingUnits = false;
  bool _lookingUp = false;
  bool _submitting = false;
  String? _lookupMessage;

  @override
  void dispose() {
    _phone.dispose();
    _name.dispose();
    _purpose.dispose();
    _vehicle.dispose();
    super.dispose();
  }

  Future<void> _loadDestinations(String propertyId) async {
    setState(() {
      _propertyId = propertyId;
      _unitId = null;
      _destinations = const [];
      _loadingUnits = true;
    });
    try {
      final raw = await ref
          .read(gatePassServiceProvider)
          .walkInDestinations(propertyId);
      if (!mounted) return;
      setState(() {
        _destinations = raw.whereType<Map<String, dynamic>>().toList();
        _loadingUnits = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loadingUnits = false);
      _snack('Could not load units for this property.');
    }
  }

  Future<void> _lookup() async {
    if (_propertyId == null || _phone.text.trim().isEmpty || _lookingUp) return;
    setState(() {
      _lookingUp = true;
      _lookupMessage = null;
    });
    try {
      final row = await ref
          .read(gatePassServiceProvider)
          .lookupWalkInVisitor(
            propertyId: _propertyId!,
            phone: _phone.text,
            unitId: _unitId,
          );
      if (!mounted) return;
      _name.text = row['name']?.toString() ?? '';
      _vehicle.text = row['vehicleNumber']?.toString() ?? '';
      final type = row['visitorType']?.toString();
      if (_types.contains(type)) _visitorType = type!;
      final previousUnit = row['lastUnitId']?.toString();
      if (_unitId == null &&
          _destinations.any(
            (row) => row['unitId']?.toString() == previousUnit,
          )) {
        _unitId = previousUnit;
      }
      setState(() {
        _lookingUp = false;
        _lookupMessage = row['registeredForSelectedUnit'] == true
            ? 'Registered for this unit — details loaded.'
            : 'Previous visitor found — confirm the details and take a fresh photo.';
      });
    } on DioException catch (error) {
      if (!mounted) return;
      setState(() {
        _lookingUp = false;
        _lookupMessage = error.response?.statusCode == 404
            ? 'First visit — enter the details below.'
            : 'Could not search previous visits.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _lookingUp = false;
        _lookupMessage = 'Could not search previous visits.';
      });
    }
  }

  Future<void> _takePhoto() async {
    final image = await _picker.pickImage(
      source: ImageSource.camera,
      imageQuality: 78,
      maxWidth: 1280,
    );
    if (image != null && mounted) setState(() => _photo = image);
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate() || _submitting) return;
    if (_photo == null) {
      _snack('Take a fresh visitor photo before submitting.');
      return;
    }
    setState(() => _submitting = true);
    try {
      final pass = await ref
          .read(gatePassServiceProvider)
          .createWalkIn(
            propertyId: _propertyId!,
            unitId: _unitId!,
            name: _name.text,
            phone: _phone.text,
            visitorType: _visitorType,
            purpose: _purpose.text,
            vehicleNumber: _vehicle.text,
            photoPath: _photo!.path,
          );
      if (!mounted) return;
      ref.invalidate(expectedTodayProvider);
      ref.invalidate(approvalsProvider);
      final id = pass['id']?.toString();
      if (id == null) throw StateError('Walk-in response had no id');
      if (mounted) context.pushReplacement('/walk-in/$id');
    } on DioException catch (error) {
      _snack(
        error.response?.data is Map
            ? (error.response!.data['message']?.toString() ??
                  'Could not create the visitor request.')
            : 'Could not create the visitor request.',
      );
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  static const _types = [
    'DELIVERY',
    'GUEST',
    'MAID',
    'MILK_VENDOR',
    'LAUNDRY_VENDOR',
    'SERVICE_VENDOR',
    'OTHER',
  ];

  @override
  Widget build(BuildContext context) {
    final properties = ref.watch(myPropertiesProvider);
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(title: const Text('New walk-in visitor')),
      body: properties.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stack) =>
            const Center(child: Text('Could not load assigned properties.')),
        data: (rows) => Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              DropdownButtonFormField<String>(
                initialValue: _propertyId,
                decoration: const InputDecoration(labelText: 'Property'),
                items: rows
                    .map(
                      (row) => DropdownMenuItem(
                        value: row['id']?.toString(),
                        child: Text(row['name']?.toString() ?? 'Property'),
                      ),
                    )
                    .toList(),
                onChanged: (value) {
                  if (value != null) _loadDestinations(value);
                },
                validator: (value) =>
                    value == null ? 'Select a property' : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                key: ValueKey(
                  'destination-$_propertyId-$_unitId-${_destinations.length}',
                ),
                initialValue: _unitId,
                decoration: InputDecoration(
                  labelText: _loadingUnits
                      ? 'Loading units…'
                      : 'Tower and unit',
                ),
                items: _destinations.map((row) {
                  final tower = row['buildingName']?.toString();
                  final unit = row['unitNumber']?.toString() ?? 'Unit';
                  return DropdownMenuItem(
                    value: row['unitId']?.toString(),
                    child: Text(tower == null ? unit : '$tower · $unit'),
                  );
                }).toList(),
                onChanged: _loadingUnits
                    ? null
                    : (value) => setState(() => _unitId = value),
                validator: (value) =>
                    value == null ? 'Select the destination unit' : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                textInputAction: TextInputAction.search,
                onFieldSubmitted: (_) => _lookup(),
                decoration: InputDecoration(
                  labelText: 'Mobile number with country code',
                  hintText: '+971501234567',
                  suffixIcon: _lookingUp
                      ? const Padding(
                          padding: EdgeInsets.all(14),
                          child: SizedBox.square(
                            dimension: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : IconButton(
                          tooltip: 'Find previous visitor',
                          onPressed: _lookup,
                          icon: const Icon(Icons.manage_search),
                        ),
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Enter a mobile number'
                    : null,
              ),
              if (_lookupMessage != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    _lookupMessage!,
                    style: const TextStyle(color: AppColors.textSecondary),
                  ),
                ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _name,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(labelText: 'Visitor name'),
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Enter the visitor name'
                    : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                initialValue: _visitorType,
                decoration: const InputDecoration(labelText: 'Visitor type'),
                items: _types
                    .map(
                      (type) => DropdownMenuItem(
                        value: type,
                        child: Text(type.replaceAll('_', ' ').toLowerCase()),
                      ),
                    )
                    .toList(),
                onChanged: (value) => setState(() => _visitorType = value!),
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _purpose,
                decoration: const InputDecoration(
                  labelText: 'Purpose or delivery company',
                ),
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _vehicle,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(
                  labelText: 'Vehicle number (optional)',
                ),
              ),
              const SizedBox(height: 18),
              if (_photo != null)
                ClipRRect(
                  borderRadius: BorderRadius.circular(14),
                  child: Image.file(
                    File(_photo!.path),
                    height: 220,
                    fit: BoxFit.cover,
                  ),
                ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: _takePhoto,
                icon: const Icon(Icons.photo_camera),
                label: Text(
                  _photo == null ? 'Take visitor photo' : 'Retake photo',
                ),
              ),
              const SizedBox(height: 18),
              FilledButton.icon(
                onPressed: _submitting ? null : _submit,
                icon: _submitting
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.send),
                label: const Text('Request entry'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
