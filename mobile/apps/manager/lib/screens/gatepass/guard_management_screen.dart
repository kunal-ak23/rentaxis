import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/guard_admin_service.dart';
import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'حراس الأمن' : 'Security Guards';
  String get loadFailed => ar ? 'فشل تحميل الحراس' : 'Failed to load guards';
  String get noGuardsTitle =>
      ar ? 'لا يوجد حراس أمن بعد' : 'No security guards yet';
  String get noGuardsSubtitle => ar
      ? 'أضف حارسًا، ثم عيّن له العقارات التي يعمل بها.'
      : 'Add a guard, then assign the properties they work.';
  String get unnamedGuard => ar ? 'حارس بلا اسم' : 'Unnamed guard';
  String get couldNotLoadAssignedProperties => ar
      ? 'تعذّر تحميل العقارات المعيّنة'
      : 'Could not load assigned properties';
  String get unpostedWarning => ar
      ? 'لا توجد عقارات معيّنة — لا يرى هذا الحارس أي زوار ولا يمكنه مسح تصريح. اضغط للتعيين.'
      : 'No properties assigned — this guard sees no visitors and cannot '
            'scan a pass. Tap to assign.';
  String propertiesAssigned(int count) => ar
      ? '$count ${count == 1 ? 'عقار معيّن' : 'عقارات معيّنة'}'
      : '$count ${count == 1 ? 'property' : 'properties'} assigned';
  String propertiesFor(String name) =>
      ar ? 'عقارات $name' : 'Properties for $name';
  String get assignedProperties =>
      ar ? 'العقارات المعيّنة' : 'Assigned properties';
  String get assignSheetSubtitle => ar
      ? 'يرى الحارس الزوار المتوقعين ويمكنه مسح التصاريح فقط في العقارات المحددة هنا.'
      : 'The guard sees expected visitors and may scan passes only at the '
            'properties ticked here.';
  String get couldNotLoadProperties =>
      ar ? 'فشل تحميل العقارات' : 'Failed to load properties';
  String get noPropertiesTitle => ar ? 'لا توجد عقارات' : 'No properties';
  String get noPropertiesSubtitle => ar
      ? 'أضف عقارًا قبل تعيين حارس له.'
      : 'Add a property before posting a guard to it.';
  String get property => ar ? 'عقار' : 'Property';
  String get save => ar ? 'حفظ' : 'Save';
  String get saving => ar ? 'جارٍ الحفظ…' : 'Saving…';
  String get propertiesUpdated =>
      ar ? 'تم تحديث العقارات' : 'Properties updated';
  String get couldNotUpdateAssignment => ar
      ? 'تعذّر تحديث التعيين. عقارات الحارس لم تتغيّر.'
      : 'Could not update the assignment. The guard’s properties are '
            'unchanged.';
  String get newSecurityGuard => ar ? 'حارس أمن جديد' : 'New Security Guard';
  String get fullName => ar ? 'الاسم الكامل' : 'Full Name';
  String get nameRequired => ar ? 'الاسم مطلوب' : 'Name is required';
  String get phoneNumber => ar ? 'رقم الهاتف' : 'Phone Number';
  String get phoneHelper => ar
      ? 'يسجّل الحارس الدخول بهذا الرقم'
      : 'The guard signs in with this number';
  String get email => ar ? 'البريد الإلكتروني' : 'Email';
  String get guardAdded => ar
      ? 'تمت إضافة الحارس. يمكنه تسجيل الدخول برقم هاتفه الآن — عيّن عقاراته بعد ذلك.'
      : 'Guard added. They can sign in with their phone number now — '
            'assign their properties next.';
  String get createGuard => ar ? 'إنشاء حارس' : 'Create Guard';
  String duplicatePhone(String? name) => ar
      ? 'رقم الهاتف هذا مسجّل بالفعل لدى ${name?.isNotEmpty == true ? name : 'حارس آخر'}.'
      : 'That phone number is already registered to '
            '${name?.isNotEmpty == true ? name : 'another guard'}.';

  String guardStatus(String value) => switch (value) {
    'ACTIVE' => ar ? 'نشط' : 'Active',
    'SUSPENDED' => ar ? 'موقوف' : 'Suspended',
    'INACTIVE' => ar ? 'غير نشط' : 'Inactive',
    'DISABLED' => ar ? 'معطّل' : 'Disabled',
    'PENDING' => ar ? 'قيد الانتظار' : 'Pending',
    _ => value.replaceAll('_', ' '),
  };
}

Color _guardStatusColor(String status, LegacyMiftahColors m) => switch (status) {
  'ACTIVE' => m.success,
  'SUSPENDED' || 'DISABLED' => m.danger,
  'PENDING' => m.warning,
  _ => m.textMuted,
};

/// The tenant's security guards, and the properties each is posted to.
///
/// The posting is the point of this screen, not a detail of it. A guard with no
/// assignments logs in perfectly well and then sees nothing: `expected-today`
/// and `approvals` both return `[]` for an unposted guard, and a scan at any
/// gate is refused. Nothing on the guard's own device can tell that apart from a
/// quiet day beyond a generic hint to ask their manager — so the manager is the
/// only one who can notice, and this is where they notice it. Hence the banner
/// and the per-card flag rather than a count buried in a detail view.
class GuardManagementScreen extends ConsumerWidget {
  const GuardManagementScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final guards = ref.watch(guardsProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
      ),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(guardsProvider.future),
        color: AppColors.accent,
        child: guards.when(
          loading: () => const Center(
            child: CircularProgressIndicator(color: AppColors.accent),
          ),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: l.loadFailed,
              onRetry: () => ref.invalidate(guardsProvider),
            ),
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return _Scrollable(
                child: EmptyState(
                  icon: Icons.shield_outlined,
                  title: l.noGuardsTitle,
                  subtitle: l.noGuardsSubtitle,
                ),
              );
            }

            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 88),
              itemCount: rows.length,
              itemBuilder: (context, index) => AnimatedListItem(
                index: index,
                child: _GuardCard(guard: rows[index], l: l),
              ),
            );
          },
        ),
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.accent,
        onPressed: () => _openCreateSheet(context, ref),
        child: const Icon(Icons.person_add_outlined, color: AppColors.primary),
      ),
    );
  }

  void _openCreateSheet(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => const _CreateGuardSheet(),
    );
  }
}

/// One guard, with their posting.
///
/// Each card reads its own assignments, so a list of N guards issues N requests
/// to `/guards/{id}/properties`. There is no batch endpoint, and the alternative
/// — showing the posting only after a tap — would hide exactly the guards this
/// screen exists to surface: the unposted ones. A tenant's guard roster is a
/// handful of rows, so the cost is bounded and paid once per visit.
class _GuardCard extends ConsumerWidget {
  const _GuardCard({required this.guard, required this.l});

  final Map<String, dynamic> guard;
  final _L l;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final id = guard['id']?.toString();
    final name = (guard['name'] as String?)?.trim();
    final phone = (guard['phoneNumber'] as String?)?.trim();
    final email = (guard['email'] as String?)?.trim();
    final status = (guard['status'] as String?)?.trim();
    final assignments = id == null
        ? const AsyncValue<List<String>>.data([])
        : ref.watch(guardPropertiesProvider(id));

    final nameStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(fontWeight: FontWeight.w600, fontSize: 14)
        : GoogleFonts.plusJakartaSans(fontWeight: FontWeight.w600, fontSize: 14);
    final metaStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textSecondary)
        : GoogleFonts.plusJakartaSans(fontSize: 12, color: m.textSecondary);
    final mutedStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textMuted)
        : GoogleFonts.plusJakartaSans(fontSize: 12, color: m.textMuted);

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: id == null
              ? null
              : () => _openAssignSheet(context, id, name, l),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    CircleAvatar(
                      backgroundColor: AppColors.accent.withValues(alpha: 0.14),
                      child: Text(
                        (name?.isNotEmpty ?? false)
                            ? name![0].toUpperCase()
                            : '?',
                        style: GoogleFonts.plusJakartaSans(
                          color: AppColors.accentDark,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Flexible(
                                child: Text(
                                  name?.isNotEmpty == true
                                      ? name!
                                      : l.unnamedGuard,
                                  style: nameStyle,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              if (status != null && status != 'ACTIVE') ...[
                                const SizedBox(width: 8),
                                _StatusPill(
                                  label: l.guardStatus(status),
                                  color: _guardStatusColor(status, m),
                                  ar: l.ar,
                                ),
                              ],
                            ],
                          ),
                          // The phone is the guard's actual credential — it is
                          // what the OTP is sent to and what identifies them at
                          // login — so it leads, ahead of the email they never
                          // use.
                          if (phone != null && phone.isNotEmpty) ...[
                            const SizedBox(height: 3),
                            Text(phone, style: metaStyle),
                          ],
                          if (email != null && email.isNotEmpty) ...[
                            const SizedBox(height: 2),
                            Text(email, style: mutedStyle),
                          ],
                        ],
                      ),
                    ),
                    Icon(Icons.chevron_right, color: m.textMuted, size: 20),
                  ],
                ),
                const SizedBox(height: 10),
                assignments.when(
                  loading: () => const ShimmerLoading(
                    height: 22,
                    width: 160,
                    borderRadius: 6,
                  ),
                  error: (_, _) => Text(
                    l.couldNotLoadAssignedProperties,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 12,
                            color: m.danger,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 12,
                            color: m.danger,
                          ),
                  ),
                  data: (propertyIds) => propertyIds.isEmpty
                      ? _UnpostedFlag(l: l, m: m)
                      : _PostedChip(count: propertyIds.length, l: l),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _openAssignSheet(
    BuildContext context,
    String userId,
    String? name,
    _L l,
  ) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) =>
          _AssignPropertiesSheet(userId: userId, guardName: name, l: l),
    );
  }
}

/// The zero-assignment case, stated in terms of what it costs the guard.
///
/// "0 properties" would be read as a number; this is read as a problem — which
/// it is, and one only the manager can fix.
class _UnpostedFlag extends StatelessWidget {
  const _UnpostedFlag({required this.l, required this.m});

  final _L l;
  final LegacyMiftahColors m;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: m.warningBg,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: m.warning.withValues(alpha: 0.4)),
      ),
      child: Row(
        children: [
          Icon(Icons.warning_amber_rounded, size: 16, color: m.warning),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              l.unpostedWarning,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w600,
                      color: m.warning,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: m.warning,
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PostedChip extends StatelessWidget {
  const _PostedChip({required this.count, required this.l});

  final int count;
  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.accent.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        l.propertiesAssigned(count),
        style: l.ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: AppColors.accentDark,
              )
            : GoogleFonts.plusJakartaSans(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: AppColors.accentDark,
              ),
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({
    required this.label,
    required this.color,
    required this.ar,
  });

  final String label;
  final Color color;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.plusJakartaSans(
                fontSize: 10,
                fontWeight: FontWeight.w600,
                letterSpacing: 1,
                color: color,
              ),
      ),
    );
  }
}

/// Multi-select of the tenant's properties for one guard.
///
/// Replace-all, matching `PUT /guards/{id}/properties`: what is ticked when Save
/// is pressed becomes the guard's entire posting. There is no delta to compute
/// and none is computed.
class _AssignPropertiesSheet extends ConsumerStatefulWidget {
  const _AssignPropertiesSheet({
    required this.userId,
    this.guardName,
    required this.l,
  });

  final String userId;
  final String? guardName;
  final _L l;

  @override
  ConsumerState<_AssignPropertiesSheet> createState() =>
      _AssignPropertiesSheetState();
}

class _AssignPropertiesSheetState
    extends ConsumerState<_AssignPropertiesSheet> {
  Set<String>? _selected;
  bool _saving = false;
  String? _error;

  Future<void> _save() async {
    final selected = _selected;
    if (selected == null || _saving) return;
    final l = widget.l;

    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      // The complete desired list, not a delta.
      await ref
          .read(guardPropertiesProvider(widget.userId).notifier)
          .save(selected.toList());
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.propertiesUpdated),
          backgroundColor: AppColors.success,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (_) {
      if (!mounted) return;
      // The sheet stays open on the selection the manager made, so they can
      // retry without re-ticking. State is untouched, so the card behind still
      // shows the posting that is actually in force.
      setState(() => _error = l.couldNotUpdateAssignment);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = widget.l;
    final properties = ref.watch(propertiesProvider);
    final assignments = ref.watch(guardPropertiesProvider(widget.userId));

    // Seed the tick boxes from what the server says is assigned, once it has
    // arrived. Only once: re-seeding on rebuild would undo the manager's edits.
    assignments.whenData((ids) => _selected ??= ids.toSet());

    final headingStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(
            fontSize: 18,
            fontWeight: FontWeight.w600,
            color: m.textPrimary,
          )
        : GoogleFonts.plusJakartaSans(
            fontSize: 18,
            fontWeight: FontWeight.w600,
            color: m.textPrimary,
          );
    final subtitleStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textSecondary)
        : GoogleFonts.plusJakartaSans(fontSize: 12, color: m.textSecondary);

    return Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        20,
        24,
        MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: m.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            widget.guardName?.isNotEmpty == true
                ? l.propertiesFor(widget.guardName!)
                : l.assignedProperties,
            style: headingStyle,
          ),
          const SizedBox(height: 6),
          Text(l.assignSheetSubtitle, style: subtitleStyle),
          const SizedBox(height: 14),
          Flexible(
            child: properties.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(
                  child: CircularProgressIndicator(color: AppColors.accent),
                ),
              ),
              error: (_, _) => ErrorState(
                message: l.couldNotLoadProperties,
                onRetry: () => ref.invalidate(propertiesProvider),
              ),
              data: (rows) {
                if (rows.isEmpty) {
                  return EmptyState(
                    icon: Icons.apartment_outlined,
                    title: l.noPropertiesTitle,
                    subtitle: l.noPropertiesSubtitle,
                  );
                }
                if (_selected == null) {
                  return const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Center(
                      child: CircularProgressIndicator(color: AppColors.accent),
                    ),
                  );
                }
                return SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: rows.map((property) {
                      final id = property['id']?.toString();
                      if (id == null) return const SizedBox.shrink();
                      final label = (property['name'] as String?)?.trim();
                      return CheckboxListTile(
                        key: Key('property-$id'),
                        contentPadding: EdgeInsets.zero,
                        controlAffinity: ListTileControlAffinity.leading,
                        activeColor: AppColors.accent,
                        checkColor: AppColors.primary,
                        value: _selected!.contains(id),
                        title: Text(
                          label?.isNotEmpty == true ? label! : l.property,
                          style: l.ar
                              ? GoogleFonts.notoNaskhArabic(
                                  fontSize: 14,
                                  color: m.textPrimary,
                                )
                              : GoogleFonts.plusJakartaSans(
                                  fontSize: 14,
                                  color: m.textPrimary,
                                ),
                        ),
                        onChanged: _saving
                            ? null
                            : (checked) => setState(() {
                                if (checked == true) {
                                  _selected!.add(id);
                                } else {
                                  _selected!.remove(id);
                                }
                              }),
                      );
                    }).toList(),
                  ),
                );
              },
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 10),
            Text(
              _error!,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.danger)
                  : GoogleFonts.plusJakartaSans(fontSize: 12, color: m.danger),
            ),
          ],
          const SizedBox(height: 16),
          GoldButton(
            key: const Key('save-assignments'),
            label: _saving ? l.saving : l.save,
            onPressed: _saving || _selected == null ? null : _save,
            icon: _saving
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: AppColors.primary,
                    ),
                  )
                : null,
          ),
        ],
      ),
    );
  }
}

/// Create a guard.
///
/// The fields are name, phone and email, and only two of them are real:
///
///  * **Phone** is the credential. It is what the OTP is sent to and the only
///    way a guard ever signs in, and it is stored verbatim — so it is
///    normalized and validated to E.164 here, because a guard saved with a
///    malformed or spaced phone cannot log in and nothing downstream reports
///    that.
///  * **Email** is required by `UserService.createUser`, which lowercases it
///    unguarded. Guards are the one role that gets no invite token and no
///    set-password mail, so nothing is ever sent to it — but it cannot be
///    omitted, and it must be unique within the tenant.
///
/// No password field: see [generateUnusedGuardPassword].
class _CreateGuardSheet extends ConsumerStatefulWidget {
  const _CreateGuardSheet();

  @override
  ConsumerState<_CreateGuardSheet> createState() => _CreateGuardSheetState();
}

class _CreateGuardSheetState extends ConsumerState<_CreateGuardSheet> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _emailCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController(text: '+971');
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _emailCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  /// Catches the same-tenant duplicate before it becomes a request.
  ///
  /// `uq_users_guard_phone` is global, so this cannot catch a guard in another
  /// tenant — but it does catch the case a manager can actually see and fix, and
  /// it does so with the precise wording the server cannot give
  /// ([describeGuardCreateFailure] explains why).
  String? _localDuplicate(String phone, _L l) {
    final guards = ref.read(guardsProvider).value ?? const [];
    final normalized = normalizeGuardPhone(phone);
    for (final guard in guards) {
      final existing = (guard['phoneNumber'] as String?)?.trim();
      if (existing != null && normalizeGuardPhone(existing) == normalized) {
        final name = (guard['name'] as String?)?.trim();
        return l.duplicatePhone(name);
      }
    }
    return null;
  }

  Future<void> _submit() async {
    if (_saving) return;
    final l = _L(context.isAr);
    // Validates first, so a malformed phone or email never reaches the network.
    if (!(_formKey.currentState?.validate() ?? false)) return;

    final duplicate = _localDuplicate(_phoneCtrl.text, l);
    if (duplicate != null) {
      setState(() => _error = duplicate);
      return;
    }

    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(guardAdminServiceProvider)
          .createGuard(
            name: _nameCtrl.text,
            email: _emailCtrl.text,
            phoneNumber: _phoneCtrl.text,
          );
      if (!mounted) return;
      ref.invalidate(guardsProvider);
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.guardAdded),
          backgroundColor: AppColors.success,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = describeGuardCreateFailure(error));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final headingStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(
            fontSize: 18,
            fontWeight: FontWeight.w600,
            color: m.textPrimary,
          )
        : GoogleFonts.plusJakartaSans(
            fontSize: 18,
            fontWeight: FontWeight.w600,
            color: m.textPrimary,
          );
    final fieldStyle = l.ar
        ? GoogleFonts.notoNaskhArabic(fontSize: 14, color: m.textPrimary)
        : GoogleFonts.plusJakartaSans(fontSize: 14, color: m.textPrimary);

    return Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        20,
        24,
        MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: Form(
        key: _formKey,
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
                    color: m.borderStrong,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              Text(l.newSecurityGuard, style: headingStyle),
              const SizedBox(height: 18),
              TextFormField(
                controller: _nameCtrl,
                style: fieldStyle,
                decoration: InputDecoration(
                  labelText: l.fullName,
                  prefixIcon: const Icon(Icons.person_outline),
                ),
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? l.nameRequired : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('guard-phone'),
                controller: _phoneCtrl,
                keyboardType: TextInputType.phone,
                style: fieldStyle,
                decoration: InputDecoration(
                  labelText: l.phoneNumber,
                  helperText: l.phoneHelper,
                  prefixIcon: const Icon(Icons.phone_outlined),
                ),
                validator: validateGuardPhone,
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('guard-email'),
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                style: fieldStyle,
                decoration: InputDecoration(
                  labelText: l.email,
                  prefixIcon: const Icon(Icons.email_outlined),
                ),
                validator: validateGuardEmail,
              ),
              if (_error != null) ...[
                const SizedBox(height: 14),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: m.dangerBg,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _error!,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 12,
                            color: m.danger,
                            fontWeight: FontWeight.w600,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 12,
                            color: m.danger,
                            fontWeight: FontWeight.w600,
                          ),
                  ),
                ),
              ],
              const SizedBox(height: 20),
              GoldButton(
                key: const Key('create-guard'),
                label: _saving ? l.saving : l.createGuard,
                onPressed: _saving ? null : _submit,
                icon: _saving
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppColors.primary,
                        ),
                      )
                    : null,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
