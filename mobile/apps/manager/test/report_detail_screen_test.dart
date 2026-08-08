import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/report_detail_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Regression guard for the P&L field-name contract: the backend ReportDTO
/// serializes `totalExpenses`, `netProfit` and the map-shaped
/// `incomeBreakdown`/`directExpenseBreakdown`/`indirectExpenseBreakdown`
/// (key = 'code - name', value = amount). The screen previously read
/// `totalExpense`/`netIncome`/`incomeAccounts`/`expenseAccounts`, so
/// expenses always showed 0 and net income equalled total income.
void main() {
  Future<void> pumpReport(
    WidgetTester tester,
    Map<String, dynamic> report,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReportDetailScreen(
            reportType: 'orgSummary',
            reportTitle: 'Organisation P&L',
            reportData: report,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  final report = <String, dynamic>{
    'reportType': 'ORGANISATION',
    'totalIncome': 1000,
    'totalExpenses': 400,
    'netOperatingIncome': 700,
    'netProfit': 600,
    'incomeBreakdown': {
      'C-01-01-001 - Rental Income': 800,
      'C-01-02-001 - Other Income': 200,
    },
    'directExpenseBreakdown': {'D-01-001 - Maintenance': 300},
    'indirectExpenseBreakdown': {'E-01-001 - Admin Salaries': 100},
  };

  testWidgets('renders expenses and net profit from ReportDTO field names', (
    tester,
  ) async {
    await pumpReport(tester, report);

    expect(find.text(Formatters.currency(1000)), findsOneWidget);
    // totalExpenses (plural) — the old singular read always showed 0.
    expect(find.text(Formatters.currency(400)), findsOneWidget);
    // netProfit — the old netIncome read fell back to income - 0.
    expect(find.text(Formatters.currency(600)), findsOneWidget);
  });

  testWidgets('renders income and direct+indirect expense breakdown maps', (
    tester,
  ) async {
    await pumpReport(tester, report);

    // Anchors the empty-case test below: these are the rendered labels.
    expect(find.text('INCOME BREAKDOWN'), findsOneWidget);
    expect(find.text('EXPENSE BREAKDOWN'), findsOneWidget);
    expect(find.text('C-01-01-001 - Rental Income'), findsOneWidget);
    expect(find.text(Formatters.currency(800)), findsOneWidget);
    expect(find.text('C-01-02-001 - Other Income'), findsOneWidget);
    expect(find.text('D-01-001 - Maintenance'), findsOneWidget);
    expect(find.text('E-01-001 - Admin Salaries'), findsOneWidget);
    expect(find.text(Formatters.currency(100)), findsOneWidget);
  });

  testWidgets('missing breakdowns render no section rows', (tester) async {
    await pumpReport(tester, <String, dynamic>{
      'totalIncome': 0,
      'totalExpenses': 0,
      'netProfit': 0,
    });

    expect(find.text('INCOME BREAKDOWN'), findsNothing);
    expect(find.text('EXPENSE BREAKDOWN'), findsNothing);
  });
}
