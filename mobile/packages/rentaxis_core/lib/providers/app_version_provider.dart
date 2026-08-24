import 'dart:io' show Platform;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../api/services/app_version_service.dart';
import 'auth_provider.dart' show apiClientProvider;

/// The three outcomes the gate can reach.
///
/// - [ok]: proceed exactly as before. This is the *only* outcome for a null
///   response, an error, a timeout, or the inert seed — fail open is the point.
/// - [updateAvailable]: a newer build exists but the installed one is still
///   supported. Soft, dismissible nudge; the app stays fully usable.
/// - [updateRequired]: the installed build is below the supported floor. Hard
///   block; the user is pinned on the update screen until they update.
enum AppGateStatus { ok, updateAvailable, updateRequired }

/// The gate outcome plus the store link to send the user to. [storeUrl] is ''
/// whenever the distribution channel is undecided (the seed leaves it blank) —
/// the UI then shows no link and simply asks the user to update.
class AppGateDecision {
  const AppGateDecision(this.status, this.storeUrl);

  final AppGateStatus status;
  final String storeUrl;

  /// The fail-open answer: proceed, no link needed.
  static const ok = AppGateDecision(AppGateStatus.ok, '');

  bool get requiresUpdate => status == AppGateStatus.updateRequired;
  bool get updateAvailable => status == AppGateStatus.updateAvailable;
}

/// The entire gate rule, as one pure function so it can be exhaustively tested
/// without a network, a device build number, or a widget tree.
///
/// Comparison is on the integer BUILD number (`+N` in pubspec, monotonic), not
/// the semver name. Fail-open is baked in three ways: a null [info] (error,
/// timeout, non-200) is [AppGateStatus.ok]; a `minSupportedBuild` of 0 (the
/// seed) can never make `installedBuild < min` true, so it never blocks; and an
/// unparseable field defaulted to 0 upstream lands here as a permissive value.
AppGateDecision decideGate({
  required int installedBuild,
  required AppVersionInfo? info,
}) {
  if (info == null) return AppGateDecision.ok;
  if (installedBuild < info.minSupportedBuild) {
    return AppGateDecision(AppGateStatus.updateRequired, info.storeUrl);
  }
  if (installedBuild < info.latestBuild) {
    return AppGateDecision(AppGateStatus.updateAvailable, info.storeUrl);
  }
  return AppGateDecision.ok;
}

/// Shared [AppVersionService] over the app's Dio stack. The endpoint is public,
/// so this works before login.
final appVersionServiceProvider = Provider<AppVersionService>((ref) {
  final client = ref.watch(apiClientProvider);
  return AppVersionService(client.dio);
});

/// The running build number, parsed to int. Overridden in tests; on device it
/// reads `package_info_plus`. A build number that won't parse reads as 0, which
/// (being below any real floor of 0) still fails open.
final installedBuildProvider = FutureProvider<int>((ref) async {
  final info = await PackageInfo.fromPlatform();
  return int.tryParse(info.buildNumber) ?? 0;
});

/// The platform half of the contract key, derived from `dart:io`. Overridden in
/// tests. Anything that isn't iOS is reported as Android — the only two mobile
/// targets the apps ship to.
final currentPlatformProvider = Provider<String>((ref) {
  return Platform.isIOS ? AppPlatform.ios : AppPlatform.android;
});

/// The gate decision for a given app. Each app reads this with its own [AppId],
/// so nothing here is hardcoded to one app.
///
/// Every failure path collapses to [AppGateDecision.ok]: the service already
/// swallows its own errors into a null, and this extra guard means even a
/// throwing build-number read or platform lookup leaves the user free to
/// proceed. A version check must never be able to lock anyone out.
final appGateProvider =
    FutureProvider.family<AppGateDecision, AppId>((ref, appId) async {
  try {
    final build = await ref.watch(installedBuildProvider.future);
    final service = ref.watch(appVersionServiceProvider);
    final platform = ref.watch(currentPlatformProvider);
    final info = await service.fetch(app: appId, platform: platform);
    return decideGate(installedBuild: build, info: info);
  } catch (_) {
    return AppGateDecision.ok;
  }
});
