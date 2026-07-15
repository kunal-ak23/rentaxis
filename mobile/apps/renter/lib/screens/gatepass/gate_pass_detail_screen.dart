import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/pass_display.dart';
import '../../providers/gate_pass_provider.dart';

/// One pass, as the guest's screen at the gate.
///
/// The pass is fetched by id rather than handed over from the list. That keeps a
/// deep link working, keeps the status honest when the list has gone stale, and
/// avoids GoRouter `extra` — which this app's router would silently drop on any
/// auth-state change, because it rebuilds on `authProvider`.
class GatePassDetailScreen extends ConsumerWidget {
  final String passId;
  const GatePassDetailScreen({super.key, required this.passId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pass = ref.watch(passByIdProvider(passId));

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        elevation: 0,
        title: const Text('Gate Pass'),
      ),
      body: pass.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ErrorState(
          message: 'Could not load this pass.',
          onRetry: () => ref.invalidate(passByIdProvider(passId)),
        ),
        data: (row) => _PassBody(pass: row, passId: passId),
      ),
    );
  }
}

class _PassBody extends ConsumerStatefulWidget {
  final Map<String, dynamic> pass;
  final String passId;
  const _PassBody({required this.pass, required this.passId});

  @override
  ConsumerState<_PassBody> createState() => _PassBodyState();
}

class _PassBodyState extends ConsumerState<_PassBody> {
  bool _cancelling = false;

  @override
  Widget build(BuildContext context) {
    // Watched, not read in [_share], so the names are already in hand when the
    // renter taps Share. `ref.read` on an unwatched FutureProvider only starts
    // the fetch and hands back a loading state — the first share would then be
    // the one message that went out without an address.
    ref.watch(activeLeasesProvider);

    final pass = widget.pass;
    final status = passString(pass, 'status');
    final qrToken = passString(pass, 'qrToken');
    final code = passString(pass, 'numericCode');
    final guest = passString(pass, 'guestName');

    return LoadingOverlay(
      isLoading: _cancelling,
      child: ListView(
        padding: EdgeInsets.fromLTRB(16, 16, 16, AppInsets.bottomNav(context, spacing: 32)),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  guest ?? 'Guest',
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w700,
                    color: AppColors.navyDark,
                  ),
                ),
              ),
              StatusBadge(
                label: gatePassStatusLabel(status),
                color: gatePassStatusColor(status),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            passString(pass, 'passType') == 'RECURRING'
                ? 'Recurring pass'
                : 'Single visit',
            style: const TextStyle(fontSize: 13, color: AppColors.textMuted),
          ),
          if (status == 'PENDING_APPROVAL') ...[
            const SizedBox(height: 14),
            const _PendingNotice(),
          ],
          const SizedBox(height: 18),
          _QrCard(qrToken: qrToken, dimmed: status != 'ACTIVE'),
          const SizedBox(height: 16),
          if (code != null) _CodeCard(code: code),
          const SizedBox(height: 16),
          _DetailCard(pass: pass),
          const SizedBox(height: 20),
          if (qrToken != null || code != null)
            SizedBox(
              height: 50,
              child: OutlinedButton.icon(
                onPressed: _share,
                icon: const Icon(Icons.ios_share),
                label: const Text('Share with guest'),
              ),
            ),
          if (canCancel(status)) ...[
            const SizedBox(height: 10),
            SizedBox(
              height: 50,
              child: TextButton.icon(
                onPressed: _cancelling ? null : _confirmCancel,
                icon: const Icon(Icons.block, color: AppColors.danger),
                label: const Text('Cancel pass',
                    style: TextStyle(color: AppColors.danger)),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _share() async {
    // The pass itself carries no property or unit *name* — only ids — so the
    // place is resolved off the renter's own leases. A pass whose lease has
    // since ended resolves to nulls and the message simply omits the address
    // rather than failing to send.
    final place = resolveUnitLabel(
      ref.read(activeLeasesProvider).valueOrNull ?? const [],
      passString(widget.pass, 'unitId'),
    );
    final text = buildShareText(
      pass: widget.pass,
      propertyName: place.propertyName,
      unitIdentifier: place.unitIdentifier,
    );
    await ref.read(shareTextProvider)(text);
  }

  Future<void> _confirmCancel() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Cancel this pass?'),
        content: Text(
          'This cannot be undone. '
          '${passString(widget.pass, 'guestName') ?? 'Your guest'} will not be '
          'let in with it, even if you have already shared it.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Keep pass'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Cancel pass',
                style: TextStyle(color: AppColors.danger)),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _cancelling = true);
    try {
      await ref.read(gatePassServiceProvider).cancel(widget.passId);
      ref.invalidate(myPassesProvider);
      ref.invalidate(passByIdProvider(widget.passId));
      if (!mounted) return;
      setState(() => _cancelling = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Pass cancelled.')),
      );
    } catch (_) {
      if (!mounted) return;
      setState(() => _cancelling = false);
      // The likely 400 here is a pass used between load and tap. Re-reading is
      // the fix and the explanation at once: the status chip will say USED.
      ref.invalidate(passByIdProvider(widget.passId));
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Could not cancel this pass — it may have just been '
              'used. Pull to check its status.'),
        ),
      );
    }
  }
}

class _PendingNotice extends StatelessWidget {
  const _PendingNotice();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.warningLight,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.warning.withValues(alpha: 0.3)),
      ),
      child: const Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.schedule_outlined, size: 18, color: AppColors.warning),
          SizedBox(width: 10),
          Expanded(
            child: Text(
              'Waiting for your manager to approve this pass. It will not open '
              'the gate until they do.',
              style: TextStyle(
                fontSize: 12.5,
                height: 1.35,
                color: AppColors.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The QR, sized and coloured to be scanned off a phone screen.
///
/// Deliberately plain black-on-white inside a white card with padding: a guard
/// scans this off a stranger's screen, at an angle, possibly in sun. The white
/// margin is the quiet zone the symbol needs to be found at all, and tinting the
/// modules navy to match the app would cost contrast the scanner needs.
///
/// A pass that is not ACTIVE dims the symbol rather than hiding it — the renter
/// should still see there *is* a pass, but should not walk to a gate believing a
/// spent code will scan.
class _QrCard extends StatelessWidget {
  final String? qrToken;
  final bool dimmed;
  const _QrCard({required this.qrToken, required this.dimmed});

  @override
  Widget build(BuildContext context) {
    final token = qrToken;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        children: [
          if (token == null)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 40),
              child: Text(
                'This pass has no QR code.',
                style: TextStyle(color: AppColors.textMuted),
              ),
            )
          else
            Opacity(
              opacity: dimmed ? 0.35 : 1,
              child: QrImageView(
                // Keyed by the token so the symbol's identity follows the code
                // it encodes rather than its position — and so a test can prove
                // which field was encoded, since [QrImageView] keeps `data`
                // private. The key never reaches the screen or the a11y tree.
                key: ValueKey(token),
                data: token,
                version: QrVersions.auto,
                size: 240,
                backgroundColor: Colors.white,
                eyeStyle: const QrEyeStyle(
                  eyeShape: QrEyeShape.square,
                  color: Colors.black,
                ),
                dataModuleStyle: const QrDataModuleStyle(
                  dataModuleShape: QrDataModuleShape.square,
                  color: Colors.black,
                ),
              ),
            ),
          const SizedBox(height: 12),
          Text(
            dimmed
                ? 'This pass will not open the gate.'
                : 'Show this at the gate',
            style: TextStyle(
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
              color: dimmed ? AppColors.textMuted : AppColors.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

/// The numeric code — the fallback when the QR will not scan.
///
/// Monospace and large because it is read aloud across a gate and keyed in by
/// someone else. The copy button exists so the renter can paste it into whatever
/// they actually message their guest with.
class _CodeCard extends StatelessWidget {
  final String code;
  const _CodeCard({required this.code});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'ENTRY CODE',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1,
                    color: AppColors.textMuted,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  formatNumericCode(code),
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 26,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 2,
                    color: AppColors.navyDark,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: 'Copy code',
            icon: const Icon(Icons.copy_rounded, color: AppColors.textSecondary),
            onPressed: () async {
              // The grouping in the label is for reading aloud; what gets copied
              // is the code the guard actually keys in.
              await Clipboard.setData(ClipboardData(text: code));
              if (!context.mounted) return;
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Entry code copied.')),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _DetailCard extends StatelessWidget {
  final Map<String, dynamic> pass;
  const _DetailCard({required this.pass});

  @override
  Widget build(BuildContext context) {
    final rows = <({String label, String value})>[
      (
        label: 'Valid',
        value: formatWindow(
          passInstant(pass, 'validFrom'),
          passInstant(pass, 'validTo'),
        )
      ),
      if (passString(pass, 'guestPhone') != null)
        (label: 'Phone', value: passString(pass, 'guestPhone')!),
      if (passString(pass, 'purpose') != null)
        (label: 'Purpose', value: passString(pass, 'purpose')!),
      if (passString(pass, 'vehicleNumber') != null)
        (label: 'Vehicle', value: passString(pass, 'vehicleNumber')!),
    ];

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        children: [
          for (final row in rows)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 78,
                    child: Text(
                      row.label,
                      style: const TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: AppColors.textMuted,
                      ),
                    ),
                  ),
                  Expanded(
                    child: Text(
                      row.value,
                      style: const TextStyle(
                        fontSize: 13.5,
                        color: AppColors.navyDark,
                      ),
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
