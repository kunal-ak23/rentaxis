import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

class AppleSignInResult {
  const AppleSignInResult({
    required this.identityToken,
    required this.rawNonce,
  });

  final String identityToken;
  final String rawNonce;
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
  return AppleSignInResult(identityToken: identityToken, rawNonce: rawNonce);
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
