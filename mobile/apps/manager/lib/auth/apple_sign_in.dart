import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class AppleSignInResult {
  const AppleSignInResult({
    required this.identityToken,
    required this.rawNonce,
    this.authorizationCode,
  });

  final String identityToken;
  final String rawNonce;

  /// One-time code the backend exchanges for the refresh token that account
  /// deletion revokes (App Store Guideline 5.1.1(v)). Null only if the
  /// platform withheld it; sign-in does not depend on it.
  final String? authorizationCode;
}

Future<AppleSignInResult> requestAppleSignIn() async {
  final rawNonce = _randomNonce();
  final hashedNonce = sha256.convert(utf8.encode(rawNonce)).toString();
  final credential = await SignInWithApple.getAppleIDCredential(
    scopes: const [
      AppleIDAuthorizationScopes.email,
      AppleIDAuthorizationScopes.fullName,
    ],
    nonce: hashedNonce,
  );
  final identityToken = credential.identityToken;
  if (identityToken == null || identityToken.isEmpty) {
    throw StateError('Apple did not return an identity token');
  }
  return AppleSignInResult(
    identityToken: identityToken,
    rawNonce: rawNonce,
    authorizationCode: credential.authorizationCode,
  );
}

String _randomNonce([int length = 32]) {
  const alphabet =
      '0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._';
  final random = Random.secure();
  return List.generate(
    length,
    (_) => alphabet[random.nextInt(alphabet.length)],
  ).join();
}
