import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/finance_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pins the transactions tab to the real /v1/finance/transactions contract:
/// the backend serializes raw FinancialTransaction entities whose JSON keys
/// are `date`, `debit`, `credit` and a nested `account` object with `name`.
/// The screen previously read the nonexistent
/// `debitAmount`/`creditAmount`/`transactionDate`/`createdAt`/`accountName`,
/// so no amount ever rendered, every row was labeled 'Credit', every date
/// showed '-' and the account title/description line never appeared.
class _FakeFinanceApi implements HttpClientAdapter {
  _FakeFinanceApi({this.transactions = const []});

  final List<Map<String, dynamic>> transactions;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    Object? body;
    if (options.path.endsWith('/v1/finance/transactions')) {
      body = transactions;
    } else if (options.path.endsWith('/v1/finance/accounts')) {
      body = const <dynamic>[];
    } else if (options.path.endsWith('/v1/finance/reports/organisation')) {
      body = const <String, dynamic>{};
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

ApiClient _fakeApiClient(_FakeFinanceApi api) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = api;
  return client;
}

void main() {
  Future<void> pumpTransactionsTab(
    WidgetTester tester,
    List<Map<String, dynamic>> transactions,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(
            _fakeApiClient(_FakeFinanceApi(transactions: transactions)),
          ),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const FinanceScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  final rows = <Map<String, dynamic>>[
    {
      'id': 'tx-1',
      'date': '2026-01-15',
      'description': 'AC repair invoice',
      'debit': 500,
      'credit': 0,
      'account': {'id': 'acc-1', 'code': '5100', 'name': 'Maintenance Expense'},
    },
    {
      'id': 'tx-2',
      'date': '2026-02-01',
      'description': 'Rent received',
      'debit': 0,
      'credit': 3500,
      'account': {'id': 'acc-2', 'code': '4000', 'name': 'Rental Income'},
    },
  ];

  testWidgets('opens Transactions as the first Finance tab', (tester) async {
    await pumpTransactionsTab(tester, rows);

    final tabBar = tester.widget<TabBar>(find.byType(TabBar));
    expect(
      tabBar.tabs.map((tab) => (tab as Tab).text),
      containsAllInOrder(['TRANSACTIONS', 'ACCOUNTS', 'REPORTS', 'TOOLS']),
    );
    expect(find.text('Maintenance Expense'), findsOneWidget);
  });

  testWidgets('renders amounts from the entity debit/credit fields', (
    tester,
  ) async {
    await pumpTransactionsTab(tester, rows);

    expect(find.text(Formatters.currency(500)), findsOneWidget);
    expect(find.text(Formatters.currency(3500)), findsOneWidget);
    // Pre-fix, debit/credit were always 0 so both rows were labeled 'Credit'.
    expect(find.text('Debit'), findsOneWidget);
    expect(find.text('Credit'), findsOneWidget);
  });

  testWidgets('renders dates from the entity date field', (tester) async {
    await pumpTransactionsTab(tester, rows);

    expect(find.text(Formatters.date('2026-01-15')), findsOneWidget);
    expect(find.text(Formatters.date('2026-02-01')), findsOneWidget);
    // Pre-fix, Formatters.date(null) rendered '-' on every row.
    expect(find.text('-'), findsNothing);
  });

  testWidgets('renders the nested account name as the row title', (
    tester,
  ) async {
    await pumpTransactionsTab(tester, rows);

    // Pre-fix the title read the nonexistent tx['accountName'], so it always
    // fell back to the description and this secondary line never rendered.
    expect(find.text('Maintenance Expense'), findsOneWidget);
    expect(find.text('Rental Income'), findsOneWidget);
    expect(find.text('AC repair invoice'), findsOneWidget);
    expect(find.text('Rent received'), findsOneWidget);
  });
}
