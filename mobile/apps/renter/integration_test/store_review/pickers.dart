// The photo pickers, stood in for so a scenario never leaves the app for the
// system gallery or file browser. The pictures are drawn here at run time — no
// image file is committed.
import 'dart:io';
import 'dart:ui' as ui;

import 'package:file_picker/file_picker.dart';
import 'package:flutter/painting.dart';
// The create-ticket screen reaches the gallery through image_picker's platform
// instance; image_picker itself does not export the platform class.
// ignore: depend_on_referenced_packages
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';

/// A leaking pipe under a sink: enough of a picture to read as a maintenance
/// photo on camera.
Future<String> drawLeakPhoto(String name, {bool closeUp = false}) async {
  const width = 960.0, height = 720.0;
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  canvas.drawRect(
    const Rect.fromLTWH(0, 0, width, height),
    Paint()
      ..shader = ui.Gradient.linear(
        Offset.zero,
        const Offset(0, height),
        const [Color(0xFFEDE8DF), Color(0xFFCFC6B8)],
      ),
  );
  final scale = closeUp ? 1.6 : 1.0;
  canvas.translate(width / 2, height / 2);
  canvas.scale(scale);
  canvas.translate(-width / 2, -height / 2);
  // Cabinet.
  canvas.drawRRect(
    RRect.fromRectAndRadius(
      const Rect.fromLTWH(120, 430, 720, 260),
      const Radius.circular(18),
    ),
    Paint()..color = const Color(0xFF8A6A4A),
  );
  // Basin and pipe.
  canvas.drawRRect(
    RRect.fromRectAndRadius(
      const Rect.fromLTWH(220, 160, 520, 70),
      const Radius.circular(30),
    ),
    Paint()..color = const Color(0xFFF7F7F7),
  );
  canvas.drawRect(
    const Rect.fromLTWH(450, 230, 60, 200),
    Paint()..color = const Color(0xFF9EA7AD),
  );
  canvas.drawRect(
    const Rect.fromLTWH(430, 330, 100, 26),
    Paint()..color = const Color(0xFF7D868C),
  );
  // Drips and a puddle.
  final water = Paint()..color = const Color(0xFF3A8DDE);
  for (var i = 0; i < 4; i++) {
    canvas.drawCircle(Offset(480, 372 + i * 16.0), 9 - i * 1.5, water);
  }
  canvas.drawOval(
    Rect.fromCenter(center: const Offset(480, 700), width: 460, height: 46),
    Paint()..color = const Color(0x993A8DDE),
  );
  final image = await recorder.endRecording().toImage(
    width.toInt(),
    height.toInt(),
  );
  final png = await image.toByteData(format: ui.ImageByteFormat.png);
  image.dispose();
  final file = File('${Directory.systemTemp.path}/$name.png');
  await file.writeAsBytes(png!.buffer.asUint8List(), flush: true);
  return file.path;
}

/// The gallery, answering with photos drawn by [drawLeakPhoto].
class FakeGallery extends ImagePickerPlatform {
  FakeGallery(this.paths);

  final List<String> paths;

  @override
  Future<List<XFile>> getMultiImageWithOptions({
    MultiImagePickerOptions options = const MultiImagePickerOptions(),
  }) async => [for (final path in paths) XFile(path)];
}

/// The file browser the ticket screen opens for an extra attachment.
class FakeFileBrowser extends FilePicker {
  FakeFileBrowser(this.path);

  final String path;

  @override
  Future<FilePickerResult?> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    bool allowCompression = true,
    int compressionQuality = 30,
    bool allowMultiple = false,
    bool withData = false,
    bool withReadStream = false,
    bool lockParentWindow = false,
    bool readSequential = false,
  }) async {
    final file = File(path);
    return FilePickerResult([
      PlatformFile(
        name: file.uri.pathSegments.last,
        size: file.lengthSync(),
        path: path,
      ),
    ]);
  }
}
