import 'package:rentaxis_core/rentaxis_core.dart';

/// A stand-in [AppVersionService] for router/gate tests: returns a fixed row
/// (or null to simulate an error/timeout) without a Dio stack. Mirrors
/// `fake_promotion_service.dart`.
class FakeAppVersionService implements AppVersionService {
  FakeAppVersionService({this.info});

  /// The row to answer with. `null` is exactly what the real service returns on
  /// an error, a timeout or a non-200 — i.e. the fail-open input.
  AppVersionInfo? info;

  int calls = 0;
  final List<AppId> requestedApps = [];
  final List<String> requestedPlatforms = [];

  @override
  Future<AppVersionInfo?> fetch({
    required AppId app,
    required String platform,
  }) async {
    calls++;
    requestedApps.add(app);
    requestedPlatforms.add(platform);
    return info;
  }
}
