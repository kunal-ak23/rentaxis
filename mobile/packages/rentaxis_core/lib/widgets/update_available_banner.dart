import 'package:flutter/material.dart';

import '../ui/miftah_tokens.dart';
import '../utils/l10n.dart';
import 'update_required_screen.dart' show UpdateUrlLauncher, launchUpdateUrl;

/// The soft nudge. Shown when a newer build exists but the installed one is
/// still supported — so it is dismissible and never blocks the app.
///
/// This is just the content; [showUpdateAvailableBanner] is what surfaces it
/// (once per launch) over whatever screen the app landed on. Kept a plain
/// widget so it can be pumped and asserted on directly.
class UpdateAvailableBanner extends StatelessWidget {
  const UpdateAvailableBanner({
    super.key,
    this.storeUrl = '',
    required this.onDismiss,
    UpdateUrlLauncher? launcher,
  }) : _launch = launcher ?? launchUpdateUrl;

  final String storeUrl;
  final VoidCallback onDismiss;
  final UpdateUrlLauncher _launch;

  bool get _hasLink => storeUrl.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Material(
      color: Colors.transparent,
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        decoration: BoxDecoration(
          color: MiftahColors.ink,
          borderRadius: BorderRadius.circular(MiftahRadii.tile),
        ),
        child: Row(
          children: [
            const Icon(
              Icons.system_update_rounded,
              color: MiftahColors.brassLight,
              size: 22,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                ar
                    ? 'يتوفر إصدار جديد من التطبيق.'
                    : 'A new version of the app is available.',
                style: (ar ? MiftahType.ar(size: 13.5) : MiftahType.body(size: 13))
                    .copyWith(color: Colors.white),
              ),
            ),
            if (_hasLink)
              TextButton(
                onPressed: () {
                  _launch(storeUrl);
                  onDismiss();
                },
                style: TextButton.styleFrom(
                  foregroundColor: MiftahColors.brassLight,
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                ),
                child: Text(
                  ar ? 'تحديث' : 'Update',
                  style: MiftahType.button(size: 13.5)
                      .copyWith(color: MiftahColors.brassLight),
                ),
              ),
            IconButton(
              onPressed: onDismiss,
              icon: const Icon(Icons.close_rounded, size: 20),
              color: Colors.white70,
              tooltip: ar ? 'إغلاق' : 'Dismiss',
            ),
          ],
        ),
      ),
    );
  }
}

/// Surfaces [UpdateAvailableBanner] once, over whatever screen is showing.
///
/// Call it with a [ScaffoldMessengerState] captured *before* navigating away
/// from the splash (the app's root messenger survives the route change). It is
/// fire-and-forget and swallows failures — a soft nudge must never disrupt the
/// app, so if there is nothing to show it against it simply does nothing.
void showUpdateAvailableBanner(
  ScaffoldMessengerState messenger, {
  required String storeUrl,
  UpdateUrlLauncher? launcher,
}) {
  messenger.showSnackBar(
    SnackBar(
      backgroundColor: Colors.transparent,
      elevation: 0,
      behavior: SnackBarBehavior.floating,
      duration: const Duration(seconds: 8),
      padding: EdgeInsets.zero,
      content: UpdateAvailableBanner(
        storeUrl: storeUrl,
        launcher: launcher,
        onDismiss: messenger.hideCurrentSnackBar,
      ),
    ),
  );
}
