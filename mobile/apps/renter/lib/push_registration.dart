import 'dart:async';
import 'dart:io';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Registers this device with RentAxis only while a resident is signed in.
///
/// Firebase issues the token, including replacements after an app restore or
/// OS rotation. The backend owns the delivery fan-out, so the app deliberately
/// sends no user or tenant identifiers in this request: those come from the
/// authenticated API headers.
class PushRegistration extends ConsumerStatefulWidget {
  const PushRegistration({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<PushRegistration> createState() => _PushRegistrationState();
}

class _PushRegistrationState extends ConsumerState<PushRegistration> {
  StreamSubscription<String>? _tokenRefreshSubscription;
  String? _registeredUserId;
  String? _registeredToken;

  @override
  void initState() {
    super.initState();
    if (!_firebaseReady) return;
    // Widget tests and desktop previews do not initialise a native Firebase
    // app. Keep those surfaces usable; a real Android/iOS session retries
    // registration as soon as Firebase is available.
    try {
      _tokenRefreshSubscription = FirebaseMessaging.instance.onTokenRefresh
          .listen((token) => _register(token));
    } catch (_) {}
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncForCurrentUser());
  }

  /// Firebase is started in main() on Android and iOS; anywhere it was not
  /// (tests, desktop previews, a build without its config file) push is off.
  bool get _firebaseReady {
    if (!Platform.isAndroid && !Platform.isIOS) return false;
    try {
      return Firebase.apps.isNotEmpty;
    } catch (_) {
      return false; // no native Firebase at all (widget tests)
    }
  }

  Future<void> _syncForCurrentUser() async {
    if (!_firebaseReady) return;
    try {
      final auth = ref.read(authProvider);
      if (!auth.isAuthenticated || auth.userId == null) {
        _registeredUserId = null;
        _registeredToken = null;
        return;
      }

      // iOS and Android 13+ require explicit user consent. Android versions
      // before 13 return granted without presenting a prompt.
      final permission = await FirebaseMessaging.instance.requestPermission();
      if (permission.authorizationStatus != AuthorizationStatus.authorized &&
          permission.authorizationStatus != AuthorizationStatus.provisional) {
        return;
      }

      // On iOS the FCM token is minted from the APNs token, which arrives a
      // moment after the permission is granted; asking first fails.
      if (Platform.isIOS && !await _apnsTokenReady()) return;
      final token = await FirebaseMessaging.instance.getToken();
      if (token != null && token.isNotEmpty) await _register(token);
    } catch (_) {
      // Native Firebase is intentionally absent from widget tests/previews.
    }
  }

  Future<bool> _apnsTokenReady() async {
    for (var attempt = 0; attempt < 10; attempt++) {
      if (await FirebaseMessaging.instance.getAPNSToken() != null) return true;
      await Future<void>.delayed(const Duration(milliseconds: 500));
    }
    return false;
  }

  Future<void> _register(String token) async {
    final auth = ref.read(authProvider);
    final userId = auth.userId;
    if (!auth.isAuthenticated || userId == null) return;
    if (_registeredUserId == userId && _registeredToken == token) return;

    try {
      await ref
          .read(notificationApiServiceProvider)
          .registerDevice(token, Platform.isIOS ? 'IOS' : 'ANDROID');
      _registeredUserId = userId;
      _registeredToken = token;
    } catch (_) {
      // Push registration must never prevent a successful sign-in or render
      // the resident app unusable. The next resume/token refresh retries it.
    }
  }

  @override
  void dispose() {
    _tokenRefreshSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<AuthState>(authProvider, (_, next) {
      if (next.isAuthenticated || _registeredUserId != null) {
        unawaited(_syncForCurrentUser());
      }
    });
    return widget.child;
  }
}
