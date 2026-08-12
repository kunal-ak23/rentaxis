import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _meetingServiceProvider = Provider<MeetingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return MeetingService(client.dio);
});

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Purpose terms mirror the renter app's create_meeting_screen so
/// vocabulary stays identical across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String get newMeeting => ar ? 'اجتماع جديد' : 'New Meeting';
  String get meetingPurpose => ar ? 'الغرض من الاجتماع' : 'Meeting Purpose';
  String get selectLease => ar ? 'اختر عقد الإيجار' : 'Select Lease';
  String get noActiveLeases =>
      ar ? 'لا توجد عقود إيجار نشطة.' : 'No active leases found.';
  String get selectALease => ar ? 'اختر عقد إيجار' : 'Select a lease';
  String get renewalDuration =>
      ar ? 'مدة التجديد (بالأشهر)' : 'Renewal Duration (months)';
  String get date => ar ? 'التاريخ' : 'Date';
  String get pickADate => ar ? 'اختر تاريخًا' : 'Pick a date';
  String get availableTimeSlots =>
      ar ? 'الأوقات المتاحة' : 'Available Time Slots';
  String get noSlotsForDate => ar
      ? 'لا توجد مواعيد متاحة لهذا التاريخ.'
      : 'No available slots for this date.';
  String get notesOptional => ar ? 'ملاحظات (اختياري)' : 'Notes (optional)';
  String get notesHint =>
      ar ? 'أي ملاحظات إضافية...' : 'Any additional notes...';
  String get chequeNotesOptional =>
      ar ? 'ملاحظات الشيك (اختياري)' : 'Cheque Notes (optional)';
  String get renewalNotesOptional =>
      ar ? 'ملاحظات التجديد (اختياري)' : 'Renewal Notes (optional)';
  String get chequeNotesHint => ar
      ? 'ملاحظات حول الشيكات المطلوب استبدالها...'
      : 'Notes about the cheques to be replaced...';
  String get renewalNotesHint =>
      ar ? 'ملاحظات حول التجديد...' : 'Notes about the renewal...';
  String get requestMeeting => ar ? 'طلب اجتماع' : 'Request Meeting';
  String get selectDateAndSlot =>
      ar ? 'يرجى اختيار تاريخ ووقت' : 'Please select a date and time slot';
  String get noHostAvailable => ar
      ? 'لا يوجد مضيف متاح. تواصل مع الإدارة.'
      : 'No host available. Contact admin.';
  String get pleaseSelectLease =>
      ar ? 'يرجى اختيار عقد الإيجار' : 'Please select a lease';
  String get meetingCreated =>
      ar ? 'تم إنشاء طلب الاجتماع' : 'Meeting request created';
  String get meetingCreateFailed =>
      ar ? 'فشل إنشاء الاجتماع' : 'Failed to create meeting';
  String get slotTaken => ar
      ? 'تم حجز هذا الموعد للتو. يرجى اختيار وقت آخر.'
      : 'This slot was just taken. Please pick another time.';
  String slotTakenNext(String next) => ar
      ? 'تم حجز هذا الموعد للتو. أقرب موعد متاح: $next'
      : 'This slot was just taken. Next available: $next';

  String unitLine(String renterName, String unit) =>
      ar ? '$renterName · وحدة $unit' : '$renterName · Unit $unit';

  String purposeLabel(String p) {
    switch (p) {
      case 'PROPERTY_VIEWING':
        return ar ? 'معاينة العقار' : 'Property Viewing';
      case 'LEASE_RENEWAL':
        return ar ? 'تجديد عقد الإيجار' : 'Lease Renewal';
      case 'CHEQUE_REPLACEMENT':
        return ar ? 'استبدال الشيك' : 'Cheque Replacement';
      case 'OTHER':
        return ar ? 'أخرى / زيارة مكتبية' : 'Other / Office Visit';
      default:
        return p;
    }
  }
}

/// Extracts the 409 slot-conflict body's suggested `nextAvailableSlot`
/// (GlobalExceptionHandler sends `{error, nextAvailableSlot}`) and formats
/// it in device-local time, e.g. "12/8, 3:30 PM". Returns null when the body
/// carries no parsable instant — the backend sends the literal string
/// "unavailable" when it has no free slot to suggest.
///
/// Top-level (not a State method) so tests can pin the mapping directly.
String? formatNextAvailableSlot(Object? responseData, {required bool ar}) {
  if (responseData is! Map) return null;
  final raw = responseData['nextAvailableSlot']?.toString();
  if (raw == null) return null;
  final dt = DateTime.tryParse(raw)?.toLocal();
  if (dt == null) return null;
  final hour = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
  final minute = dt.minute.toString().padLeft(2, '0');
  final ampm = ar ? (dt.hour < 12 ? 'ص' : 'م') : (dt.hour < 12 ? 'AM' : 'PM');
  final sep = ar ? '،' : ',';
  return '${dt.day}/${dt.month}$sep $hour:$minute $ampm';
}

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

  List<dynamic> _leases = [];
  String? _selectedLeaseId;
  bool _loadingLeases = false;

  String? _defaultHostId;
  bool _isSubmitting = false;

  static const _purposeKeys = [
    ('PROPERTY_VIEWING', Icons.home_outlined),
    ('LEASE_RENEWAL', Icons.autorenew_rounded),
    ('CHEQUE_REPLACEMENT', Icons.receipt_outlined),
    ('OTHER', Icons.business_outlined),
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
      final id = await ref.read(_meetingServiceProvider).getDefaultHostId();
      if (mounted) setState(() => _defaultHostId = id);
    } catch (_) {}
  }

  Future<void> _fetchLeases() async {
    setState(() => _loadingLeases = true);
    try {
      final leases = await ref.read(_leaseServiceProvider).getAllLeases();
      if (!mounted) return;
      setState(() {
        _leases = leases.where((l) => l['status'] == 'ACTIVE').toList();
        _loadingLeases = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingLeases = false);
    }
  }

  Future<void> _pickDate() async {
    final m = context.miftah;
    final d = await showDatePicker(
      context: context,
      initialDate: DateTime.now().add(const Duration(days: 1)),
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 90)),
      builder: (ctx, child) => Theme(
        data: Theme.of(ctx).copyWith(
          colorScheme: Theme.of(ctx).colorScheme.copyWith(
            primary: AppColors.accent,
            onPrimary: AppColors.primary,
            surface: m.surface,
          ),
        ),
        child: child!,
      ),
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
      final ampm = context.isAr
          ? (dt.hour < 12 ? 'ص' : 'م')
          : (dt.hour < 12 ? 'AM' : 'PM');
      return '$hour:$minute $ampm';
    } catch (_) {
      return slot['start'].toString();
    }
  }

  bool get _needsLease =>
      _purpose == 'LEASE_RENEWAL' || _purpose == 'CHEQUE_REPLACEMENT';

  Future<void> _submit() async {
    final l = _L(context.isAr);
    if (_selectedSlot == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.selectDateAndSlot)));
      return;
    }
    if (_defaultHostId == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.noHostAvailable)));
      return;
    }
    if (_needsLease && _selectedLeaseId == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.pleaseSelectLease)));
      return;
    }

    setState(() => _isSubmitting = true);
    try {
      final type = _purpose == 'PROPERTY_VIEWING'
          ? 'PROPERTY_VISIT'
          : 'OFFICE_VISIT';

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
          orElse: () => null,
        );
        if (lease != null && lease['endDate'] != null) {
          body['proposedStartDate'] = lease['endDate'];
          final months = int.tryParse(_renewalMonthsCtrl.text.trim()) ?? 12;
          final start = DateTime.parse(lease['endDate']);
          final end = DateTime(start.year, start.month + months, start.day);
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
          SnackBar(
            content: Text(l.meetingCreated),
            backgroundColor: AppColors.success,
          ),
        );
        context.pop();
      }
    } on DioException catch (e) {
      if (mounted) {
        final String message;
        if (e.response?.statusCode == 409) {
          // Slot race: another user booked the same slot first. The backend's
          // 409 carries a suggested nextAvailableSlot — surface it, and
          // refresh availability so the stale slot leaves the grid.
          final next = formatNextAvailableSlot(e.response?.data, ar: l.ar);
          message = next != null ? l.slotTakenNext(next) : l.slotTaken;
          setState(() => _selectedSlot = null);
          if (_selectedDate != null) _fetchSlots(_selectedDate!);
        } else {
          // e.g. 400 "Cannot book a slot in the past" — the server's message
          // wins, the generic string is only the fallback.
          message = errorMessage(e, l.meetingCreateFailed);
        }
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(message),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.meetingCreateFailed),
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
          l.newMeeting,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(fontWeight: FontWeight.w600)
              : GoogleFonts.plusJakartaSans(fontSize: 16, letterSpacing: 1.2),
        ),
      ),
      body: LoadingOverlay(
        isLoading: _isSubmitting,
        child: SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            16,
            16,
            16,
            AppInsets.bottomNav(context),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _sectionHeader(l.meetingPurpose, l, m),
              const SizedBox(height: 8),
              ...(_purposeKeys.map(
                (p) => _PurposeOption(
                  label: l.purposeLabel(p.$1),
                  icon: p.$2,
                  isSelected: _purpose == p.$1,
                  ar: l.ar,
                  onTap: () => setState(() {
                    _purpose = p.$1;
                    _selectedLeaseId = null;
                  }),
                ),
              )),

              if (_needsLease) ...[
                const SizedBox(height: 20),
                _sectionHeader(l.selectLease, l, m),
                const SizedBox(height: 8),
                if (_loadingLeases)
                  const Center(
                    child: CircularProgressIndicator(color: AppColors.accent),
                  )
                else if (_leases.isEmpty)
                  Text(
                    l.noActiveLeases,
                    style: GoogleFonts.plusJakartaSans(
                      color: m.textMuted,
                      fontSize: 13,
                    ),
                  )
                else
                  Container(
                    decoration: BoxDecoration(
                      color: m.surface,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: m.border),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _selectedLeaseId,
                        isExpanded: true,
                        hint: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 12),
                          child: Text(
                            l.selectALease,
                            style: GoogleFonts.plusJakartaSans(color: m.textMuted),
                          ),
                        ),
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        borderRadius: BorderRadius.circular(12),
                        dropdownColor: m.surface,
                        items: _leases.map<DropdownMenuItem<String>>((lease) {
                          return DropdownMenuItem<String>(
                            value: lease['id'],
                            child: Text(
                              l.unitLine(
                                lease['renterName'] ?? '-',
                                (lease['unitIdentifier'] ??
                                        lease['unitNumber'] ??
                                        '-')
                                    .toString(),
                              ),
                              overflow: TextOverflow.ellipsis,
                              style: GoogleFonts.plusJakartaSans(
                                color: m.textPrimary,
                              ),
                            ),
                          );
                        }).toList(),
                        onChanged: (v) => setState(() => _selectedLeaseId = v),
                      ),
                    ),
                  ),
              ],

              if (_purpose == 'LEASE_RENEWAL') ...[
                const SizedBox(height: 20),
                _sectionHeader(l.renewalDuration, l, m),
                const SizedBox(height: 8),
                TextField(
                  controller: _renewalMonthsCtrl,
                  keyboardType: TextInputType.number,
                  style: GoogleFonts.plusJakartaSans(color: m.textPrimary),
                  decoration: InputDecoration(
                    hintText: '12',
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 12,
                    ),
                  ),
                ),
              ],

              const SizedBox(height: 20),
              _sectionHeader(l.date, l, m),
              const SizedBox(height: 8),
              InkWell(
                onTap: _pickDate,
                borderRadius: BorderRadius.circular(12),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 14,
                  ),
                  decoration: BoxDecoration(
                    color: m.surface,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: m.border),
                  ),
                  child: Row(
                    children: [
                      const Icon(
                        Icons.calendar_today_outlined,
                        size: 18,
                        color: AppColors.accentDark,
                      ),
                      const SizedBox(width: 10),
                      Text(
                        _selectedDate == null
                            ? l.pickADate
                            : '${_selectedDate!.year}-${_selectedDate!.month.toString().padLeft(2, '0')}-${_selectedDate!.day.toString().padLeft(2, '0')}',
                        style: GoogleFonts.plusJakartaSans(
                          color: _selectedDate == null
                              ? m.textMuted
                              : m.textPrimary,
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              if (_selectedDate != null) ...[
                const SizedBox(height: 16),
                _sectionHeader(l.availableTimeSlots, l, m),
                const SizedBox(height: 8),
                if (_loadingSlots)
                  const Center(
                    child: CircularProgressIndicator(color: AppColors.accent),
                  )
                else if (_slots.isEmpty)
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: m.surface,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: m.border),
                    ),
                    child: Text(
                      l.noSlotsForDate,
                      style: GoogleFonts.plusJakartaSans(
                        color: m.textMuted,
                        fontSize: 13,
                      ),
                    ),
                  )
                else
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: _slots.map((slot) {
                      final isSelected = _selectedSlot == slot;
                      return GestureDetector(
                        onTap: () => setState(() => _selectedSlot = slot),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 200),
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 10,
                          ),
                          decoration: BoxDecoration(
                            color: isSelected ? AppColors.accent : m.surface,
                            borderRadius: BorderRadius.circular(10),
                            border: Border.all(
                              color: isSelected ? AppColors.accent : m.border,
                            ),
                          ),
                          child: Text(
                            _slotLabel(slot),
                            style: GoogleFonts.plusJakartaSans(
                              fontWeight: FontWeight.w600,
                              color: isSelected
                                  ? AppColors.primary
                                  : m.textPrimary,
                              fontSize: 13,
                            ),
                          ),
                        ),
                      );
                    }).toList(),
                  ),
              ],

              const SizedBox(height: 20),
              _sectionHeader(l.notesOptional, l, m),
              const SizedBox(height: 8),
              TextField(
                controller: _notesCtrl,
                maxLines: 3,
                style: GoogleFonts.plusJakartaSans(color: m.textPrimary),
                decoration: InputDecoration(
                  hintText: l.notesHint,
                  hintStyle: GoogleFonts.plusJakartaSans(color: m.textMuted),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  contentPadding: const EdgeInsets.all(12),
                ),
              ),

              if (_needsLease) ...[
                const SizedBox(height: 16),
                _sectionHeader(
                  _purpose == 'CHEQUE_REPLACEMENT'
                      ? l.chequeNotesOptional
                      : l.renewalNotesOptional,
                  l,
                  m,
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _detailNotesCtrl,
                  maxLines: 3,
                  style: GoogleFonts.plusJakartaSans(color: m.textPrimary),
                  decoration: InputDecoration(
                    hintText: _purpose == 'CHEQUE_REPLACEMENT'
                        ? l.chequeNotesHint
                        : l.renewalNotesHint,
                    hintStyle: GoogleFonts.plusJakartaSans(color: m.textMuted),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    contentPadding: const EdgeInsets.all(12),
                  ),
                ),
              ],

              const SizedBox(height: 32),
              GoldButton(
                label: l.requestMeeting,
                height: 50,
                onPressed: _isSubmitting ? null : _submit,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _sectionHeader(String text, _L l, LegacyMiftahColors m) {
    return Text(
      text,
      style: l.ar
          ? GoogleFonts.notoNaskhArabic(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            )
          : GoogleFonts.plusJakartaSans(
              fontSize: 11,
              letterSpacing: 1.6,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            ),
    );
  }
}

class _PurposeOption extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool isSelected;
  final bool ar;
  final VoidCallback onTap;

  const _PurposeOption({
    required this.label,
    required this.icon,
    required this.isSelected,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.accent.withValues(alpha: 0.1)
              : m.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isSelected ? AppColors.accent : m.border,
            width: isSelected ? 1.5 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(
              icon,
              size: 20,
              color: isSelected
                  ? (m.isDark ? AppColors.accent : AppColors.accentDark)
                  : m.textMuted,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                label,
                style: GoogleFonts.plusJakartaSans(
                  fontSize: 14,
                  letterSpacing: ar ? 0 : 0.2,
                  fontWeight: FontWeight.w600,
                  color: isSelected
                      ? (m.isDark ? AppColors.accent : AppColors.accentDark)
                      : m.textPrimary,
                ),
              ),
            ),
            if (isSelected)
              const Icon(
                Icons.check_circle_rounded,
                color: AppColors.accent,
                size: 18,
              ),
          ],
        ),
      ),
    );
  }
}
