import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/pass_display.dart';
import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تصاريح الدخول' : 'Gate Passes';
  String get visitorApprovals => ar ? 'موافقات الزوار' : 'Visitor approvals';
  String get newPass => ar ? 'تصريح جديد' : 'New pass';
  String get loadFailed =>
      ar ? 'تعذر تحميل تصاريح الدخول الخاصة بك.' : 'Could not load your gate passes.';
  String get noPasses => ar ? 'لا توجد تصاريح دخول بعد' : 'No gate passes yet';
  String get noPassesSub => ar
      ? 'أنشئ تصريحًا ليتمكن ضيفك من إظهاره عند البوابة بدلاً من '
            'تسجيله يدويًا من قبل الحارس.'
      : 'Create a pass and your guest can show it at the gate '
            'instead of being signed in by the guard.';
  String get createPass => ar ? 'إنشاء تصريح' : 'Create a pass';
  String get guestFallback => ar ? 'ضيف' : 'Guest';
  String get recurring => ar ? 'متكرر' : 'Recurring';
}

/// The renter's gate passes, newest first.
///
/// Rows carry no QR and no code: the credential lives one tap away on the detail
/// screen. That is not only tidiness — a list is the thing a renter holds up in
/// a lobby or hands to someone, and a screenful of live gate codes is a screenful
/// of keys.
class GatePassListScreen extends ConsumerWidget {
  const GatePassListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final passes = ref.watch(myPassesProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: m.surface,
        foregroundColor: m.textPrimary,
        elevation: 0,
        title: Text(l.title),
        actions: [
          IconButton(
            tooltip: l.visitorApprovals,
            onPressed: () => context.push('/gatepass/approvals'),
            icon: const Icon(Icons.approval_outlined),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/gatepass/create'),
        backgroundColor: AppColors.primary,
        icon: const Icon(Icons.add, color: Colors.white),
        label: Text(l.newPass, style: const TextStyle(color: Colors.white)),
      ),
      body: RefreshIndicator(
        color: m.isDark ? AppColors.accent : AppColors.primary,
        onRefresh: () => ref.refresh(myPassesProvider.future),
        child: passes.when(
          loading: () => const _ListShimmer(),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: l.loadFailed,
              onRetry: () => ref.invalidate(myPassesProvider),
            ),
          ),
          data: (rows) {
            if (rows.isEmpty) return _Scrollable(child: _NoPasses(l: l));
            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                16,
                12,
                16,
                AppInsets.bottomNav(context) + 72,
              ),
              itemCount: rows.length,
              itemBuilder: (context, index) => AnimatedListItem(
                index: index,
                child: _PassRow(pass: rows[index], l: l),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Wraps a centred state in a scroll view so pull-to-refresh still works when
/// there is nothing to scroll — otherwise the one gesture a renter reaches for
/// on a wrong-looking empty screen does nothing.
class _Scrollable extends StatelessWidget {
  final Widget child;
  const _Scrollable({required this.child});

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

class _NoPasses extends StatelessWidget {
  final _L l;
  const _NoPasses({required this.l});

  @override
  Widget build(BuildContext context) {
    return EmptyState(
      icon: Icons.qr_code_2_outlined,
      title: l.noPasses,
      subtitle: l.noPassesSub,
      actionLabel: l.createPass,
      onAction: () => context.push('/gatepass/create'),
    );
  }
}

class _PassRow extends StatelessWidget {
  final Map<String, dynamic> pass;
  final _L l;
  const _PassRow({required this.pass, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = passString(pass, 'status');
    final id = passString(pass, 'id');
    final guest = passString(pass, 'guestName') ?? l.guestFallback;
    final purpose = passString(pass, 'purpose');
    final vehicle = passString(pass, 'vehicleNumber');
    final recurring = passString(pass, 'passType') == 'RECURRING';

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Material(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          // A pass with no id cannot be opened — there is nothing to fetch. It
          // still renders, because hiding it would leave the renter wondering
          // where their pass went.
          onTap: id == null ? null : () => context.push('/gatepass/$id'),
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              border: Border.all(color: m.border),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        guest,
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: m.textPrimary,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 8),
                    StatusBadge(
                      label: gatePassStatusLabel(status, ar: l.ar),
                      color: gatePassStatusColor(status),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  formatWindow(
                    passInstant(pass, 'validFrom'),
                    passInstant(pass, 'validTo'),
                    ar: l.ar,
                  ),
                  style: TextStyle(fontSize: 12.5, color: m.textSecondary),
                ),
                if (purpose != null || vehicle != null || recurring) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      if (recurring)
                        _Tag(icon: Icons.repeat, label: l.recurring),
                      if (purpose != null)
                        _Tag(icon: Icons.notes_outlined, label: purpose),
                      if (vehicle != null)
                        _Tag(icon: Icons.directions_car, label: vehicle),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  final IconData icon;
  final String label;
  const _Tag({required this.icon, required this.label});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: m.textMuted),
          const SizedBox(width: 5),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 180),
            child: Text(
              label,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 11.5, color: m.textSecondary),
            ),
          ),
        ],
      ),
    );
  }
}

class _ListShimmer extends StatelessWidget {
  const _ListShimmer();

  @override
  Widget build(BuildContext context) {
    return ListView.builder(
      physics: const NeverScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      itemCount: 4,
      itemBuilder: (context, _) => const Padding(
        padding: EdgeInsets.only(bottom: 10),
        child: ShimmerLoading(width: double.infinity, height: 96),
      ),
    );
  }
}
