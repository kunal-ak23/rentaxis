import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _meetingServiceProvider = Provider<MeetingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return MeetingService(client.dio);
});

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

class CreateMeetingScreen extends ConsumerStatefulWidget {
  const CreateMeetingScreen({super.key});

  @override
  ConsumerState<CreateMeetingScreen> createState() =>
      _CreateMeetingScreenState();
}

class _CreateMeetingScreenState extends ConsumerState<CreateMeetingScreen> {
  final _notesCtrl = TextEditingController();
  final _detailNotesCtrl = TextEditingController();
  final _renewalMonthsCtrl = TextEditingController(text: '12');

  String _purpose = 'PROPERTY_VIEWING';
  DateTime? _selectedDate;
  Map<String, dynamic>? _selectedSlot;
  List<dynamic> _slots = [];
  bool _loadingSlots = false;

  // Lease selection (for LEASE_RENEWAL / CHEQUE_REPLACEMENT)
  List<dynamic> _leases = [];
  String? _selectedLeaseId;
  bool _loadingLeases = false;

  String? _defaultHostId;
  bool _isSubmitting = false;

  static const _purposes = [
    ('PROPERTY_VIEWING', 'Property Viewing', Icons.home_outlined),
    ('LEASE_RENEWAL', 'Lease Renewal', Icons.autorenew_rounded),
    ('CHEQUE_REPLACEMENT', 'Cheque Replacement', Icons.receipt_outlined),
    ('OTHER', 'Other / Office Visit', Icons.business_outlined),
  ];

  @override
  void initState() {
    super.initState();
    _fetchDefaultHost();
    _fetchLeases();
  }

  @override
  void dispose() {
    _notesCtrl.dispose();
    _detailNotesCtrl.dispose();
    _renewalMonthsCtrl.dispose();
    super.dispose();
  }

  Future<void> _fetchDefaultHost() async {
    try {
      final id =
          await ref.read(_meetingServiceProvider).getDefaultHostId();
      if (mounted) setState(() => _defaultHostId = id);
    } catch (_) {}
  }

  Future<void> _fetchLeases() async {
    setState(() => _loadingLeases = true);
    try {
      final leases = await ref.read(_leaseServiceProvider).getAllLeases();
      if (!mounted) return;
      setState(() {
        _leases =
            leases.where((l) => l['status'] == 'ACTIVE').toList();
        _loadingLeases = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingLeases = false);
    }
  }

  Future<void> _pickDate() async {
    final d = await showDatePicker(
      context: context,
      initialDate: DateTime.now().add(const Duration(days: 1)),
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 90)),
    );
    if (d == null) return;
    setState(() {
      _selectedDate = d;
      _selectedSlot = null;
      _slots = [];
    });
    if (_defaultHostId != null) await _fetchSlots(d);
  }

  Future<void> _fetchSlots(DateTime date) async {
    if (_defaultHostId == null) return;
    setState(() => _loadingSlots = true);
    try {
      final dateStr =
          '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
      final slots = await ref
          .read(_meetingServiceProvider)
          .getAvailableSlots(_defaultHostId!, dateStr);
      if (!mounted) return;
      setState(() {
        _slots = slots.where((s) => s['available'] == true).toList();
        _loadingSlots = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingSlots = false);
    }
  }

  String _slotLabel(Map<String, dynamic> slot) {
    try {
      final dt = DateTime.parse(slot['start'].toString()).toLocal();
      final hour = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
      final minute = dt.minute.toString().padLeft(2, '0');
      final ampm = dt.hour < 12 ? 'AM' : 'PM';
      return '$hour:$minute $ampm';
    } catch (_) {
      return slot['start'].toString();
    }
  }

  bool get _needsLease =>
      _purpose == 'LEASE_RENEWAL' || _purpose == 'CHEQUE_REPLACEMENT';

  Future<void> _submit() async {
    if (_selectedSlot == null) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please select a date and time slot')));
      return;
    }
    if (_defaultHostId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No host available. Contact admin.')));
      return;
    }
    if (_needsLease && _selectedLeaseId == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Please select a lease')));
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      final type =
          _purpose == 'PROPERTY_VIEWING' ? 'PROPERTY_VISIT' : 'OFFICE_VISIT';

      final body = <String, dynamic>{
        'purpose': _purpose,
        'type': type,
        'hostUserId': _defaultHostId,
        'slotStart': _selectedSlot!['start'],
        if (_notesCtrl.text.trim().isNotEmpty) 'notes': _notesCtrl.text.trim(),
      };

      if (_needsLease && _selectedLeaseId != null) {
        body['leaseId'] = _selectedLeaseId;
      }

      if (_purpose == 'LEASE_RENEWAL') {
        final lease = _leases.firstWhere(
            (l) => l['id'] == _selectedLeaseId,
            orElse: () => null);
        if (lease != null && lease['endDate'] != null) {
          body['proposedStartDate'] = lease['endDate'];
          final months = int.tryParse(_renewalMonthsCtrl.text.trim()) ?? 12;
          final start = DateTime.parse(lease['endDate']);
          final end = DateTime(
              start.year, start.month + months, start.day);
          body['proposedEndDate'] =
              '${end.year}-${end.month.toString().padLeft(2, '0')}-${end.day.toString().padLeft(2, '0')}';
        }
        if (_detailNotesCtrl.text.trim().isNotEmpty) {
          body['detailNotes'] = _detailNotesCtrl.text.trim();
        }
      } else if (_purpose == 'CHEQUE_REPLACEMENT') {
        if (_detailNotesCtrl.text.trim().isNotEmpty) {
          body['detailNotes'] = _detailNotesCtrl.text.trim();
        }
      }

      await ref.read(_meetingServiceProvider).createMeeting(body);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Meeting request created')));
        context.pop();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to create meeting')));
      }
    } finally {
      if (mounted) setState(() => _isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('New Meeting')),
      body: LoadingOverlay(
        isLoading: _isSubmitting,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Purpose selection
              _SectionHeader('Meeting Purpose'),
              const SizedBox(height: 8),
              ...(_purposes.map((p) => _PurposeOption(
                    label: p.$2,
                    icon: p.$3,
                    isSelected: _purpose == p.$1,
                    onTap: () => setState(() {
                      _purpose = p.$1;
                      _selectedLeaseId = null;
                    }),
                  ))),

              // Lease selector
              if (_needsLease) ...[
                const SizedBox(height: 20),
                _SectionHeader('Select Lease'),
                const SizedBox(height: 8),
                if (_loadingLeases)
                  const Center(
                      child: CircularProgressIndicator(
                          color: AppColors.primary))
                else if (_leases.isEmpty)
                  const Text('No active leases found.',
                      style:
                          TextStyle(color: AppColors.textMuted, fontSize: 13))
                else
                  Container(
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppColors.border),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _selectedLeaseId,
                        isExpanded: true,
                        hint: const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 12),
                          child: Text('Select a lease'),
                        ),
                        padding:
                            const EdgeInsets.symmetric(horizontal: 12),
                        borderRadius: BorderRadius.circular(12),
                        items: _leases.map<DropdownMenuItem<String>>((l) {
                          return DropdownMenuItem<String>(
                            value: l['id'],
                            child: Text(
                              '${l['renterName'] ?? '-'} · Unit ${l['unitNumber'] ?? '-'}',
                              overflow: TextOverflow.ellipsis,
                            ),
                          );
                        }).toList(),
                        onChanged: (v) =>
                            setState(() => _selectedLeaseId = v),
                      ),
                    ),
                  ),
              ],

              // Duration (LEASE_RENEWAL only)
              if (_purpose == 'LEASE_RENEWAL') ...[
                const SizedBox(height: 20),
                _SectionHeader('Renewal Duration (months)'),
                const SizedBox(height: 8),
                TextField(
                  controller: _renewalMonthsCtrl,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                    hintText: '12',
                    border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12)),
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12, vertical: 12),
                  ),
                ),
              ],

              // Date picker
              const SizedBox(height: 20),
              _SectionHeader('Date'),
              const SizedBox(height: 8),
              InkWell(
                onTap: _pickDate,
                borderRadius: BorderRadius.circular(12),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 16, vertical: 14),
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: AppColors.border),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.calendar_today_outlined,
                          size: 18, color: AppColors.primary),
                      const SizedBox(width: 10),
                      Text(
                        _selectedDate == null
                            ? 'Pick a date'
                            : '${_selectedDate!.year}-${_selectedDate!.month.toString().padLeft(2, '0')}-${_selectedDate!.day.toString().padLeft(2, '0')}',
                        style: TextStyle(
                          color: _selectedDate == null
                              ? AppColors.textMuted
                              : AppColors.textPrimary,
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              // Time slots
              if (_selectedDate != null) ...[
                const SizedBox(height: 16),
                _SectionHeader('Available Time Slots'),
                const SizedBox(height: 8),
                if (_loadingSlots)
                  const Center(
                      child: CircularProgressIndicator(
                          color: AppColors.primary))
                else if (_slots.isEmpty)
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Text('No available slots for this date.',
                        style: TextStyle(
                            color: AppColors.textMuted, fontSize: 13)),
                  )
                else
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: _slots.map((slot) {
                      final isSelected = _selectedSlot == slot;
                      return GestureDetector(
                        onTap: () =>
                            setState(() => _selectedSlot = slot),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 200),
                          padding: const EdgeInsets.symmetric(
                              horizontal: 16, vertical: 10),
                          decoration: BoxDecoration(
                            color: isSelected
                                ? AppColors.primary
                                : AppColors.surface,
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(
                              color: isSelected
                                  ? AppColors.primary
                                  : AppColors.border,
                            ),
                          ),
                          child: Text(
                            _slotLabel(slot),
                            style: TextStyle(
                              fontWeight: FontWeight.w600,
                              color: isSelected
                                  ? Colors.white
                                  : AppColors.textPrimary,
                              fontSize: 13,
                            ),
                          ),
                        ),
                      );
                    }).toList(),
                  ),
              ],

              // Notes
              const SizedBox(height: 20),
              _SectionHeader('Notes (optional)'),
              const SizedBox(height: 8),
              TextField(
                controller: _notesCtrl,
                maxLines: 3,
                decoration: InputDecoration(
                  hintText: 'Any additional notes...',
                  border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12)),
                  contentPadding: const EdgeInsets.all(12),
                ),
              ),

              // Detail notes (LEASE_RENEWAL / CHEQUE_REPLACEMENT)
              if (_needsLease) ...[
                const SizedBox(height: 16),
                _SectionHeader(
                    _purpose == 'CHEQUE_REPLACEMENT'
                        ? 'Cheque Notes (optional)'
                        : 'Renewal Notes (optional)'),
                const SizedBox(height: 8),
                TextField(
                  controller: _detailNotesCtrl,
                  maxLines: 3,
                  decoration: InputDecoration(
                    hintText: _purpose == 'CHEQUE_REPLACEMENT'
                        ? 'Notes about the cheques to be replaced...'
                        : 'Notes about the renewal...',
                    border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12)),
                    contentPadding: const EdgeInsets.all(12),
                  ),
                ),
              ],

              const SizedBox(height: 32),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: _isSubmitting ? null : _submit,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.primary,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14)),
                  ),
                  child: const Text('Request Meeting',
                      style: TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.w700,
                          fontSize: 15)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String text;
  const _SectionHeader(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(text,
        style: const TextStyle(
            fontWeight: FontWeight.w700,
            fontSize: 14,
            color: AppColors.navyDark));
  }
}

class _PurposeOption extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool isSelected;
  final VoidCallback onTap;

  const _PurposeOption({
    required this.label,
    required this.icon,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.primary.withValues(alpha: 0.1)
              : AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isSelected ? AppColors.primary : AppColors.border,
            width: isSelected ? 1.5 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(icon,
                size: 20,
                color:
                    isSelected ? AppColors.primary : AppColors.textMuted),
            const SizedBox(width: 12),
            Expanded(
              child: Text(label,
                  style: TextStyle(
                      fontWeight: FontWeight.w600,
                      color: isSelected
                          ? AppColors.primary
                          : AppColors.textPrimary)),
            ),
            if (isSelected)
              const Icon(Icons.check_circle_rounded,
                  color: AppColors.primary, size: 18),
          ],
        ),
      ),
    );
  }
}
