import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../api/api_client.dart';
import '../api/services/auth_service.dart';
import '../api/services/gate_pass_service.dart';
import '../api/services/listing_api_service.dart';
import '../api/services/location_service.dart';
import '../api/tenant_context.dart';
import '../models/auth_response.dart';

final apiClientProvider = Provider<ApiClient>((ref) => ApiClient());

final authServiceProvider = Provider<AuthService>((ref) {
  final client = ref.watch(apiClientProvider);
  return AuthService(client.dio);
});

/// Single shared instance of ListingApiService — use this everywhere instead
/// of declaring a local private provider per file.
final listingApiServiceProvider = Provider<ListingApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return ListingApiService(client.dio);
});

/// Single shared GatePassApiService — used by the renter, manager and guard apps.
final gatePassServiceProvider = Provider<GatePassApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return GatePassApiService(client.dio);
});

/// Single shared LocationService (geolocator wrapper).
final locationServiceProvider = Provider<LocationService>(
  (_) => LocationService(),
);

/// Resolves the tenant slug from [AuthState] using only the authoritative
/// `tenantId` field. Returns null if the tenant cannot be matched — callers
/// must handle the null case rather than falling back to the wrong tenant.
String? resolveTenantSlug(AuthState auth) {
  if (auth.tenants.isEmpty || auth.tenantId == null) return null;
  final matches = auth.tenants.where((t) => t['id'] == auth.tenantId);
  if (matches.isEmpty) return null;
  return matches.first['slug'] as String?;
}

class AuthState {
  final bool isAuthenticated;
  final bool isLoading;
  final String? userId;
  final String? email;
  final String? name;
  final String? role;
  final String? tenantId;
  final List<Map<String, dynamic>> tenants;
  final String? error;

  const AuthState({
    this.isAuthenticated = false,
    this.isLoading = true,
    this.userId,
    this.email,
    this.name,
    this.role,
    this.tenantId,
    this.tenants = const [],
    this.error,
  });

  AuthState copyWith({
    bool? isAuthenticated,
    bool? isLoading,
    String? userId,
    String? email,
    String? name,
    String? role,
    String? tenantId,
    List<Map<String, dynamic>>? tenants,
    String? error,
  }) {
    return AuthState(
      isAuthenticated: isAuthenticated ?? this.isAuthenticated,
      isLoading: isLoading ?? this.isLoading,
      userId: userId ?? this.userId,
      email: email ?? this.email,
      name: name ?? this.name,
      role: role ?? this.role,
      tenantId: tenantId ?? this.tenantId,
      tenants: tenants ?? this.tenants,
      error: error,
    );
  }
}

class AuthNotifier extends StateNotifier<AuthState> {
  final AuthService _authService;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  AuthNotifier(this._authService) : super(const AuthState()) {
    _init();
  }

  Future<void> _init() async {
    final userId = await _storage.read(key: 'userId');
    if (userId != null) {
      try {
        final profile = await _authService.getProfile();

        final tenants = await _authService.getTenants();

        final savedTenantId = await _storage.read(key: 'tenantId');

        TenantContext.currentTenantId = savedTenantId;

        state = AuthState(
          isAuthenticated: true,
          isLoading: false,
          userId: profile['id'],
          email: profile['email'],
          name: profile['name'],
          role: profile['role'],
          tenantId: savedTenantId ??
              (tenants.isNotEmpty ? tenants[0]['tenantId'] : null),
          tenants: List<Map<String, dynamic>>.from(tenants),
        );
      } catch (e) {
        await _clearStorage();
        state = const AuthState(isAuthenticated: false, isLoading: false);
      }
    } else {
      state = const AuthState(isAuthenticated: false, isLoading: false);
    }
  }

  Future<bool> login(String email, String password) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await _establishSession(await _authService.login(email, password));
      return true;
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: 'Invalid email or password',
      );
      return false;
    }
  }

  /// Phone-OTP login for security guards. The backend returns the same identity
  /// payload as [login], so the session is established identically — see
  /// [_establishSession].
  Future<bool> loginWithOtp(String phone, String code) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await _establishSession(await _authService.verifyOtp(phone, code));
      return true;
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: 'Invalid or expired code',
      );
      return false;
    }
  }

  /// Persists the identity every request is authenticated with and loads the
  /// tenant list. Shared by [login] and [loginWithOtp]: the identity written
  /// here is exactly what [AuthInterceptor] reads back into the `X-User-*`
  /// headers, so the two paths must not drift apart.
  Future<void> _establishSession(AuthResponse response) async {
    await _storage.write(key: 'userId', value: response.id);
    await _storage.write(key: 'userRole', value: response.role);

    final tenantId = response.tenantId;
    if (tenantId != null) {
      await _storage.write(key: 'tenantId', value: tenantId);
      await _storage.write(key: 'userTenantId', value: tenantId);
      TenantContext.currentTenantId = tenantId;
    }

    // Ordered after the writes above: this call is itself authenticated by the
    // headers they feed.
    final tenants = await _authService.getTenants();

    state = AuthState(
      isAuthenticated: true,
      isLoading: false,
      userId: response.id,
      email: response.email,
      name: response.name,
      role: response.role,
      tenantId: tenantId,
      tenants: List<Map<String, dynamic>>.from(tenants),
    );
  }

  Future<void> switchTenant(String tenantId) async {
    await _storage.write(key: 'tenantId', value: tenantId);
    TenantContext.currentTenantId = tenantId;
    state = state.copyWith(tenantId: tenantId);
  }

  Future<void> logout() async {
    await _clearStorage();
    TenantContext.currentTenantId = null;
    state = const AuthState(isAuthenticated: false, isLoading: false);
  }

  Future<void> _clearStorage() async {
    await _storage.delete(key: 'userId');
    await _storage.delete(key: 'userRole');
    await _storage.delete(key: 'tenantId');
    await _storage.delete(key: 'userTenantId');
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  final authService = ref.watch(authServiceProvider);
  return AuthNotifier(authService);
});
