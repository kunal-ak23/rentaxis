import 'dart:io';

import 'package:integration_test/integration_test_driver_extended.dart';

Future<void> main() async {
  await integrationDriver(
    onScreenshot: (name, bytes, [args]) async {
      final dir = Directory('build/screen_tour');
      dir.createSync(recursive: true);
      File('${dir.path}/$name.png').writeAsBytesSync(bytes);
      return true;
    },
  );
}
