import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:manager/app.dart';

void main() {
  testWidgets('ManagerApp smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: ManagerApp()),
    );
    await tester.pumpAndSettle();
  });
}
