import 'package:rentaxis_core/rentaxis_core.dart';

/// A stand-in [AppVersionService] for the harness: answers instantly with a
/// fixed row (or null to model an error/timeout) so booting the real app never
/// reaches out to the network at splash. Mirrors `fake_auth_service.dart`.
class FakeAppVersionService implements AppVersionService {
  FakeAppVersionService({this.info});

  /// The row to answer with. `null` is what the real service returns on an
  /// error/timeout — the fail-open input, and the default here so the gate
  /// stays inert for the auth tests that don't care about it.
  AppVersionInfo? info;

  @override
  Future<AppVersionInfo?> fetch({
    required AppId app,
    required String platform,
  }) async =>
      info;
}
