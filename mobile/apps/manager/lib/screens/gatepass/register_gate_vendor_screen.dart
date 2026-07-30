import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/gate_pass_provider.dart';

class RegisterGateVendorScreen extends ConsumerStatefulWidget {
  const RegisterGateVendorScreen({super.key});

  @override
  ConsumerState<RegisterGateVendorScreen> createState() =>
      _RegisterGateVendorScreenState();
}

class _RegisterGateVendorScreenState
    extends ConsumerState<RegisterGateVendorScreen> {
  final _key = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _phone = TextEditingController();
  String? _propertyId;
  String? _unitId;
  String _type = 'MAID';
  List<Map<String, dynamic>> _units = const [];
  bool _loadingUnits = false;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    super.dispose();
  }

  Future<void> _propertyChanged(String id) async {
    setState(() {
      _propertyId = id;
      _unitId = null;
      _loadingUnits = true;
    });
    try {
      final raw = await UnitService(
        ref.read(apiClientProvider).dio,
      ).getUnitsByProperty(id);
      if (!mounted) return;
      setState(() {
        _units = raw
            .whereType<Map>()
            .map((row) => Map<String, dynamic>.from(row))
            .toList();
        _loadingUnits = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingUnits = false);
    }
  }

  Future<void> _save() async {
    if (!_key.currentState!.validate() || _saving) return;
    setState(() => _saving = true);
    try {
      await ref.read(gatePassServiceProvider).registerGateVisitor({
        'propertyId': _propertyId,
        'unitId': _unitId,
        'name': _name.text.trim(),
        'phone': _phone.text.trim(),
        'visitorType': _type,
        'validFrom': null,
        'validTo': null,
        'active': true,
      });
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Vendor registered for this unit.')),
      );
      _name.clear();
      _phone.clear();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not register this vendor.')),
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final properties = ref.watch(propertiesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Register Unit Vendor')),
      body: properties.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stack) =>
            const Center(child: Text('Could not load properties.')),
        data: (rows) => Form(
          key: _key,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const Text(
                'Register a regular maid or vendor so future gate visits can use the faster policy for that unit.',
              ),
              const SizedBox(height: 18),
              DropdownButtonFormField<String>(
                decoration: const InputDecoration(labelText: 'Property'),
                items: rows
                    .map(
                      (row) => DropdownMenuItem(
                        value: row['id']?.toString(),
                        child: Text(row['name']?.toString() ?? 'Property'),
                      ),
                    )
                    .toList(),
                onChanged: (id) {
                  if (id != null) _propertyChanged(id);
                },
                validator: (value) =>
                    value == null ? 'Select a property' : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                key: ValueKey(
                  'vendor-unit-$_propertyId-$_unitId-${_units.length}',
                ),
                decoration: InputDecoration(
                  labelText: _loadingUnits ? 'Loading units…' : 'Unit',
                ),
                items: _units
                    .map(
                      (row) => DropdownMenuItem(
                        value: row['id']?.toString(),
                        child: Text(row['unitNumber']?.toString() ?? 'Unit'),
                      ),
                    )
                    .toList(),
                onChanged: (id) => setState(() => _unitId = id),
                validator: (value) => value == null ? 'Select a unit' : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _name,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(labelText: 'Name'),
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Enter a name'
                    : null,
              ),
              const SizedBox(height: 14),
              TextFormField(
                controller: _phone,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: 'Mobile number',
                  hintText: '+971501234567',
                ),
                validator: (value) => value == null || value.trim().isEmpty
                    ? 'Enter a mobile number'
                    : null,
              ),
              const SizedBox(height: 14),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: const InputDecoration(labelText: 'Vendor type'),
                items: const [
                  DropdownMenuItem(value: 'MAID', child: Text('Maid')),
                  DropdownMenuItem(
                    value: 'MILK_VENDOR',
                    child: Text('Milk vendor'),
                  ),
                  DropdownMenuItem(
                    value: 'LAUNDRY_VENDOR',
                    child: Text('Laundry vendor'),
                  ),
                  DropdownMenuItem(
                    value: 'SERVICE_VENDOR',
                    child: Text('Service vendor'),
                  ),
                  DropdownMenuItem(value: 'OTHER', child: Text('Other')),
                ],
                onChanged: (value) => setState(() => _type = value!),
              ),
              const SizedBox(height: 22),
              FilledButton.icon(
                onPressed: _saving ? null : _save,
                icon: const Icon(Icons.how_to_reg),
                label: Text(_saving ? 'Saving…' : 'Register vendor'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
