import 'dart:async';
import 'dart:io';

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
    if (!Platform.isAndroid) return;
    // Widget tests and desktop previews do not initialise a native Firebase
    // app. Keep those surfaces usable; a real Android/iOS session retries
    // registration as soon as Firebase is available.
    try {
      _tokenRefreshSubscription = FirebaseMessaging.instance.onTokenRefresh
          .listen((token) => _register(token));
    } catch (_) {}
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncForCurrentUser());
  }

  Future<void> _syncForCurrentUser() async {
    if (!Platform.isAndroid) return;
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

      final token = await FirebaseMessaging.instance.getToken();
      if (token != null && token.isNotEmpty) await _register(token);
    } catch (_) {
      // Native Firebase is intentionally absent from widget tests/previews.
    }
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
