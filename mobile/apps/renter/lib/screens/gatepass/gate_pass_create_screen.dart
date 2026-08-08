import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/phone_format.dart';
import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تصريح دخول جديد' : 'New Gate Pass';
  String get tenancyCheckFailed =>
      ar ? 'تعذر التحقق من عقد إيجارك.' : 'Could not check your tenancy.';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get guestName => ar ? 'اسم الضيف' : 'Guest name';
  String get whoIsVisiting => ar ? 'من الزائر؟' : 'Who is visiting?';
  String get enterGuestName =>
      ar ? 'أدخل اسم الضيف' : 'Enter the guest\'s name';
  String get guestPhone => ar ? 'هاتف الضيف' : 'Guest phone';
  String get enterGuestPhone =>
      ar ? 'أدخل رقم هاتف الضيف' : 'Enter the guest\'s phone number';
  String get phoneCountryCode => ar
      ? 'استخدم الرقم كاملاً مع رمز الدولة، مثال: ‎+971501234567'
      : 'Use the full number with country code, e.g. +971501234567';
  String get purposeOptional => ar ? 'الغرض (اختياري)' : 'Purpose (optional)';
  String get purposeHint => ar
      ? 'توصيل، زيارة عائلية، صيانة…'
      : 'Delivery, family visit, maintenance…';
  String get vehicleOptional =>
      ar ? 'رقم المركبة (اختياري)' : 'Vehicle number (optional)';
  String get passType => ar ? 'نوع التصريح' : 'Pass type';
  String get singleVisit => ar ? 'زيارة واحدة' : 'Single visit';
  String get recurring => ar ? 'متكرر' : 'Recurring';
  String get requestPass => ar ? 'طلب تصريح' : 'Request pass';
  String get createPass => ar ? 'إنشاء تصريح' : 'Create pass';
  String get visitDate => ar ? 'تاريخ الزيارة' : 'Visit date';
  String get pickADate => ar ? 'اختر تاريخًا' : 'Pick a date';
  String get from => ar ? 'من' : 'From';
  String get until => ar ? 'حتى' : 'Until';
  String get starts => ar ? 'يبدأ' : 'Starts';
  String get firstDay => ar ? 'اليوم الأول' : 'First day';
  String get expires => ar ? 'ينتهي' : 'Expires';
  String get lastDay => ar ? 'اليوم الأخير' : 'Last day';
  String get needsApproval =>
      ar ? 'يحتاج إلى موافقة المدير' : 'Needs manager approval';
  String get worksStraightAway =>
      ar ? 'يعمل فورًا' : 'Works straight away';
  String get recurringExplainer => ar
      ? 'لخادمة أو سائق أو زائر منتظم. يجب أن يوافق المدير على هذا التصريح '
            'قبل أن يفتح البوابة — لن يعمل اليوم ما لم تتم الموافقة عليه.'
      : 'For a maid, driver or regular visitor. A manager must '
            'approve this pass before it opens the gate — it will '
            'not work today unless it is approved.';
  String get singleExplainer => ar
      ? 'لزيارة لمرة واحدة. يصبح التصريح نشطًا فور إنشائه ويسمح لضيفك بالدخول '
            'مرة واحدة.'
      : 'For a one-off visit. The pass is active as soon as you '
            'create it and lets your guest in once.';
  String get chooseUnit =>
      ar ? 'اختر الوحدة التي سيكون هذا التصريح لها.' : 'Choose which unit this pass is for.';
  String get pickRecurringWindow => ar
      ? 'اختر أول وآخر يوم يجب أن يعمل فيه هذا التصريح.'
      : 'Pick the first and last day this pass should work.';
  String get pickVisitDate =>
      ar ? 'اختر تاريخ الزيارة.' : 'Pick the date of the visit.';
  String get lastDayAfterFirst => ar
      ? 'يجب أن يكون اليوم الأخير بعد اليوم الأول.'
      : 'The last day must be after the first day.';
  String get endAfterStart => ar
      ? 'يجب أن يكون وقت الانتهاء بعد وقت البدء.'
      : 'The end time must be after the start time.';
  String get unitNotOnActiveLease => ar
      ? 'هذه الوحدة ليست ضمن عقد إيجار نشط لك. تواصل مع مدير العقار إذا كان '
            'يجب أن يكون عقدك نشطًا.'
      : 'That unit is not on an active lease of yours. '
            'Contact your manager if your tenancy should be active.';
  String get createFailed =>
      ar ? 'تعذر إنشاء التصريح. حاول مرة أخرى.' : 'Could not create the pass. Please try again.';
  String get noActiveTenancy => ar ? 'لا يوجد عقد إيجار نشط' : 'No active tenancy';
  String get noActiveTenancySub => ar
      ? 'تُصدر تصاريح الدخول للوحدة التي تستأجرها، لذا تحتاج إلى عقد إيجار '
            'نشط لإنشاء واحد. إذا بدأ عقدك للتو، اطلب من مدير العقار تفعيله.'
      : 'Gate passes are raised against the unit you are renting, so '
            'you need an active lease to create one. If your tenancy has just '
            'started, ask your property manager to activate it.';
  String get whichUnit => ar ? 'أي وحدة؟' : 'Which unit?';
  String get unitFallback => ar ? 'وحدة' : 'Unit';
  String unitLabel(String number) => ar ? 'وحدة $number' : 'Unit $number';
}

/// Raise a gate pass for a guest.
///
/// Two things here are load-bearing and easy to lose in a refactor:
///
/// 1. **The unit comes from an ACTIVE lease, and only an ACTIVE one.** The
///    backend derives the property from the unit and 404s a unit that is not on
///    the caller's active lease — deliberately 404 rather than 403, so a renter
///    cannot probe unit ids. That means a wrong unit surfaces as an
///    indistinguishable "not found", so the screen must not offer one. A renter
///    with no active lease is blocked *here*, with a reason, rather than sent to
///    the server to be told nothing.
///
/// 2. **The two temporals are `Instant`s and must go up as UTC.** They are
///    encoded with core's [instant] helper. A local `DateTime.toIso8601String()`
///    emits no zone and Jackson reads it as UTC, silently shifting every window
///    by the device's offset — four hours, in the UAE.
class GatePassCreateScreen extends ConsumerStatefulWidget {
  const GatePassCreateScreen({super.key});

  @override
  ConsumerState<GatePassCreateScreen> createState() =>
      _GatePassCreateScreenState();
}

class _GatePassCreateScreenState extends ConsumerState<GatePassCreateScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final _purposeCtrl = TextEditingController();
  final _vehicleCtrl = TextEditingController();

  bool _recurring = false;
  String? _selectedUnitId;
  bool _submitting = false;

  // Single-visit window.
  DateTime? _visitDate;
  TimeOfDay _startTime = const TimeOfDay(hour: 9, minute: 0);
  TimeOfDay _endTime = const TimeOfDay(hour: 18, minute: 0);

  // Recurring window.
  DateTime? _recurStart;
  DateTime? _recurEnd;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    _purposeCtrl.dispose();
    _vehicleCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final leases = ref.watch(activeLeasesProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: m.surface,
        foregroundColor: m.textPrimary,
        elevation: 0,
        title: Text(l.title),
      ),
      body: leases.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ErrorState(
          message: l.tenancyCheckFailed,
          onRetry: () => ref.invalidate(activeLeasesProvider),
        ),
        data: (rows) {
          if (rows.isEmpty) return _NoActiveLease(l: l);
          return _form(rows, m, l);
        },
      ),
    );
  }

  Widget _form(List<Map<String, dynamic>> leases, MiftahColors m, _L l) {
    // One lease is the overwhelmingly common case; choosing between one option
    // is not a choice, so it is made silently and the picker never appears.
    if (_selectedUnitId == null && leases.length == 1) {
      _selectedUnitId = leases.first['unitId']?.toString();
    }

    return LoadingOverlay(
      isLoading: _submitting,
      child: Form(
        key: _formKey,
        child: ListView(
          padding: EdgeInsets.fromLTRB(
            16,
            16,
            16,
            AppInsets.bottomNav(context, spacing: 32),
          ),
          children: [
            if (leases.length > 1) ...[
              _label(l.unit, m),
              _UnitPicker(
                leases: leases,
                selectedUnitId: _selectedUnitId,
                l: l,
                onChanged: (id) => setState(() => _selectedUnitId = id),
              ),
              const SizedBox(height: 18),
            ],
            _label(l.guestName, m),
            TextFormField(
              controller: _nameCtrl,
              textCapitalization: TextCapitalization.words,
              // 160 is the server's @Size cap; stopping the field here turns a
              // 400 into a keystroke that simply does not land.
              maxLength: 160,
              decoration: InputDecoration(
                hintText: l.whoIsVisiting,
                counterText: '',
              ),
              validator: (v) =>
                  (v == null || v.trim().isEmpty) ? l.enterGuestName : null,
            ),
            const SizedBox(height: 14),
            _label(l.guestPhone, m),
            TextFormField(
              controller: _phoneCtrl,
              keyboardType: TextInputType.phone,
              maxLength: 32,
              decoration: const InputDecoration(
                hintText: '+971 50 123 4567',
                counterText: '',
              ),
              validator: (v) {
                final raw = (v ?? '').trim();
                if (raw.isEmpty) return l.enterGuestPhone;
                if (!isValidE164(normalizePhone(raw))) {
                  return l.phoneCountryCode;
                }
                return null;
              },
            ),
            const SizedBox(height: 14),
            _label(l.purposeOptional, m),
            TextFormField(
              controller: _purposeCtrl,
              maxLength: 240,
              decoration: InputDecoration(
                hintText: l.purposeHint,
                counterText: '',
              ),
            ),
            const SizedBox(height: 14),
            _label(l.vehicleOptional, m),
            TextFormField(
              controller: _vehicleCtrl,
              textCapitalization: TextCapitalization.characters,
              maxLength: 32,
              decoration: const InputDecoration(
                hintText: 'DXB A 12345',
                counterText: '',
              ),
            ),
            const SizedBox(height: 22),
            _label(l.passType, m),
            _TypeToggle(
              recurring: _recurring,
              l: l,
              onChanged: (value) => setState(() => _recurring = value),
            ),
            const SizedBox(height: 10),
            _TypeExplainer(recurring: _recurring, l: l),
            const SizedBox(height: 22),
            if (_recurring) ..._recurringFields(l) else ..._singleFields(l),
            const SizedBox(height: 26),
            SizedBox(
              height: 52,
              child: ElevatedButton(
                onPressed: _submitting ? null : _submit,
                child: Text(_recurring ? l.requestPass : l.createPass),
              ),
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _singleFields(_L l) => [
    _label(l.visitDate, context.miftah),
    _DateField(
      value: _visitDate,
      hint: l.pickADate,
      onPick: (d) => setState(() => _visitDate = d),
    ),
    const SizedBox(height: 14),
    Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _label(l.from, context.miftah),
              _TimeField(
                value: _startTime,
                onPick: (t) => setState(() => _startTime = t),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _label(l.until, context.miftah),
              _TimeField(
                value: _endTime,
                onPick: (t) => setState(() => _endTime = t),
              ),
            ],
          ),
        ),
      ],
    ),
  ];

  List<Widget> _recurringFields(_L l) => [
    _label(l.starts, context.miftah),
    _DateField(
      value: _recurStart,
      hint: l.firstDay,
      onPick: (d) => setState(() => _recurStart = d),
    ),
    const SizedBox(height: 14),
    _label(l.expires, context.miftah),
    _DateField(
      value: _recurEnd,
      hint: l.lastDay,
      onPick: (d) => setState(() => _recurEnd = d),
    ),
  ];

  /// The window as two local DateTimes, or null when the form has not been
  /// filled in far enough to have one.
  ///
  /// A recurring pass's last day is inclusive — a renter who types "expires on
  /// the 30th" means the 30th is still good — so the window runs to the end of
  /// that day rather than to its first instant, which would expire the pass a
  /// day early.
  ({DateTime from, DateTime to})? _window() {
    if (_recurring) {
      final start = _recurStart;
      final end = _recurEnd;
      if (start == null || end == null) return null;
      return (
        from: DateTime(start.year, start.month, start.day),
        to: DateTime(end.year, end.month, end.day, 23, 59, 59),
      );
    }
    final date = _visitDate;
    if (date == null) return null;
    return (
      from: DateTime(
        date.year,
        date.month,
        date.day,
        _startTime.hour,
        _startTime.minute,
      ),
      to: DateTime(
        date.year,
        date.month,
        date.day,
        _endTime.hour,
        _endTime.minute,
      ),
    );
  }

  Future<void> _submit() async {
    final l = _L(context.isAr);
    if (!_formKey.currentState!.validate()) return;

    final unitId = _selectedUnitId;
    if (unitId == null) {
      _toast(l.chooseUnit);
      return;
    }

    final window = _window();
    if (window == null) {
      _toast(_recurring ? l.pickRecurringWindow : l.pickVisitDate);
      return;
    }
    // The backend 400s on validTo <= validFrom. Catching it here names the
    // actual mistake instead of relaying a validation error about field names
    // the renter never saw.
    if (!window.to.isAfter(window.from)) {
      _toast(_recurring ? l.lastDayAfterFirst : l.endAfterStart);
      return;
    }

    setState(() => _submitting = true);
    try {
      final created = await ref.read(gatePassServiceProvider).create({
        'unitId': unitId,
        'guestName': _nameCtrl.text.trim(),
        'guestPhone': normalizePhone(_phoneCtrl.text.trim()),
        if (_purposeCtrl.text.trim().isNotEmpty)
          'purpose': _purposeCtrl.text.trim(),
        if (_vehicleCtrl.text.trim().isNotEmpty)
          'vehicleNumber': _vehicleCtrl.text.trim(),
        'passType': _recurring ? 'RECURRING' : 'SINGLE_USE',
        'validFrom': instant(window.from),
        'validTo': instant(window.to),
      });

      // The list is now missing the pass that was just made.
      ref.invalidate(myPassesProvider);

      if (!mounted) return;
      final id = created['id']?.toString();
      if (id == null) {
        // Created, but with nothing to navigate to. Sending the renter back to
        // the list is honest: the pass is there.
        context.pop();
        return;
      }
      // pushReplacement, not push: the create form has done its job and the
      // renter should not be able to step back into a filled-in copy of it.
      context.pushReplacement('/gatepass/$id');
    } catch (error) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast(_describeCreateError(error, l));
    }
  }

  void _toast(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}

/// Turns a failed create into something a renter can act on.
///
/// The 404 case matters most: the backend answers 404 (not 403) for a unit that
/// is not on the caller's active lease, so the bare status reads as "missing"
/// when it means "not yours / not active". Relaying "not found" would send the
/// renter looking for a bug.
String _describeCreateError(Object error, _L l) {
  if (error is DioException) {
    final status = error.response?.statusCode;
    if (status == 404) {
      return l.unitNotOnActiveLease;
    }
    final data = error.response?.data;
    if (data is Map && data['message'] is String) {
      final message = (data['message'] as String).trim();
      if (message.isNotEmpty) return message;
    }
  }
  return l.createFailed;
}

Widget _label(String text, MiftahColors m) => Padding(
  padding: const EdgeInsets.only(bottom: 6),
  child: Text(
    text,
    style: TextStyle(
      fontSize: 12.5,
      fontWeight: FontWeight.w700,
      color: m.textSecondary,
    ),
  ),
);

/// Shown instead of the form when the renter has no ACTIVE lease.
///
/// This is a dead end on purpose. Every field below it would be filled in for a
/// unit the server will refuse, and the refusal is a 404 that explains nothing —
/// so the block has to carry the explanation itself.
class _NoActiveLease extends StatelessWidget {
  final _L l;
  const _NoActiveLease({required this.l});

  @override
  Widget build(BuildContext context) {
    return EmptyState(
      icon: Icons.home_outlined,
      title: l.noActiveTenancy,
      subtitle: l.noActiveTenancySub,
    );
  }
}

class _UnitPicker extends StatelessWidget {
  final List<Map<String, dynamic>> leases;
  final String? selectedUnitId;
  final _L l;
  final ValueChanged<String?> onChanged;

  const _UnitPicker({
    required this.leases,
    required this.selectedUnitId,
    required this.l,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return DropdownButtonFormField<String>(
      initialValue: selectedUnitId,
      decoration: InputDecoration(hintText: l.whichUnit),
      items: leases.map((lease) {
        final unitId = lease['unitId']?.toString();
        final unit = lease['unitIdentifier']?.toString();
        final property = lease['propertyName']?.toString();
        final label = [
          property,
          unit == null ? null : l.unitLabel(unit),
        ].whereType<String>().join(' · ');
        return DropdownMenuItem(
          value: unitId,
          child: Text(
            label.isEmpty ? l.unitFallback : label,
            overflow: TextOverflow.ellipsis,
          ),
        );
      }).toList(),
      onChanged: onChanged,
    );
  }
}

class _TypeToggle extends StatelessWidget {
  final bool recurring;
  final _L l;
  final ValueChanged<bool> onChanged;

  const _TypeToggle({
    required this.recurring,
    required this.l,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _segment(m, label: l.singleVisit, selected: !recurring, value: false),
          _segment(m, label: l.recurring, selected: recurring, value: true),
        ],
      ),
    );
  }

  Widget _segment(
    MiftahColors m, {
    required String label,
    required bool selected,
    required bool value,
  }) {
    return Expanded(
      child: GestureDetector(
        onTap: () => onChanged(value),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          padding: const EdgeInsets.symmetric(vertical: 11),
          decoration: BoxDecoration(
            color: selected ? m.surface : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
            border: selected ? Border.all(color: m.border) : null,
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: selected ? m.textPrimary : m.textMuted,
            ),
          ),
        ),
      ),
    );
  }
}

/// Says what the chosen type actually does, *before* the pass is created.
///
/// The approval caveat is the whole reason this exists. A SINGLE_USE pass is
/// ACTIVE the moment it is made; a RECURRING one is PENDING_APPROVAL and does
/// not open the gate until a manager or guard approves it. A renter who sets up
/// a recurring maid pass and expects it to work this morning finds out at the
/// gate, with their maid standing there — which is the one place this app cannot
/// explain itself.
class _TypeExplainer extends StatelessWidget {
  final bool recurring;
  final _L l;
  const _TypeExplainer({required this.recurring, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final color = recurring ? m.warning : m.success;
    final background = recurring ? m.warningBg : m.successBg;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            recurring ? Icons.schedule_outlined : Icons.check_circle_outline,
            size: 18,
            color: color,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  recurring ? l.needsApproval : l.worksStraightAway,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  recurring ? l.recurringExplainer : l.singleExplainer,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.35,
                    color: m.textSecondary,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DateField extends StatelessWidget {
  final DateTime? value;
  final String hint;
  final ValueChanged<DateTime> onPick;

  const _DateField({
    required this.value,
    required this.hint,
    required this.onPick,
  });

  @override
  Widget build(BuildContext context) {
    final label = value == null
        ? hint
        : '${value!.day.toString().padLeft(2, '0')}/'
              '${value!.month.toString().padLeft(2, '0')}/${value!.year}';

    return _PickerField(
      icon: Icons.calendar_today_outlined,
      label: label,
      muted: value == null,
      onTap: () async {
        final now = DateTime.now();
        final picked = await showDatePicker(
          context: context,
          initialDate: value ?? now,
          firstDate: DateTime(now.year, now.month, now.day),
          lastDate: now.add(const Duration(days: 365)),
        );
        if (picked != null) onPick(picked);
      },
    );
  }
}

class _TimeField extends StatelessWidget {
  final TimeOfDay value;
  final ValueChanged<TimeOfDay> onPick;

  const _TimeField({required this.value, required this.onPick});

  @override
  Widget build(BuildContext context) {
    return _PickerField(
      icon: Icons.access_time,
      label: value.format(context),
      muted: false,
      onTap: () async {
        final picked = await showTimePicker(
          context: context,
          initialTime: value,
        );
        if (picked != null) onPick(picked);
      },
    );
  }
}

class _PickerField extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool muted;
  final VoidCallback onTap;

  const _PickerField({
    required this.icon,
    required this.label,
    required this.muted,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 15),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Icon(icon, size: 17, color: m.textMuted),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                label,
                style: TextStyle(
                  fontSize: 14,
                  color: muted ? m.textMuted : m.textPrimary,
                  fontWeight: muted ? FontWeight.w400 : FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
