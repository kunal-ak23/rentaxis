import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  const firebaseAuthTestMode = bool.fromEnvironment('FIREBASE_AUTH_TEST_MODE');
  if (kDebugMode && firebaseAuthTestMode) {
    await FirebaseAuth.instance.setSettings(
      appVerificationDisabledForTesting: true,
    );
  }
  runApp(const ProviderScope(child: SecurityApp()));
}
