import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../api/api_client.dart';
import '../api/services/auth_service.dart';
import '../api/services/facility_service.dart';
import '../api/services/promotion_service.dart';
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

/// Single shared FacilityApiService — amenities, parking spots and booking
/// requests. Used by the manager and renter apps.
final facilityServiceProvider = Provider<FacilityApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return FacilityApiService(client.dio);
});

/// Single shared PromotionApiService — the renter promotions carousel.
final promotionServiceProvider = Provider<PromotionApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PromotionApiService(client.dio);
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

  /// Non-empty only after a password login came back 409 (the email+password
  /// pair matches in several tenants). Each entry is the backend's
  /// `TenantCandidate` map — keys `tenantId` and `tenantName` — and the login
  /// screen should let the user pick one, then call
  /// [AuthNotifier.login] again with `tenantId` set to the chosen id.
  /// Transient like [error]: cleared by any state change that doesn't re-set it.
  final List<Map<String, dynamic>> tenantChoices;

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
    this.tenantChoices = const [],
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
    List<Map<String, dynamic>>? tenantChoices,
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
      tenantChoices: tenantChoices ?? const [],
    );
  }
}

class AuthNotifier extends StateNotifier<AuthState> {
  final AuthService _authService;
  // Android can retain an encrypted preferences blob after an app is rebuilt
  // with a different signing key (common on demo devices).  Let the storage
  // plugin reset that blob instead of leaving auth permanently stuck in the
  // loading state before the login screen can render.
  final FlutterSecureStorage _storage = const FlutterSecureStorage(
    aOptions: AndroidOptions(resetOnError: true),
  );

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

        // GET /auth/me/tenants returns TenantInfo{id, name, slug} — the key
        // is `id`, not `tenantId`. When nothing was persisted (e.g. the login
        // response carried no tenantId), fall back to the first membership and
        // persist it so subsequent restores agree with this session.
        String? tenantId = savedTenantId;
        if (tenantId == null && tenants.isNotEmpty) {
          tenantId = tenants[0]['id'] as String?;
          if (tenantId != null) {
            await _storage.write(key: 'tenantId', value: tenantId);
          }
        }

        TenantContext.currentTenantId = tenantId;

        state = AuthState(
          isAuthenticated: true,
          isLoading: false,
          userId: profile['id'],
          email: profile['email'],
          name: profile['name'],
          role: profile['role'],
          tenantId: tenantId,
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

  /// Password login. When the same email + password exists in several tenants
  /// the backend answers 409 with the candidate list instead of a session; the
  /// candidates land in [AuthState.tenantChoices] so the screen can show a
  /// picker and call this again with the chosen [tenantId].
  Future<bool> login(String email, String password, {String? tenantId}) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await _establishSession(
        await _authService.login(email, password, tenantId: tenantId),
      );
      return true;
    } on DioException catch (e) {
      if (e.response?.statusCode == 409) {
        state = state.copyWith(
          isLoading: false,
          error:
              'This account belongs to multiple organizations. Select one to continue.',
          tenantChoices: _parseTenantChoices(e.response?.data),
        );
        return false;
      }
      state = state.copyWith(
        isLoading: false,
        error: 'Invalid email or password',
      );
      return false;
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: 'Invalid email or password',
      );
      return false;
    }
  }

  /// Extracts the `tenants` list from the 409 LoginAmbiguousResponse body.
  static List<Map<String, dynamic>> _parseTenantChoices(dynamic data) {
    if (data is! Map) return const [];
    final raw = data['tenants'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((t) => Map<String, dynamic>.from(t))
        .toList();
  }

  /// Exchanges a Firebase Phone Authentication ID token for the guard's
  /// RentAxis session. The backend returns the same identity payload as [login],
  /// so both paths share [_establishSession].
  Future<bool> loginWithFirebase(String idToken) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      await _establishSession(await _authService.loginWithFirebase(idToken));
      return true;
    } catch (error) {
      state = state.copyWith(
        isLoading: false,
        error: _firebaseLoginError(error),
      );
      return false;
    }
  }

  /// Persists the identity every request is authenticated with and loads the
  /// tenant list. Shared by [login] and [loginWithFirebase]: the identity written
  /// here is exactly what [AuthInterceptor] reads back into the `X-User-*`
  /// headers, so the two paths must not drift apart.
  Future<void> _establishSession(AuthResponse response) async {
    await _storage.write(key: 'userId', value: response.id);
    await _storage.write(key: 'userRole', value: response.role);

    // The signed JWT shares the identity keys' lifecycle exactly: written
    // where identity is written, deleted where identity is deleted (see
    // [_clearStorage]). When the backend issued no token (old deploy), any
    // stale token from a previous session is removed so storage never pairs
    // this session's userId with another session's JWT.
    final token = response.token;
    if (token != null) {
      await _storage.write(key: 'authToken', value: token);
    } else {
      await _storage.delete(key: 'authToken');
    }

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
    await _storage.delete(key: 'authToken');
    await _storage.delete(key: 'userId');
    await _storage.delete(key: 'userRole');
    await _storage.delete(key: 'tenantId');
    await _storage.delete(key: 'userTenantId');
  }
}

String _firebaseLoginError(Object error) {
  if (error is! DioException) {
    return 'Could not complete login. Please try again.';
  }
  final status = error.response?.statusCode;
  if (status == 401) {
    return 'This phone is not assigned to an active security guard.';
  }
  if (status == 429) {
    return 'Too many login attempts. Please wait and try again.';
  }
  if (status == 503) {
    return 'Phone login is temporarily unavailable. Please contact support.';
  }
  if (error.type == DioExceptionType.connectionError ||
      error.type == DioExceptionType.connectionTimeout ||
      error.type == DioExceptionType.receiveTimeout ||
      error.type == DioExceptionType.sendTimeout) {
    return 'No connection. Check your network and try again.';
  }
  return 'Could not complete login. Please try again.';
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  final authService = ref.watch(authServiceProvider);
  return AuthNotifier(authService);
});
