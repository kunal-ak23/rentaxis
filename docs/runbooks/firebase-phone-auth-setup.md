# Firebase Phone Authentication Setup

The RentAxis security app uses Firebase Phone Authentication on Android and
iOS. Firebase sends and verifies the SMS code on the device. The app then sends
the Firebase ID token to `POST /api/v1/auth/firebase`; the backend verifies the
token with Firebase Admin and signs in the single active `SECURITY_GUARD` whose
stored E.164 phone matches the signed `phone_number` claim.

WhatsApp, Azure Advanced Messaging, message templates and the `login_otps`
table are not part of this flow.

## 1. Create and configure the Firebase project

1. Create or select the production Firebase project and attach billing.
2. Open **Authentication → Sign-in method** and enable **Phone**.
3. Open **Authentication → Settings → SMS region policy** and explicitly allow
   the countries where guards will sign in. A newly created project may allow
   no SMS regions until this is configured.
4. Add the Android app with package name `com.rentaxis.security`.
5. Add the iOS app with bundle ID `com.rentaxis.security`.

Use separate Firebase projects for dev/staging/prod if those environments must
not share users, quotas or abuse controls.

Current project state (2026-07-30):

- Firebase project: `rent-axis-493307` on the Blaze plan;
- Phone provider: enabled;
- SMS region allow-list: United Arab Emirates (`AE`) and India (`IN`);
- Android and iOS apps: registered as `com.rentaxis.security`.

## 2. Android app verification

In Firebase **Project settings → General → Android app**, add SHA-1 and SHA-256
fingerprints for every certificate that can sign the app:

- local debug certificate;
- upload/release certificate used by CI;
- Google Play App Signing certificate, once Play manages the app.

Play Integrity is the normal verification path. Firebase can use reCAPTCHA as a
fallback. Test phone auth on a real device; Firebase test phone numbers are the
safe option for automated/manual non-production tests.

The Android application ID is already `com.rentaxis.security`, and the main
manifest includes Internet access. The local debug SHA-1 and SHA-256 are
registered in Firebase; release/upload and Play App Signing fingerprints still
need to be added when those certificates exist.

## 3. iOS app verification

1. In Xcode, enable the **Push Notifications** capability for the Runner target.
2. In Firebase **Project settings → Cloud Messaging**, upload an APNs
   authentication key for the iOS app.
3. Keep **Background Modes → Background fetch** and **Remote notifications**
   enabled. Their `Info.plist` entries are already checked in.
4. Add the iOS app's **Encoded App ID** from Firebase Project settings as a
   custom URL scheme on the Runner target. Firebase uses this for the reCAPTCHA
   fallback when silent APNs verification is unavailable.
5. Confirm the Apple provisioning profile includes the push-notification
   entitlement.

The URL scheme, background modes and push-notification entitlement are checked
in. The APNs key/certificate, Apple Team ID and matching provisioning profile
remain Apple-account configuration and are not present yet.

## 4. Native client configuration

The checked-in `android/app/google-services.json` and
`ios/Runner/GoogleService-Info.plist` identify the Firebase apps. They contain
non-secret project identifiers and are processed by the Android Google Services
plugin and the iOS Runner target respectively. No Firebase Dart defines are
required when building the security app.

The backend service-account credential below is still secret and must never be
included in either mobile configuration file.

## 5. Backend Firebase Admin credential

Create a Firebase Admin service-account JSON in the Firebase/Google Cloud
project. Do not commit it. Encode the complete file onto one line:

```bash
base64 < firebase-admin.json | tr -d '\n'
```

Add these GitHub Actions repository secrets:

- `FIREBASE_PROJECT_ID`
- `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`

The deploy workflow passes both into Docker. With the `prod` Spring profile, the
backend refuses to start when either value is blank or when the credential
cannot be decoded. Local/test contexts can boot without Firebase, but
`POST /api/v1/auth/firebase` returns 503 until configured.

Rotate a leaked service-account key immediately in Google Cloud IAM, replace the
GitHub secret, and redeploy.

For `rent-axis-493307`, the Firebase Admin service account and both GitHub
Actions secrets were configured on 2026-07-30. The downloaded JSON was removed
after the encrypted secret was verified.

## 6. End-to-end acceptance check

1. In Firebase Authentication, add a fictional test phone number and six-digit
   code whose country is allowed by the SMS region policy. Firebase does not
   send a real SMS for test numbers.
2. Create one active RentAxis `SECURITY_GUARD` with exactly that E.164 phone.
3. For an emulator or an automated debug run, opt out of Android app
   verification only for that build:

   ```bash
   flutter build apk --debug \
     --dart-define=FIREBASE_AUTH_TEST_MODE=true
   ```

   The flag is ignored by release builds. Omit it for normal device and
   production builds.
4. Enter the test phone and code.
5. Confirm the app lands on **Expected today** and can load only that guard's
   assigned properties.
6. Make the guard inactive and repeat. Firebase verification should succeed, but
   RentAxis must reject the token exchange with 401.
7. Try a valid Firebase phone that is not a RentAxis guard. The same generic
   401/result must appear.

Never paste a real Firebase ID token, SMS code, service-account JSON or decoded
credential into tickets or chat.
