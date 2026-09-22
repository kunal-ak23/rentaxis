import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/services/tenant_feature_service.dart';
import 'auth_provider.dart';

/// The one flag name the apps read today. Kept as a constant so the string is
/// spelled once and matches the backend's `TenantFeature` enum constant.
const String kMobileFinanceFeature = 'MOBILE_FINANCE';

final tenantFeatureServiceProvider = Provider<TenantFeatureService>(
  (ref) => TenantFeatureService(ref.watch(apiClientProvider).dio),
);

/// Per-tenant flags, or an empty map if they cannot be read.
///
/// Unlike the app-version gate, this one **fails closed**: an unreachable
/// backend is exactly when a screen that depends on it would throw, so an
/// unknown flag is treated as off. That matches the web's useTenantFeatures.
///
/// The session identity is watched purely as a refresh key: the first read can
/// happen before login (401 -> empty map), and without this the cached empty
/// answer would outlive the sign-in that makes the endpoint answerable.
final tenantFeaturesProvider = FutureProvider<Map<String, bool>>((ref) async {
  ref.watch(authProvider.select((auth) => (auth.isAuthenticated, auth.tenantId)));
  try {
    return await ref.watch(tenantFeatureServiceProvider).getFeatures();
  } catch (_) {
    return const <String, bool>{};
  }
});

/// Whether this tenant's mobile finance, lease and cheque screens are available.
///
/// Off for every tenant until the apps are rewritten for accounting v2 (spec
/// D7). While the flags are still loading this reads false, so a screen is
/// never shown and then snatched away — and an absent key reads as the flag's
/// default, which is off.
final mobileFinanceEnabledProvider = Provider<bool>((ref) {
  return ref.watch(tenantFeaturesProvider).maybeWhen(
        data: (flags) => flags[kMobileFinanceFeature] == true,
        orElse: () => false,
      );
});
