import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/property_detail_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pins the add-contact flow to the real POST /v1/properties/{id}/contacts
/// contract: PropertyContactDTO requires `category` (@NotNull ContactCategory
/// enum), `name` and `phone` (both @NotBlank); there is no 'role' field on
/// either side — the pre-fix form sent 'role' and no category, a guaranteed
/// 400, and the list row read contact['role'], which the PropertyContact
/// response (category/customLabel) never contains.
class _FakeContactsApi implements HttpClientAdapter {
  _FakeContactsApi({List<Map<String, dynamic>>? contacts})
    : contacts = contacts ?? [];

  /// Raw PropertyContact rows, as `GET /v1/properties/{id}/contacts`
  /// returns them.
  final List<Map<String, dynamic>> contacts;

  /// Captured `POST /v1/properties/{id}/contacts` bodies.
  final List<Map<String, dynamic>> createBodies = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.path;
    final method = options.method;

    Object? body;
    if (method == 'GET' && path.endsWith('/v1/properties/prop-1')) {
      body = {
        'id': 'prop-1',
        'nameEn': 'Marina Heights',
        'emirate': 'DUBAI',
        'type': 'RESIDENTIAL',
      };
    } else if (method == 'GET' && path.endsWith('/v1/units/property/prop-1')) {
      body = <dynamic>[];
    } else if (method == 'GET' &&
        path.endsWith('/v1/buildings/property/prop-1')) {
      body = <dynamic>[];
    } else if (method == 'GET' &&
        path.endsWith('/v1/properties/prop-1/contacts')) {
      body = contacts;
    } else if (method == 'POST' &&
        path.endsWith('/v1/properties/prop-1/contacts')) {
      final data = Map<String, dynamic>.from(options.data as Map);
      createBodies.add(data);
      final created = {'id': 'contact-new', ...data};
      contacts.add(created);
      body = created;
    }

    if (body == null) {
      return ResponseBody.fromString('not found', 404);
    }
    return ResponseBody.fromString(
      jsonEncode(body),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

class _FakeAuthService implements AuthService {
  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'admin@example.com',
    'name': 'Admin',
    'role': 'TENANT_ADMIN',
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 't1', 'name': 'Demo Tenant', 'slug': 'demo'},
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // AuthNotifier's _init reads the persisted session from
  // flutter_secure_storage; stub the platform channel so it finds a userId
  // and proceeds to the (fake) profile fetch instead of crashing the zone.
  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async {
          if (call.method == 'read') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            return {'userId': 'u1', 'tenantId': 't1'}[args['key']];
          }
          if (call.method == 'readAll') return <String, String>{};
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, null);
  });

  Future<_FakeContactsApi> pumpScreen(
    WidgetTester tester,
    _FakeContactsApi api,
  ) async {
    // Tall surface so the contacts section (far down the scroll view) is
    // laid out and tappable without scrolling.
    await tester.binding.setSurfaceSize(const Size(500, 3000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final client = ApiClient(baseUrl: 'http://fake/api');
    client.dio.interceptors.clear();
    client.dio.httpClientAdapter = api;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(client),
          authProvider.overrideWith((ref) => AuthNotifier(_FakeAuthService())),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const PropertyDetailScreen(propertyId: 'prop-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return api;
  }

  testWidgets('contact rows render category/customLabel, not phantom role', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      _FakeContactsApi(
        contacts: [
          {
            'id': 'c-1',
            'category': 'PLUMBER',
            'name': 'Ahmed',
            'phone': '+971501111111',
          },
          {
            'id': 'c-2',
            'category': 'OTHER',
            'customLabel': 'Landscaper',
            'name': 'Bilal',
            'phone': '+971502222222',
          },
        ],
      ),
    );

    expect(find.text('Ahmed'), findsOneWidget);
    expect(find.text('Plumber'), findsOneWidget);
    // OTHER + customLabel renders the custom label, not 'Other'.
    expect(find.text('Landscaper'), findsOneWidget);
  });

  testWidgets(
    'add-contact POST sends category/name/phone and no role; phone required',
    (tester) async {
      final api = await pumpScreen(tester, _FakeContactsApi());

      await tester.tap(find.text('ADD'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Name'),
        'Ahmed',
      );

      // phone is @NotBlank on the backend — the form must not submit
      // without it.
      await tester.tap(find.text('ADD CONTACT'));
      await tester.pumpAndSettle();
      expect(api.createBodies, isEmpty);
      expect(find.text('Required'), findsOneWidget);

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Phone'),
        '+971501234567',
      );
      await tester.tap(find.text('ADD CONTACT'));
      await tester.pumpAndSettle();

      expect(api.createBodies, hasLength(1));
      final body = api.createBodies.single;
      expect(body['category'], 'PLUMBER');
      expect(body['name'], 'Ahmed');
      expect(body['phone'], '+971501234567');
      expect(body.containsKey('role'), isFalse);
    },
  );

  testWidgets('OTHER category exposes and submits a custom label', (
    tester,
  ) async {
    final api = await pumpScreen(tester, _FakeContactsApi());

    await tester.tap(find.text('ADD'));
    await tester.pumpAndSettle();

    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Other').last);
    await tester.pumpAndSettle();

    await tester.enterText(
      find.widgetWithText(TextFormField, 'Custom Label'),
      'Landscaper',
    );
    await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Bilal');
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Phone'),
      '+971502222222',
    );
    await tester.tap(find.text('ADD CONTACT'));
    await tester.pumpAndSettle();

    expect(api.createBodies, hasLength(1));
    expect(api.createBodies.single['category'], 'OTHER');
    expect(api.createBodies.single['customLabel'], 'Landscaper');
  });
}
