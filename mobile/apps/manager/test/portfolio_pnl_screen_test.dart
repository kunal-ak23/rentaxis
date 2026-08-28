import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/portfolio_pnl_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class _PortfolioApi implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (!options.path.endsWith('/v1/finance/reports/portfolio-profit-loss')) {
      return ResponseBody.fromString('not found', 404);
    }
    return ResponseBody.fromString(
      jsonEncode({
        'overall': {
          'totalIncome': 150000,
          'totalExpenses': 30000,
          'netProfit': 120000,
        },
        'properties': [
          {
            'propertyId': 'p-1',
            'propertyNameEn': 'North Gate Tower',
            'propertyNameAr': 'برج البوابة الشمالية',
            'totalIncome': 100000,
            'totalExpenses': 20000,
            'netProfit': 80000,
          },
          {
            'propertyId': 'p-2',
            'propertyNameEn': 'Marina Court',
            'propertyNameAr': 'ساحة المارينا',
            'totalIncome': 50000,
            'totalExpenses': 10000,
            'netProfit': 40000,
          },
        ],
      }),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

void main() {
  testWidgets('shows overall P&L first and every property separately', (
    tester,
  ) async {
    final client = ApiClient(baseUrl: 'http://fake/api');
    client.dio.interceptors.clear();
    client.dio.httpClientAdapter = _PortfolioApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(client)],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const PortfolioPnlScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Overall Portfolio P&L'), findsOneWidget);
    expect(find.text('2 properties in portfolio'), findsOneWidget);
    expect(find.text('North Gate Tower'), findsOneWidget);
    expect(find.text('Marina Court'), findsOneWidget);
  });

  testWidgets('search filters a large property P&L list by property name', (
    tester,
  ) async {
    final client = ApiClient(baseUrl: 'http://fake/api');
    client.dio.interceptors.clear();
    client.dio.httpClientAdapter = _PortfolioApi();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(client)],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const PortfolioPnlScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'North');
    await tester.pump();

    expect(find.text('North Gate Tower'), findsOneWidget);
    expect(find.text('Marina Court'), findsNothing);
    expect(find.text('Showing 1 of 2'), findsOneWidget);
  });
}
