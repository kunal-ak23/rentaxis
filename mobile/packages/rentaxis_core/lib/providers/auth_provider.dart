import 'dart:developer' as dev;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../api/api_client.dart';
import '../api/services/auth_service.dart';
import '../api/tenant_context.dart';

final apiClientProvider = Provider<ApiClient>((ref) => ApiClient());

final authServiceProvider = Provider<AuthService>((ref) {
  final client = ref.watch(apiClientProvider);
  return AuthService(client.dio);
});

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
    dev.log('[AUTH_DEBUG] _init() started', name: 'AuthProvider');
    final userId = await _storage.read(key: 'userId');
    dev.log('[AUTH_DEBUG] stored userId: $userId', name: 'AuthProvider');
    if (userId != null) {
      try {
        dev.log('[AUTH_DEBUG] fetching profile...', name: 'AuthProvider');
        final profile = await _authService.getProfile();
        dev.log('[AUTH_DEBUG] profile response: $profile', name: 'AuthProvider');

        dev.log('[AUTH_DEBUG] fetching tenants...', name: 'AuthProvider');
        final tenants = await _authService.getTenants();
        dev.log('[AUTH_DEBUG] tenants response: $tenants', name: 'AuthProvider');

        final savedTenantId = await _storage.read(key: 'tenantId');
        dev.log('[AUTH_DEBUG] stored tenantId: $savedTenantId', name: 'AuthProvider');

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
        dev.log('[AUTH_DEBUG] _init() SUCCESS - authenticated', name: 'AuthProvider');
      } catch (e, stack) {
        dev.log('[AUTH_DEBUG] _init() FAILED: $e', name: 'AuthProvider', error: e, stackTrace: stack);
        await _clearStorage();
        state = const AuthState(isAuthenticated: false, isLoading: false);
      }
    } else {
      dev.log('[AUTH_DEBUG] no stored userId - unauthenticated', name: 'AuthProvider');
      state = const AuthState(isAuthenticated: false, isLoading: false);
    }
  }

  Future<bool> login(String email, String password) async {
    dev.log('[AUTH_DEBUG] login() called for: $email', name: 'AuthProvider');
    state = state.copyWith(isLoading: true, error: null);
    try {
      final response = await _authService.login(email, password);
      dev.log('[AUTH_DEBUG] login response: id=${response.id}, role=${response.role}, tenantId=${response.tenantId}', name: 'AuthProvider');

      await _storage.write(key: 'userId', value: response.id);
      await _storage.write(key: 'userRole', value: response.role);

      final tenantId = response.tenantId;
      if (tenantId != null) {
        await _storage.write(key: 'tenantId', value: tenantId);
        await _storage.write(key: 'userTenantId', value: tenantId);
        TenantContext.currentTenantId = tenantId;
      }
      dev.log('[AUTH_DEBUG] TenantContext.currentTenantId = ${TenantContext.currentTenantId}', name: 'AuthProvider');

      final tenants = await _authService.getTenants();
      dev.log('[AUTH_DEBUG] tenants after login: $tenants', name: 'AuthProvider');

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
      dev.log('[AUTH_DEBUG] login() SUCCESS', name: 'AuthProvider');
      return true;
    } catch (e, stack) {
      dev.log('[AUTH_DEBUG] login() FAILED: $e', name: 'AuthProvider', error: e, stackTrace: stack);
      state = state.copyWith(
        isLoading: false,
        error: 'Invalid email or password',
      );
      return false;
    }
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
