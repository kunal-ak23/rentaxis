import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../gatepass/pass_display.dart';
import '../providers/gate_pass_provider.dart';

/// `/approvals` as a route of its own. The same queue is the home screen's third
/// tab; both render [ApprovalsView], which is where the behaviour lives.
class ApprovalsScreen extends StatelessWidget {
  const ApprovalsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        title: const Text('Approvals'),
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
      ),
      body: const SafeArea(child: ApprovalsView()),
    );
  }
}

/// Recurring passes waiting on a decision at the guard's assigned properties.
class ApprovalsView extends ConsumerWidget {
  const ApprovalsView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final approvals = ref.watch(approvalsProvider);

    return RefreshIndicator(
      onRefresh: () => ref.refresh(approvalsProvider.future),
      child: approvals.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: 'Could not load approvals.',
            onRetry: () => ref.invalidate(approvalsProvider),
          ),
        ),
        data: (passes) {
          if (passes.isEmpty) {
            return const _Scrollable(
              child: EmptyState(
                icon: Icons.inbox_outlined,
                title: 'Nothing waiting for approval',
                subtitle: 'Recurring passes raised for your gate appear here.\n\n'
                    'Never see any? Ask your manager to check that you are '
                    'assigned to a property.',
              ),
            );
          }

          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
            itemCount: passes.length,
            itemBuilder: (context, index) =>
                _ApprovalCard(pass: passes[index]),
          );
        },
      ),
    );
  }
}

/// One pending pass, with its decision buttons.
///
/// Stateful for one reason: [_deciding]. A decision is a network call, and two
/// taps on Approve would post two decisions — the second of which the backend
/// answers 400 for ("not pending approval"), turning a double-tap into an error
/// on a pass that was in fact approved.
class _ApprovalCard extends ConsumerStatefulWidget {
  const _ApprovalCard({required this.pass});

  final Map<String, dynamic> pass;

  @override
  ConsumerState<_ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends ConsumerState<_ApprovalCard> {
  bool _deciding = false;

  /// Refresh-on-success rather than an optimistic removal.
  ///
  /// The optimistic version has to guess at a failure: it would drop the card,
  /// then put it back on an error — and a card that reappears silently reads as
  /// a rendering glitch, not as "your approval did not happen". Since a decision
  /// is one tap on a short list, waiting out one round trip costs the guard
  /// nothing and keeps the screen unable to claim a decision the server
  /// refused.
  Future<void> _decide(bool approved) async {
    final id = passString(widget.pass, 'id');
    if (id == null || _deciding) return;

    setState(() => _deciding = true);
    try {
      await ref.read(gatePassServiceProvider).decide(id, approved);
      if (!mounted) return;
      // Only after the server has agreed.
      ref.invalidate(approvalsProvider);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(approved ? 'Pass approved' : 'Pass rejected'),
          backgroundColor: approved ? AppColors.success : AppColors.textMuted,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (_) {
      if (!mounted) return;
      // The card stays exactly as it was, so the queue still shows the pass as
      // undecided — which it is.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            approved
                ? 'Could not approve the pass. It is still pending — try again.'
                : 'Could not reject the pass. It is still pending — try again.',
          ),
          backgroundColor: AppColors.danger,
          behavior: SnackBarBehavior.floating,
        ),
      );
    } finally {
      if (mounted) setState(() => _deciding = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final pass = widget.pass;
    // Keyed per pass so a tap in a test — and a hit test in a rebuilt list —
    // names one card's button rather than "whichever Approve is on screen".
    final id = passString(pass, 'id') ?? '';
    final name = passString(pass, 'guestName') ?? 'Guest';
    final unit = passString(pass, 'unitNumber');
    final purpose = passString(pass, 'purpose');
    final vehicle = passString(pass, 'vehicleNumber');
    final window = formatWindow(
      passInstant(pass, 'validFrom'),
      passInstant(pass, 'validTo'),
    );

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  name,
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
                ),
              ),
              const StatusBadge(
                label: 'RECURRING',
                color: AppColors.info,
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (unit != null) _DetailRow(icon: Icons.home_outlined, value: 'Unit $unit'),
          _DetailRow(icon: Icons.schedule, value: window),
          if (purpose != null)
            _DetailRow(icon: Icons.notes_outlined, value: purpose),
          if (vehicle != null)
            _DetailRow(icon: Icons.directions_car, value: vehicle),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  key: Key('reject-$id'),
                  onPressed: _deciding ? null : () => _decide(false),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.danger,
                    side: const BorderSide(color: AppColors.danger),
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Text(
                    'Reject',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: ElevatedButton(
                  key: Key('approve-$id'),
                  onPressed: _deciding ? null : () => _decide(true),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.success,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: _deciding
                      ? const SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Text(
                          'Approve',
                          style: TextStyle(fontWeight: FontWeight.w700),
                        ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.icon, required this.value});

  final IconData icon;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 15, color: AppColors.textMuted),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(
                fontSize: 14,
                color: AppColors.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// See the note on the home screen's copy of this: a [RefreshIndicator] over a
/// non-scrolling child cannot be pulled.
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
