import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/guard_admin_service.dart';
import '../../providers/gate_pass_provider.dart';

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
    final guards = ref.watch(guardsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Security Guards')),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(guardsProvider.future),
        color: AppColors.primary,
        child: guards.when(
          loading: () => const Center(
            child: CircularProgressIndicator(color: AppColors.primary),
          ),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: 'Failed to load guards',
              onRetry: () => ref.invalidate(guardsProvider),
            ),
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return const _Scrollable(
                child: EmptyState(
                  icon: Icons.shield_outlined,
                  title: 'No security guards yet',
                  subtitle: 'Add a guard, then assign the properties they work.',
                ),
              );
            }

            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 88),
              itemCount: rows.length,
              itemBuilder: (context, index) => AnimatedListItem(
                index: index,
                child: _GuardCard(guard: rows[index]),
              ),
            );
          },
        ),
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () => _openCreateSheet(context, ref),
        child: const Icon(Icons.person_add_outlined, color: Colors.white),
      ),
    );
  }

  void _openCreateSheet(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
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
  const _GuardCard({required this.guard});

  final Map<String, dynamic> guard;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final id = guard['id']?.toString();
    final name = (guard['name'] as String?)?.trim();
    final phone = (guard['phoneNumber'] as String?)?.trim();
    final email = (guard['email'] as String?)?.trim();
    final status = (guard['status'] as String?)?.trim();
    final assignments =
        id == null ? const AsyncValue<List<String>>.data([]) : ref.watch(guardPropertiesProvider(id));

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: id == null ? null : () => _openAssignSheet(context, id, name),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  CircleAvatar(
                    backgroundColor: AppColors.primary.withValues(alpha: 0.1),
                    child: Text(
                      (name?.isNotEmpty ?? false) ? name![0].toUpperCase() : '?',
                      style: const TextStyle(
                        color: AppColors.primary,
                        fontWeight: FontWeight.w700,
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
                                name?.isNotEmpty == true ? name! : 'Unnamed guard',
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                  fontSize: 14,
                                ),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            if (status != null && status != 'ACTIVE') ...[
                              const SizedBox(width: 8),
                              StatusBadge(
                                label: status,
                                color: AppColors.textMuted,
                              ),
                            ],
                          ],
                        ),
                        // The phone is the guard's actual credential — it is what
                        // the OTP is sent to and what identifies them at login —
                        // so it leads, ahead of the email they never use.
                        if (phone != null && phone.isNotEmpty) ...[
                          const SizedBox(height: 3),
                          Text(
                            phone,
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ],
                        if (email != null && email.isNotEmpty) ...[
                          const SizedBox(height: 2),
                          Text(
                            email,
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textMuted,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const Icon(Icons.chevron_right,
                      color: AppColors.textMuted, size: 20),
                ],
              ),
              const SizedBox(height: 10),
              assignments.when(
                loading: () =>
                    const ShimmerLoading(height: 22, width: 160, borderRadius: 6),
                error: (_, _) => const Text(
                  'Could not load assigned properties',
                  style: TextStyle(fontSize: 12, color: AppColors.danger),
                ),
                data: (propertyIds) => propertyIds.isEmpty
                    ? const _UnpostedFlag()
                    : _PostedChip(count: propertyIds.length),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _openAssignSheet(BuildContext context, String userId, String? name) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _AssignPropertiesSheet(userId: userId, guardName: name),
    );
  }
}

/// The zero-assignment case, stated in terms of what it costs the guard.
///
/// "0 properties" would be read as a number; this is read as a problem — which
/// it is, and one only the manager can fix.
class _UnpostedFlag extends StatelessWidget {
  const _UnpostedFlag();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.warningLight,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.warning.withValues(alpha: 0.4)),
      ),
      child: const Row(
        children: [
          Icon(Icons.warning_amber_rounded,
              size: 16, color: AppColors.warning),
          SizedBox(width: 8),
          Expanded(
            child: Text(
              'No properties assigned — this guard sees no visitors and cannot '
              'scan a pass. Tap to assign.',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: AppColors.warning,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PostedChip extends StatelessWidget {
  const _PostedChip({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.accent.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        '$count ${count == 1 ? 'property' : 'properties'} assigned',
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: AppColors.accent,
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
  const _AssignPropertiesSheet({required this.userId, this.guardName});

  final String userId;
  final String? guardName;

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
        const SnackBar(
          content: Text('Properties updated'),
          backgroundColor: AppColors.success,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (_) {
      if (!mounted) return;
      // The sheet stays open on the selection the manager made, so they can
      // retry without re-ticking. State is untouched, so the card behind still
      // shows the posting that is actually in force.
      setState(() => _error =
          'Could not update the assignment. The guard’s properties are unchanged.');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final properties = ref.watch(propertiesProvider);
    final assignments = ref.watch(guardPropertiesProvider(widget.userId));

    // Seed the tick boxes from what the server says is assigned, once it has
    // arrived. Only once: re-seeding on rebuild would undo the manager's edits.
    assignments.whenData((ids) => _selected ??= ids.toSet());

    return Padding(
      padding: EdgeInsets.fromLTRB(
          24, 20, 24, MediaQuery.of(context).viewInsets.bottom + 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.border,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            widget.guardName?.isNotEmpty == true
                ? 'Properties for ${widget.guardName}'
                : 'Assigned properties',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 6),
          const Text(
            'The guard sees expected visitors and may scan passes only at the '
            'properties ticked here.',
            style: TextStyle(fontSize: 12, color: AppColors.textSecondary),
          ),
          const SizedBox(height: 14),
          Flexible(
            child: properties.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(
                  child: CircularProgressIndicator(color: AppColors.primary),
                ),
              ),
              error: (_, _) => ErrorState(
                message: 'Failed to load properties',
                onRetry: () => ref.invalidate(propertiesProvider),
              ),
              data: (rows) {
                if (rows.isEmpty) {
                  return const EmptyState(
                    icon: Icons.apartment_outlined,
                    title: 'No properties',
                    subtitle: 'Add a property before posting a guard to it.',
                  );
                }
                if (_selected == null) {
                  return const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Center(
                      child:
                          CircularProgressIndicator(color: AppColors.primary),
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
                        activeColor: AppColors.primary,
                        value: _selected!.contains(id),
                        title: Text(
                          label?.isNotEmpty == true ? label! : 'Property',
                          style: const TextStyle(fontSize: 14),
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
              style: const TextStyle(fontSize: 12, color: AppColors.danger),
            ),
          ],
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              key: const Key('save-assignments'),
              onPressed: _saving || _selected == null ? null : _save,
              child: _saving
                  ? const SizedBox(
                      height: 18,
                      width: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Text('Save'),
            ),
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
  String? _localDuplicate(String phone) {
    final guards = ref.read(guardsProvider).value ?? const [];
    final normalized = normalizeGuardPhone(phone);
    for (final guard in guards) {
      final existing = (guard['phoneNumber'] as String?)?.trim();
      if (existing != null && normalizeGuardPhone(existing) == normalized) {
        final name = (guard['name'] as String?)?.trim();
        return 'That phone number is already registered to '
            '${name?.isNotEmpty == true ? name : 'another guard'}.';
      }
    }
    return null;
  }

  Future<void> _submit() async {
    if (_saving) return;
    // Validates first, so a malformed phone or email never reaches the network.
    if (!(_formKey.currentState?.validate() ?? false)) return;

    final duplicate = _localDuplicate(_phoneCtrl.text);
    if (duplicate != null) {
      setState(() => _error = duplicate);
      return;
    }

    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(guardAdminServiceProvider).createGuard(
            name: _nameCtrl.text,
            email: _emailCtrl.text,
            phoneNumber: _phoneCtrl.text,
          );
      if (!mounted) return;
      ref.invalidate(guardsProvider);
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Guard added. They can sign in with their phone number '
              'now — assign their properties next.'),
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
    return Padding(
      padding: EdgeInsets.fromLTRB(
          24, 20, 24, MediaQuery.of(context).viewInsets.bottom + 24),
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
                    color: AppColors.border,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              Text('New Security Guard',
                  style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 18),
              TextFormField(
                controller: _nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Full Name',
                  prefixIcon: Icon(Icons.person_outline),
                ),
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Name is required' : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('guard-phone'),
                controller: _phoneCtrl,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: 'Phone Number',
                  helperText: 'The guard signs in with this number',
                  prefixIcon: Icon(Icons.phone_outlined),
                ),
                validator: validateGuardPhone,
              ),
              const SizedBox(height: 16),
              TextFormField(
                key: const Key('guard-email'),
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                  labelText: 'Email',
                  prefixIcon: Icon(Icons.email_outlined),
                ),
                validator: validateGuardEmail,
              ),
              if (_error != null) ...[
                const SizedBox(height: 14),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: AppColors.dangerLight,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _error!,
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppColors.danger,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 20),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  key: const Key('create-guard'),
                  onPressed: _saving ? null : _submit,
                  child: _saving
                      ? const SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Text('Create Guard'),
                ),
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
