// The gallery is the OS photo picker, which a test cannot operate. The app
// still calls ImagePicker().pickImage(source: gallery) as it does for a
// person; this platform instance answers with the photo the test drew.
// ignore: depend_on_referenced_packages
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';

class FakeGallery extends ImagePickerPlatform {
  String? nextPath;

  @override
  Future<XFile?> getImageFromSource({
    required ImageSource source,
    ImagePickerOptions options = const ImagePickerOptions(),
  }) async {
    final path = nextPath;
    return path == null ? null : XFile(path);
  }

  static FakeGallery install() {
    final fake = FakeGallery();
    ImagePickerPlatform.instance = fake;
    return fake;
  }
}
