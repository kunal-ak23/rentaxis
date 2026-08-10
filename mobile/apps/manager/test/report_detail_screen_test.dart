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

  Future<void> pumpTyped(
    WidgetTester tester,
    String reportType,
    dynamic report,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReportDetailScreen(
            reportType: reportType,
            reportTitle: 'Report',
            reportData: report,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  // TrialBalanceDTO serializes the per-account rows as `lines` (the screen
  // previously read the nonexistent `accounts`/`rows`, so the table was
  // always empty).
  testWidgets('trial balance renders account rows from the lines field', (
    tester,
  ) async {
    await pumpTyped(tester, 'trialBalance', <String, dynamic>{
      'lines': [
        {
          'accountCode': 'C-01-01-001',
          'accountName': 'Rental Income',
          'debit': 0,
          'credit': 500,
        },
        {
          'accountCode': 'A-01-001',
          'accountName': 'Bank',
          'debit': 300,
          'credit': 0,
        },
      ],
      'totalDebit': 300,
      'totalCredit': 500,
    });

    expect(find.text('Rental Income'), findsOneWidget);
    expect(find.text('Bank'), findsOneWidget);
    // Row amount + matching total.
    expect(find.text(Formatters.currency(500)), findsNWidgets(2));
    expect(find.text(Formatters.currency(300)), findsNWidgets(2));
  });

  // VatReturnDTO field names: totalOutputVat / totalInputVat / netVatPayable
  // with line items in salesLines + purchaseLines (the screen previously read
  // outputVat/inputVat/details, showing 0 everywhere).
  testWidgets('vat return renders DTO totals and sales/purchase lines', (
    tester,
  ) async {
    await pumpTyped(tester, 'vatReturn', <String, dynamic>{
      'period': '2026-01-01 to 2026-03-31',
      'totalOutputVat': 250,
      'totalInputVat': 90,
      'netVatPayable': 160,
      'salesLines': [
        {'description': 'Unit 101 rent', 'taxableAmount': 3500, 'vatAmount': 175},
        {'description': 'Unit 102 rent', 'taxableAmount': 1500, 'vatAmount': 75},
      ],
      'purchaseLines': [
        {'description': 'AC repair', 'taxableAmount': 1200, 'vatAmount': 60},
        {'description': 'Cleaning', 'taxableAmount': 600, 'vatAmount': 30},
      ],
    });

    expect(find.text(Formatters.currency(250)), findsOneWidget);
    expect(find.text(Formatters.currency(90)), findsOneWidget);
    expect(find.text(Formatters.currency(160)), findsOneWidget);
    expect(find.text('SALES (OUTPUT VAT)'), findsOneWidget);
    expect(find.text('PURCHASES (INPUT VAT)'), findsOneWidget);
    expect(find.text('Unit 101 rent'), findsOneWidget);
    expect(find.text(Formatters.currency(175)), findsOneWidget);
    expect(find.text('AC repair'), findsOneWidget);
    expect(find.text(Formatters.currency(60)), findsOneWidget);
  });

  // The vendor ledger response is raw FinancialTransaction rows with no
  // balance field; the screen used to render a Balance column that always
  // showed 0.00 next to real debit/credit values.
  testWidgets('vendor ledger shows debit/credit chips and no balance column', (
    tester,
  ) async {
    await pumpTyped(tester, 'vendorLedger', <dynamic>[
      {
        'description': 'AC repair invoice',
        'debit': 500,
        'credit': 0,
        'date': '2026-01-15',
      },
      {
        'description': 'Refund received',
        'debit': 0,
        'credit': 200,
        'date': '2026-02-01',
      },
    ]);

    expect(find.text('AC repair invoice'), findsOneWidget);
    expect(find.text('Refund received'), findsOneWidget);
    expect(find.text('Debit: ${Formatters.currency(500)}'), findsOneWidget);
    expect(find.text('Credit: ${Formatters.currency(200)}'), findsOneWidget);
    expect(find.text('Balance'), findsNothing);
    expect(find.text(Formatters.currency(0)), findsNothing);
  });
}
