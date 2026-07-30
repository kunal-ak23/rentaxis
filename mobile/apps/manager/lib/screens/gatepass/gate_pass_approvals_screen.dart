import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/pass_display.dart';
import '../../providers/gate_pass_provider.dart';

/// The tenant's gate passes awaiting a decision.
///
/// Tenant-wide, unlike the guard app's copy of this queue — same endpoint, scope
/// chosen server-side by role. So a card here may be for any property, which is
/// why every card names its property and the guard app's does not.
class GatePassApprovalsScreen extends ConsumerWidget {
  const GatePassApprovalsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final approvals = ref.watch(approvalsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Gate Pass Approvals')),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(approvalsProvider.future),
        color: AppColors.primary,
        child: approvals.when(
          loading: () => const Center(
            child: CircularProgressIndicator(color: AppColors.primary),
          ),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: 'Failed to load approvals',
              onRetry: () => ref.invalidate(approvalsProvider),
            ),
          ),
          data: (passes) {
            if (passes.isEmpty) {
              return const _Scrollable(
                child: EmptyState(
                  icon: Icons.inbox_outlined,
                  title: 'Nothing waiting for approval',
                  subtitle:
                      'Recurring passes raised by renters appear here until '
                      'someone approves or rejects them.',
                ),
              );
            }

            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 80),
              itemCount: passes.length,
              itemBuilder: (context, index) => AnimatedListItem(
                index: index,
                child: _ApprovalCard(pass: passes[index]),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// One pending pass, with its decision buttons.
///
/// Stateful for [_deciding]: a decision is a network call, and two taps on
/// Approve would post two decisions — the second of which comes back 400,
/// turning a double-tap into an error on a pass that was in fact approved.
class _ApprovalCard extends ConsumerStatefulWidget {
  const _ApprovalCard({required this.pass});

  final Map<String, dynamic> pass;

  @override
  ConsumerState<_ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends ConsumerState<_ApprovalCard> {
  bool _deciding = false;

  /// Refresh-on-success rather than an optimistic removal, so the screen can
  /// never claim a decision the server refused.
  ///
  /// The three outcomes are deliberately distinct, because they call for
  /// different things from the manager:
  ///
  ///  * **Success** — the queue is re-read and the card goes.
  ///  * **400** — someone else decided this pass first. A manager and a guard
  ///    see the same queue (`/approvals` is shared, and a guard's scope is a
  ///    subset of this one), so this is an ordinary race, not an error the
  ///    manager can fix by retrying. `GatePassService.approve` rejects any pass
  ///    that is no longer PENDING_APPROVAL. Telling them to "try again" would
  ///    be a lie — the pass is settled — so the queue is refreshed and the card
  ///    disappears, which is the honest outcome. It also self-heals the stale
  ///    list rather than leaving other decided cards on screen.
  ///  * **Anything else** — the pass is still pending, the card stays exactly as
  ///    it was, and retrying is the right advice.
  Future<void> _decide(bool approved) async {
    final id = passString(widget.pass, 'id');
    if (id == null || _deciding) return;

    setState(() => _deciding = true);
    try {
      await ref.read(gatePassServiceProvider).decide(id, approved);
      if (!mounted) return;
      ref.invalidate(approvalsProvider); // only after the server has agreed
      _notify(
        approved ? 'Pass approved' : 'Pass rejected',
        approved ? AppColors.success : AppColors.textMuted,
      );
    } catch (error) {
      if (!mounted) return;
      if (_isAlreadyDecided(error)) {
        ref.invalidate(approvalsProvider);
        _notify(
          'Someone already decided this pass. Refreshing the queue.',
          AppColors.warning,
        );
      } else {
        _notify(
          approved
              ? 'Could not approve the pass. It is still pending — try again.'
              : 'Could not reject the pass. It is still pending — try again.',
          AppColors.danger,
        );
      }
    } finally {
      if (mounted) setState(() => _deciding = false);
    }
  }

  /// A 400 on this path means exactly one thing.
  ///
  /// The endpoint's only other client-error outcomes are 403 (wrong role — the
  /// screen is unreachable) and 404 (a guard probing a property they are not
  /// posted to — not a manager, whose scope is the whole tenant). The remaining
  /// 400 is `GatePassService.approve` refusing a pass that has left
  /// PENDING_APPROVAL.
  bool _isAlreadyDecided(Object error) =>
      error is DioException && error.response?.statusCode == 400;

  void _notify(String message, Color background) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: background,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final pass = widget.pass;
    // Keyed per pass so a tap in a test — and a hit test in a rebuilt list —
    // names one card's button rather than "whichever Approve is on screen".
    final id = passString(pass, 'id') ?? '';
    final name = passString(pass, 'guestName') ?? 'Guest';
    final property = passString(pass, 'propertyName');
    final unit = passString(pass, 'unitNumber');
    final purpose = passString(pass, 'purpose');
    final vehicle = passString(pass, 'vehicleNumber');
    final passType = passString(pass, 'passType');
    final window = formatWindow(
      passInstant(pass, 'validFrom'),
      passInstant(pass, 'validTo'),
    );

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(16),
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
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      color: AppColors.textPrimary,
                    ),
                  ),
                ),
                if (passType != null)
                  StatusBadge(label: passType, color: AppColors.info),
              ],
            ),
            const SizedBox(height: 10),
            // `propertyName` is resolved server-side (commit 5180e3a); when it is
            // null the row is gone, and no id is shown in its place — a raw UUID
            // tells a manager nothing they can act on.
            if (property != null)
              _DetailRow(icon: Icons.apartment_outlined, value: property),
            if (unit != null)
              _DetailRow(icon: Icons.home_outlined, value: 'Unit $unit'),
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
                      padding: const EdgeInsets.symmetric(vertical: 12),
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
                      padding: const EdgeInsets.symmetric(vertical: 12),
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
                fontSize: 13,
                color: AppColors.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
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
