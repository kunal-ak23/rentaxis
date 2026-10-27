import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Where a maintenance attachment comes from.
enum TicketAttachmentSource { camera, gallery, document }

/// Takes a photo for a maintenance request, asking for the camera first.
///
/// Returns the photo's path, or null when the renter cancelled or the camera
/// is not allowed. A camera the renter has turned off for the app cannot be
/// asked for again — iOS shows its prompt once — so that case offers the
/// app's Settings page instead of a dead end.
Future<String?> takeTicketPhoto(BuildContext context) async {
  final status = await Permission.camera.request();
  if (!status.isGranted) {
    if (context.mounted) {
      final ar = context.isAr;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            ar
                ? 'اسمح بالوصول إلى الكاميرا لإضافة صورة'
                : 'Allow camera access to add a photo',
          ),
          action: status.isPermanentlyDenied || status.isRestricted
              ? SnackBarAction(
                  label: ar ? 'الإعدادات' : 'Settings',
                  onPressed: openAppSettings,
                )
              : null,
        ),
      );
    }
    return null;
  }
  final image = await ImagePicker().pickImage(
    source: ImageSource.camera,
    maxWidth: 1920,
    maxHeight: 1080,
    imageQuality: 80,
  );
  return image?.path;
}

/// Asks where an attachment for an existing request comes from.
Future<TicketAttachmentSource?> chooseTicketAttachmentSource(
  BuildContext context,
) {
  final ar = context.isAr;
  return showModalBottomSheet<TicketAttachmentSource>(
    context: context,
    showDragHandle: true,
    builder: (context) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final (source, icon, label) in [
            (
              TicketAttachmentSource.camera,
              Icons.photo_camera_outlined,
              ar ? 'الكاميرا' : 'Camera',
            ),
            (
              TicketAttachmentSource.gallery,
              Icons.photo_library_outlined,
              ar ? 'المعرض' : 'Gallery',
            ),
            (
              TicketAttachmentSource.document,
              Icons.attach_file_rounded,
              ar ? 'مستند' : 'Document',
            ),
          ])
            ListTile(
              key: Key('attachment-source-${source.name}'),
              leading: Icon(icon),
              title: Text(label),
              onTap: () => Navigator.of(context).pop(source),
            ),
        ],
      ),
    ),
  );
}

/// The file for [source]: a photo taken now, one from the library, or a
/// JPG/PNG/PDF document — the types the request's attachment upload accepts.
Future<String?> pickTicketAttachment(
  BuildContext context,
  TicketAttachmentSource source,
) async {
  switch (source) {
    case TicketAttachmentSource.camera:
      return takeTicketPhoto(context);
    case TicketAttachmentSource.gallery:
      final image = await ImagePicker().pickImage(
        source: ImageSource.gallery,
        maxWidth: 1920,
        maxHeight: 1080,
        imageQuality: 80,
      );
      return image?.path;
    case TicketAttachmentSource.document:
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['jpg', 'jpeg', 'png', 'pdf'],
      );
      return result?.files.single.path;
  }
}
