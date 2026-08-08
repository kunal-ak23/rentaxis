import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../gatepass/pass_display.dart';
import '../../providers/gate_pass_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تصريح الدخول' : 'Gate Pass';
  String get loadFailed => ar ? 'تعذر تحميل هذا التصريح.' : 'Could not load this pass.';
  String get guestFallback => ar ? 'ضيف' : 'Guest';
  String get recurringPass => ar ? 'تصريح متكرر' : 'Recurring pass';
  String get singleVisit => ar ? 'زيارة واحدة' : 'Single visit';
  String get pendingNotice => ar
      ? 'بانتظار موافقة مدير العقار على هذا التصريح. لن يفتح البوابة حتى '
            'تتم الموافقة.'
      : 'Waiting for your manager to approve this pass. It will not open '
            'the gate until they do.';
  String get noQrCode => ar ? 'لا يحتوي هذا التصريح على رمز QR.' : 'This pass has no QR code.';
  String get wontOpenGate => ar ? 'لن يفتح هذا التصريح البوابة.' : 'This pass will not open the gate.';
  String get showAtGate => ar ? 'أظهر هذا عند البوابة' : 'Show this at the gate';
  String get entryCode => ar ? 'رمز الدخول' : 'ENTRY CODE';
  String get copyCode => ar ? 'نسخ الرمز' : 'Copy code';
  String get codeCopied => ar ? 'تم نسخ رمز الدخول.' : 'Entry code copied.';
  String get shareWithGuest => ar ? 'مشاركة مع الضيف' : 'Share with guest';
  String get cancelPass => ar ? 'إلغاء التصريح' : 'Cancel pass';
  String get keepPass => ar ? 'الاحتفاظ بالتصريح' : 'Keep pass';
  String get cancelTitle => ar ? 'إلغاء هذا التصريح؟' : 'Cancel this pass?';
  String cancelConfirm(String guest) => ar
      ? 'لا يمكن التراجع عن هذا. لن يُسمح لـ $guest بالدخول به، حتى لو '
            'كنت قد شاركته بالفعل.'
      : 'This cannot be undone. $guest will not be '
            'let in with it, even if you have already shared it.';
  String get yourGuest => ar ? 'ضيفك' : 'Your guest';
  String get passCancelled => ar ? 'تم إلغاء التصريح.' : 'Pass cancelled.';
  String get cancelFailed => ar
      ? 'تعذر إلغاء هذا التصريح — قد يكون قد استُخدم للتو. اسحب للأسفل '
            'للتحقق من حالته.'
      : 'Could not cancel this pass — it may have just been '
            'used. Pull to check its status.';
  String get valid => ar ? 'الصلاحية' : 'Valid';
  String get phone => ar ? 'الهاتف' : 'Phone';
  String get purpose => ar ? 'الغرض' : 'Purpose';
  String get vehicle => ar ? 'المركبة' : 'Vehicle';
}

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
    final m = context.miftah;
    final l = _L(context.isAr);
    final pass = ref.watch(passByIdProvider(passId));

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        backgroundColor: m.surface,
        foregroundColor: m.textPrimary,
        elevation: 0,
        title: Text(l.title),
      ),
      body: pass.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => ErrorState(
          message: l.loadFailed,
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
    final m = context.miftah;
    final l = _L(context.isAr);

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
      child: RefreshIndicator(
        color: m.isDark ? AppColors.accent : AppColors.primary,
        onRefresh: () => ref.refresh(passByIdProvider(widget.passId).future),
        child: ListView(
          padding: EdgeInsets.fromLTRB(
            16,
            16,
            16,
            AppInsets.bottomNav(context, spacing: 32),
          ),
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    guest ?? l.guestFallback,
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w700,
                      color: m.textPrimary,
                    ),
                  ),
                ),
                StatusBadge(
                  label: gatePassStatusLabel(status, ar: l.ar),
                  color: gatePassStatusColor(status),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              passString(pass, 'passType') == 'RECURRING'
                  ? l.recurringPass
                  : l.singleVisit,
              style: TextStyle(fontSize: 13, color: m.textMuted),
            ),
            if (status == 'PENDING_APPROVAL') ...[
              const SizedBox(height: 14),
              _PendingNotice(l: l),
            ],
            const SizedBox(height: 18),
            _QrCard(qrToken: qrToken, dimmed: status != 'ACTIVE', l: l),
            const SizedBox(height: 16),
            if (code != null) _CodeCard(code: code, l: l),
            const SizedBox(height: 16),
            _DetailCard(pass: pass, l: l),
            const SizedBox(height: 20),
            if (qrToken != null || code != null)
              SizedBox(
                height: 50,
                child: OutlinedButton.icon(
                  onPressed: _share,
                  icon: const Icon(Icons.ios_share),
                  label: Text(l.shareWithGuest),
                ),
              ),
            if (canCancel(status)) ...[
              const SizedBox(height: 10),
              SizedBox(
                height: 50,
                child: TextButton.icon(
                  onPressed: _cancelling ? null : _confirmCancel,
                  icon: Icon(Icons.block, color: m.danger),
                  label: Text(
                    l.cancelPass,
                    style: TextStyle(color: m.danger),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _share() async {
    final l = _L(context.isAr);
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
      ar: l.ar,
    );
    await ref.read(shareTextProvider)(text);
  }

  Future<void> _confirmCancel() async {
    final l = _L(context.isAr);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.cancelTitle),
        content: Text(
          l.cancelConfirm(passString(widget.pass, 'guestName') ?? l.yourGuest),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l.keepPass),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(
              l.cancelPass,
              style: TextStyle(color: context.miftah.danger),
            ),
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
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(l.passCancelled)));
    } catch (_) {
      if (!mounted) return;
      setState(() => _cancelling = false);
      // The likely 400 here is a pass used between load and tap. Re-reading is
      // the fix and the explanation at once: the status chip will say USED.
      ref.invalidate(passByIdProvider(widget.passId));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(l.cancelFailed)),
      );
    }
  }
}

class _PendingNotice extends StatelessWidget {
  final _L l;
  const _PendingNotice({required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: m.warningBg,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.warning.withValues(alpha: 0.3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.schedule_outlined, size: 18, color: m.warning),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              l.pendingNotice,
              style: TextStyle(
                fontSize: 12.5,
                height: 1.35,
                color: m.textSecondary,
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
///
/// This card is deliberately fixed white-on-black regardless of theme mode —
/// the symbol needs to stay scannable, so its colours do not follow dark mode.
class _QrCard extends StatelessWidget {
  final String? qrToken;
  final bool dimmed;
  final _L l;
  const _QrCard({required this.qrToken, required this.dimmed, required this.l});

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
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 40),
              child: Text(
                l.noQrCode,
                style: const TextStyle(color: AppColors.textMuted),
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
            dimmed ? l.wontOpenGate : l.showAtGate,
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
  final _L l;
  const _CodeCard({required this.code, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.entryCode,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    letterSpacing: l.ar ? 0 : 1,
                    color: m.textMuted,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  formatNumericCode(code),
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 26,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 2,
                    color: m.textPrimary,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: l.copyCode,
            icon: Icon(Icons.copy_rounded, color: m.textSecondary),
            onPressed: () async {
              // The grouping in the label is for reading aloud; what gets copied
              // is the code the guard actually keys in.
              await Clipboard.setData(ClipboardData(text: code));
              if (!context.mounted) return;
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text(l.codeCopied)),
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
  final _L l;
  const _DetailCard({required this.pass, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final rows = <({String label, String value})>[
      (
        label: l.valid,
        value: formatWindow(
          passInstant(pass, 'validFrom'),
          passInstant(pass, 'validTo'),
          ar: l.ar,
        ),
      ),
      if (passString(pass, 'guestPhone') != null)
        (label: l.phone, value: passString(pass, 'guestPhone')!),
      if (passString(pass, 'purpose') != null)
        (label: l.purpose, value: passString(pass, 'purpose')!),
      if (passString(pass, 'vehicleNumber') != null)
        (label: l.vehicle, value: passString(pass, 'vehicleNumber')!),
    ];

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
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
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: m.textMuted,
                      ),
                    ),
                  ),
                  Expanded(
                    child: Text(
                      row.value,
                      style: TextStyle(fontSize: 13.5, color: m.textPrimary),
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
