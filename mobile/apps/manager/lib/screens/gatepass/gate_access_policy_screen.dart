import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/gate_pass_provider.dart';

class GateAccessPolicyScreen extends ConsumerStatefulWidget {
  const GateAccessPolicyScreen({super.key});

  @override
  ConsumerState<GateAccessPolicyScreen> createState() =>
      _GateAccessPolicyScreenState();
}

class _GateAccessPolicyScreenState
    extends ConsumerState<GateAccessPolicyScreen> {
  String? _propertyId;
  String? _buildingId;
  List<Map<String, dynamic>> _buildings = const [];
  bool _loading = false;
  bool _saving = false;
  bool _requireUnregisteredApproval = true;
  bool _requireRegisteredApproval = false;
  bool _notifyRegisteredEntry = true;
  bool _requireFreshPhoto = true;
  int _timeout = 15;

  Future<void> _selectProperty(String id) async {
    setState(() {
      _propertyId = id;
      _buildingId = null;
      _loading = true;
    });
    final client = ref.read(apiClientProvider);
    try {
      final results = await Future.wait([
        BuildingService(client.dio).getBuildingsByProperty(id),
        ref.read(gatePassServiceProvider).effectiveGatePolicy(propertyId: id),
      ]);
      if (!mounted) return;
      setState(() {
        _buildings = (results[0] as List)
            .whereType<Map>()
            .map((row) => Map<String, dynamic>.from(row))
            .toList();
        _apply(Map<String, dynamic>.from(results[1] as Map));
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _loading = false);
        _snack('Could not load the gate policy.');
      }
    }
  }

  Future<void> _selectBuilding(String? id) async {
    if (_propertyId == null) return;
    setState(() {
      _buildingId = id;
      _loading = true;
    });
    try {
      final policy = await ref
          .read(gatePassServiceProvider)
          .effectiveGatePolicy(propertyId: _propertyId!, buildingId: id);
      if (!mounted) return;
      setState(() {
        _apply(policy);
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _loading = false);
        _snack('Could not load the gate policy.');
      }
    }
  }

  void _apply(Map<String, dynamic> policy) {
    _requireUnregisteredApproval =
        policy['requireUnregisteredApproval'] != false;
    _requireRegisteredApproval = policy['requireRegisteredApproval'] == true;
    _notifyRegisteredEntry = policy['notifyRegisteredEntry'] != false;
    _requireFreshPhoto = policy['requireFreshPhoto'] != false;
    _timeout = (policy['approvalTimeoutMinutes'] as num?)?.toInt() ?? 15;
  }

  Future<void> _save() async {
    if (_propertyId == null || _saving) return;
    setState(() => _saving = true);
    try {
      await ref
          .read(gatePassServiceProvider)
          .saveGatePolicy(
            propertyId: _propertyId!,
            buildingId: _buildingId,
            policy: {
              'requireUnregisteredApproval': _requireUnregisteredApproval,
              'requireRegisteredApproval': _requireRegisteredApproval,
              'notifyRegisteredEntry': _notifyRegisteredEntry,
              'requireFreshPhoto': _requireFreshPhoto,
              'approvalTimeoutMinutes': _timeout,
            },
          );
      _snack(
        _buildingId == null
            ? 'Property policy saved.'
            : 'Tower override saved.',
      );
    } catch (_) {
      _snack('Could not save the gate policy.');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final properties = ref.watch(propertiesProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Gate Access Policy')),
      body: properties.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stack) =>
            const Center(child: Text('Could not load properties.')),
        data: (rows) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            DropdownButtonFormField<String>(
              initialValue: _propertyId,
              decoration: const InputDecoration(labelText: 'Property'),
              items: rows
                  .map(
                    (row) => DropdownMenuItem(
                      value: row['id']?.toString(),
                      child: Text(
                        row['name']?.toString() ??
                            row['nameEn']?.toString() ??
                            'Property',
                      ),
                    ),
                  )
                  .toList(),
              onChanged: (id) {
                if (id != null) _selectProperty(id);
              },
            ),
            const SizedBox(height: 14),
            DropdownButtonFormField<String?>(
              key: ValueKey(
                'policy-scope-$_propertyId-$_buildingId-${_buildings.length}',
              ),
              initialValue: _buildingId,
              decoration: const InputDecoration(
                labelText: 'Scope',
                helperText: 'Choose a tower to override the property default.',
              ),
              items: [
                const DropdownMenuItem<String?>(
                  value: null,
                  child: Text('Property default'),
                ),
                ..._buildings.map(
                  (row) => DropdownMenuItem<String?>(
                    value: row['id']?.toString(),
                    child: Text(row['nameEn']?.toString() ?? 'Tower'),
                  ),
                ),
              ],
              onChanged: _propertyId == null ? null : _selectBuilding,
            ),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(28),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_propertyId != null) ...[
              const SizedBox(height: 18),
              SwitchListTile(
                value: _requireUnregisteredApproval,
                title: const Text('Approve new visitors and delivery riders'),
                subtitle: const Text(
                  'They remain at the gate until a resident approves.',
                ),
                onChanged: (value) =>
                    setState(() => _requireUnregisteredApproval = value),
              ),
              SwitchListTile(
                value: _requireRegisteredApproval,
                title: const Text('Approve registered vendors every visit'),
                onChanged: (value) =>
                    setState(() => _requireRegisteredApproval = value),
              ),
              SwitchListTile(
                value: _notifyRegisteredEntry,
                title: const Text('Notify residents when their vendor arrives'),
                onChanged: (value) =>
                    setState(() => _notifyRegisteredEntry = value),
              ),
              SwitchListTile(
                value: _requireFreshPhoto,
                title: const Text('Require a fresh camera photo'),
                onChanged: (value) =>
                    setState(() => _requireFreshPhoto = value),
              ),
              ListTile(
                title: const Text('Approval timeout'),
                subtitle: Slider(
                  value: _timeout.toDouble(),
                  min: 5,
                  max: 60,
                  divisions: 11,
                  label: '$_timeout minutes',
                  onChanged: (value) =>
                      setState(() => _timeout = value.round()),
                ),
                trailing: Text('$_timeout min'),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: _saving ? null : _save,
                icon: const Icon(Icons.save_outlined),
                label: Text(_saving ? 'Saving…' : 'Save policy'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
