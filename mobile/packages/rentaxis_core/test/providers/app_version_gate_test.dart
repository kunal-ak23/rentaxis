import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  group('decideGate (the pure rule)', () {
    test('null info fails open to ok (error / timeout / non-200)', () {
      expect(
        decideGate(installedBuild: 3, info: null).status,
        AppGateStatus.ok,
      );
    });

    test('the min-0 seed never gates: build == latest is ok', () {
      // Seed shape: min 0, latest = current build, url blank. Inert.
      final decision = decideGate(
        installedBuild: 3,
        info: const AppVersionInfo(
          minSupportedBuild: 0,
          latestBuild: 3,
          latestVersionName: '1.2.0',
          storeUrl: '',
        ),
      );
      expect(decision.status, AppGateStatus.ok);
    });

    test('the permissive fallback (all zeros) is ok', () {
      expect(
        decideGate(installedBuild: 3, info: AppVersionInfo.permissive).status,
        AppGateStatus.ok,
      );
    });

    test('build below the floor is updateRequired and carries the store url',
        () {
      final decision = decideGate(
        installedBuild: 2,
        info: const AppVersionInfo(
          minSupportedBuild: 3,
          latestBuild: 5,
          latestVersionName: '2.0.0',
          storeUrl: 'https://store/app',
        ),
      );
      expect(decision.status, AppGateStatus.updateRequired);
      expect(decision.storeUrl, 'https://store/app');
    });

    test('build exactly at the floor is NOT required (boundary)', () {
      // Pins `installedBuild < min` rather than `<=`.
      final decision = decideGate(
        installedBuild: 3,
        info: const AppVersionInfo(
          minSupportedBuild: 3,
          latestBuild: 3,
          latestVersionName: '2.0.0',
          storeUrl: '',
        ),
      );
      expect(decision.status, AppGateStatus.ok);
    });

    test('supported-but-behind build is updateAvailable', () {
      final decision = decideGate(
        installedBuild: 3,
        info: const AppVersionInfo(
          minSupportedBuild: 1,
          latestBuild: 5,
          latestVersionName: '2.0.0',
          storeUrl: 'https://store/app',
        ),
      );
      expect(decision.status, AppGateStatus.updateAvailable);
      expect(decision.storeUrl, 'https://store/app');
    });

    test('build at the latest is ok (boundary)', () {
      // Pins `installedBuild < latest` rather than `<=`.
      final decision = decideGate(
        installedBuild: 5,
        info: const AppVersionInfo(
          minSupportedBuild: 1,
          latestBuild: 5,
          latestVersionName: '2.0.0',
          storeUrl: '',
        ),
      );
      expect(decision.status, AppGateStatus.ok);
    });
  });

  group('appGateProvider (fail-open wiring)', () {
    Future<AppGateDecision> readGate({
      required int build,
      required AppVersionService service,
    }) async {
      final container = ProviderContainer(overrides: [
        installedBuildProvider.overrideWith((ref) async => build),
        currentPlatformProvider.overrideWithValue(AppPlatform.android),
        appVersionServiceProvider.overrideWithValue(service),
      ]);
      addTearDown(container.dispose);
      return container.read(appGateProvider(AppId.renter).future);
    }

    test('service returning null (its throw/timeout answer) => gate ok',
        () async {
      final decision = await readGate(
        build: 1,
        service: _StubVersionService(null),
      );
      expect(decision.status, AppGateStatus.ok);
    });

    test('a service that throws anyway => gate still ok (outer guard)',
        () async {
      final decision = await readGate(
        build: 1,
        service: _ThrowingVersionService(),
      );
      expect(decision.status, AppGateStatus.ok);
    });

    test('floor above the installed build => updateRequired', () async {
      final decision = await readGate(
        build: 2,
        service: _StubVersionService(const AppVersionInfo(
          minSupportedBuild: 3,
          latestBuild: 4,
          latestVersionName: '2.0.0',
          storeUrl: 'https://store/app',
        )),
      );
      expect(decision.status, AppGateStatus.updateRequired);
      expect(decision.storeUrl, 'https://store/app');
    });

    test('newer build available but supported => updateAvailable', () async {
      final decision = await readGate(
        build: 3,
        service: _StubVersionService(const AppVersionInfo(
          minSupportedBuild: 1,
          latestBuild: 5,
          latestVersionName: '2.0.0',
          storeUrl: '',
        )),
      );
      expect(decision.status, AppGateStatus.updateAvailable);
    });
  });
}

class _StubVersionService implements AppVersionService {
  _StubVersionService(this._result);
  final AppVersionInfo? _result;

  @override
  Future<AppVersionInfo?> fetch({
    required AppId app,
    required String platform,
  }) async =>
      _result;
}

class _ThrowingVersionService implements AppVersionService {
  @override
  Future<AppVersionInfo?> fetch({
    required AppId app,
    required String platform,
  }) async =>
      throw StateError('boom');
}
