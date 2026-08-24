import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../ui/miftah_tokens.dart';
import '../ui/miftah_widgets.dart';
import '../utils/l10n.dart';

/// Opens a store/update URL. Injectable so widget tests can assert the button
/// tried to open the right link without touching a platform channel.
typedef UpdateUrlLauncher = Future<void> Function(String url);

/// Default launcher: opens the URL in an external app if the platform can.
/// Silently does nothing when the URL can't be handled — a dead link must not
/// throw out of a button press.
Future<void> launchUpdateUrl(String url) async {
  final uri = Uri.tryParse(url);
  if (uri == null) return;
  if (await canLaunchUrl(uri)) {
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }
}

/// The hard block. Shown when the installed build is below the supported floor.
///
/// It is deliberately a dead end: [PopScope] with `canPop: false` swallows the
/// system back gesture, there is no app bar and no skip, and the app's router
/// pins any navigation attempt back here. The only way forward is to update.
///
/// The update button is hidden entirely when [storeUrl] is empty — the seed
/// leaves the distribution channel undecided, so in that state the screen just
/// tells the user a new version is required without offering a broken link.
class UpdateRequiredScreen extends StatelessWidget {
  const UpdateRequiredScreen({
    super.key,
    this.storeUrl = '',
    UpdateUrlLauncher? launcher,
  }) : _launch = launcher ?? launchUpdateUrl;

  final String storeUrl;
  final UpdateUrlLauncher _launch;

  bool get _hasLink => storeUrl.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return PopScope(
      canPop: false,
      child: Scaffold(
        backgroundColor: MiftahColors.darkCanvas,
        body: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      gradient: MiftahGradients.gold,
                      borderRadius: BorderRadius.circular(MiftahRadii.tile),
                      boxShadow: MiftahShadows.gold,
                    ),
                    child: const Icon(
                      Icons.system_update_rounded,
                      color: MiftahColors.ink,
                      size: 34,
                    ),
                  ),
                  const SizedBox(height: 28),
                  Text(
                    ar ? 'التحديث مطلوب' : 'Update required',
                    textAlign: TextAlign.center,
                    style: ar
                        ? MiftahType.ar(
                            size: 24,
                            weight: FontWeight.w800,
                            color: MiftahColors.darkTextPrimary,
                          )
                        : MiftahType.title(
                            color: MiftahColors.darkTextPrimary,
                          ).copyWith(fontSize: 24),
                  ),
                  const SizedBox(height: 14),
                  Text(
                    ar
                        ? 'إصدارك من التطبيق لم يعد مدعومًا. يرجى التحديث إلى أحدث إصدار للمتابعة.'
                        : 'Your version of the app is no longer supported. '
                            'Please update to the latest version to continue.',
                    textAlign: TextAlign.center,
                    style: (ar
                            ? MiftahType.ar(size: 15)
                            : MiftahType.body(size: 14.5))
                        .copyWith(color: MiftahColors.darkTextMuted),
                  ),
                  if (_hasLink) ...[
                    const SizedBox(height: 32),
                    MiftahGoldButton(
                      label: ar ? 'تحديث الآن' : 'Update now',
                      icon: const Icon(
                        Icons.open_in_new_rounded,
                        size: 18,
                        color: MiftahColors.ink,
                      ),
                      onPressed: () => _launch(storeUrl),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
