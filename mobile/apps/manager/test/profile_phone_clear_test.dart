import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/profile_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pins the PUT /auth/me phone-clearing contract on the profile screen:
/// - the phone field is prefilled from GET /auth/me (the auth state carries no
///   phone, so without the prefetch an empty field is indistinguishable from
///   an untouched one);
/// - once prefilled, emptying the field sends `phoneNumber: ''`, which the
///   backend normalizes to null (PhoneNumbers.compact) — i.e. a real clear;
/// - when the prefetch failed, an empty field omits the key entirely so a
///   name-only save can never wipe a stored number the screen never saw.
class _FakeAuthApi implements HttpClientAdapter {
  _FakeAuthApi({this.phoneNumber, this.failProfileGet = false});

  /// phoneNumber returned by GET /auth/me.
  final String? phoneNumber;

  /// When true, GET /auth/me responds 500 (prefetch-failure path).
  final bool failProfileGet;

  /// Body of the last PUT /auth/me, decoded to a map.
  Map<String, dynamic>? lastProfileUpdate;

  Map<String, dynamic> get _profile => {
    'id': 'user-1',
    'email': 'pm@example.com',
    'name': 'Alia',
    'role': 'TENANT_ADMIN',
    'phoneNumber': phoneNumber,
    'tenantId': 't-1',
  };

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    Object? body;
    var status = 200;
    if (options.path.endsWith('/auth/me/tenants')) {
      body = [
        {'id': 't-1', 'name': 'Org One', 'slug': 'org-one'},
      ];
    } else if (options.path.endsWith('/auth/me')) {
      if (options.method == 'PUT') {
        final data = options.data;
        lastProfileUpdate = data is Map
            ? Map<String, dynamic>.from(data)
            : Map<String, dynamic>.from(jsonDecode(data as String) as Map);
        body = _profile;
      } else if (failProfileGet) {
        body = {'message': 'boom'};
        status = 500;
      } else {
        body = _profile;
      }
    }

    if (body == null) {
      return ResponseBody.fromString('not found', 404);
    }
    return ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

ApiClient _fakeApiClient(_FakeAuthApi api) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = api;
  return client;
}

/// In-memory stub of flutter_secure_storage so AuthNotifier._init can run.
void _stubSecureStorage(Map<String, String> store) {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
        (call) async {
          final args = call.arguments as Map?;
          switch (call.method) {
            case 'read':
              return store[args!['key'] as String];
            case 'write':
              store[args!['key'] as String] = args['value'] as String;
              return null;
            case 'delete':
              store.remove(args!['key'] as String);
              return null;
            case 'readAll':
              return store;
            default:
              return null;
          }
        },
      );
}

Future<void> _pumpProfile(WidgetTester tester, _FakeAuthApi api) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(_fakeApiClient(api))],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: const ProfileScreen(),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TenantContext.currentTenantId = null;
  });

  testWidgets('prefills the phone from GET /auth/me and clearing it sends an '
      'empty string', (tester) async {
    _stubSecureStorage({
      'userId': 'user-1',
      'userRole': 'TENANT_ADMIN',
      'tenantId': 't-1',
    });
    final api = _FakeAuthApi(phoneNumber: '+971501111111');
    await _pumpProfile(tester, api);

    final phoneField = find.widgetWithText(TextFormField, '+971501111111');
    expect(phoneField, findsOneWidget, reason: 'phone must be prefilled');

    await tester.ensureVisible(phoneField);
    await tester.enterText(phoneField, '');
    await tester.pumpAndSettle();

    // The screen also renders the "Update password" GoldButton; the profile
    // save button is the first one in the tree.
    final saveButton = find.byType(GoldButton).first;
    expect(saveButton, findsOneWidget);
    await tester.ensureVisible(saveButton);
    await tester.tap(saveButton);
    await tester.pumpAndSettle();

    expect(api.lastProfileUpdate, isNotNull);
    // Empty string — not an omitted key — is what clears the stored number.
    expect(api.lastProfileUpdate, containsPair('phoneNumber', ''));
  });

  testWidgets('omits phoneNumber on a name-only save when the prefetch '
      'failed', (tester) async {
    _stubSecureStorage({});
    final api = _FakeAuthApi(failProfileGet: true);
    await _pumpProfile(tester, api);

    final nameField = find.byType(TextFormField).first;
    await tester.ensureVisible(nameField);
    await tester.enterText(nameField, 'New Name');
    await tester.pumpAndSettle();

    // The screen also renders the "Update password" GoldButton; the profile
    // save button is the first one in the tree.
    final saveButton = find.byType(GoldButton).first;
    expect(saveButton, findsOneWidget);
    await tester.ensureVisible(saveButton);
    await tester.tap(saveButton);
    await tester.pumpAndSettle();

    expect(api.lastProfileUpdate, isNotNull);
    expect(api.lastProfileUpdate, containsPair('name', 'New Name'));
    // The screen never learned the stored phone, so it must not clear it.
    expect(api.lastProfileUpdate!.containsKey('phoneNumber'), isFalse);
  });
}
