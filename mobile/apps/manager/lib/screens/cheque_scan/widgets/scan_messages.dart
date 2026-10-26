import 'payee_check_notice.dart';

/// EN/AR messages for the scan wizard's server refusals. Upload codes are
/// worded as the web's `bulkChequeUpload.uploadErrors`.
String uploadErrorMessage(String? code, String fallback, bool ar) {
  const en = {
    'cheque_upload_file_required': 'The file is empty.',
    'cheque_upload_file_too_large': 'The file is larger than 10 MB.',
    'cheque_upload_unsupported_type':
        'Only images (JPEG, PNG, HEIC) and PDFs can be uploaded.',
    'cheque_upload_too_many_pages': 'The PDF has too many pages (at most 6).',
    'cheque_upload_too_many_cheques':
        'Too many cheques in one file (at most 24). Split it and upload again.',
    'cheque_upload_pdf_unreadable':
        'The PDF could not be read. It may be damaged or password-protected.',
    'cheque_upload_image_too_large':
        'The image is too large to read (at most 60 megapixels).',
    'cheque_upload_pdf_not_supported':
        'PDF upload is not available yet. Upload photos of the cheques instead.',
    'cheque_upload_busy':
        'The server is busy reading other cheques. Try again in a moment.',
    'cheque_storage_unavailable':
        'Cheque storage is not reachable right now, so nothing was saved. Try again in a few minutes.',
  };
  const arMap = {
    'cheque_upload_file_required': 'الملف فارغ.',
    'cheque_upload_file_too_large': 'حجم الملف أكبر من 10 ميغابايت.',
    'cheque_upload_unsupported_type':
        'يمكن رفع الصور (JPEG وPNG وHEIC) وملفات PDF فقط.',
    'cheque_upload_too_many_pages':
        'يحتوي ملف PDF على عدد كبير جدًا من الصفحات (6 صفحات كحد أقصى).',
    'cheque_upload_too_many_cheques':
        'عدد الشيكات في الملف الواحد كبير جدًا (24 شيكًا كحد أقصى). قسّم الملف وأعد الرفع.',
    'cheque_upload_pdf_unreadable':
        'تعذّرت قراءة ملف PDF. قد يكون تالفًا أو محميًا بكلمة مرور.',
    'cheque_upload_image_too_large':
        'الصورة كبيرة جدًا ولا يمكن قراءتها (60 ميغابكسل كحد أقصى).',
    'cheque_upload_pdf_not_supported':
        'رفع ملفات PDF غير متاح بعد. ارفع صور الشيكات بدلًا منها.',
    'cheque_upload_busy':
        'الخادم مشغول بقراءة شيكات أخرى. حاول مرة أخرى بعد قليل.',
    'cheque_storage_unavailable':
        'تعذّر الوصول إلى مخزن الشيكات الآن، لذلك لم يُحفظ شيء. حاول مرة أخرى بعد بضع دقائق.',
  };
  final known = (ar ? arMap : en)[code];
  if (known != null) return known;
  return ar ? 'تعذّرت القراءة: $fallback' : 'Extraction failed: $fallback';
}

/// Readable message for a bulk-attach refusal reason (EN/AR).
String attachRefusalMessage(String reason, bool ar) {
  switch (reason) {
    case 'payee_mismatch_unconfirmed':
      return PayeeL(ar).unconfirmedError;
    case 'image_not_issued':
      return ar
          ? 'لا يمكن إرفاق هذه الصورة. امسح الشيك مرة أخرى.'
          : 'This image cannot be attached. Scan the cheque again.';
    case 'cheque_not_attachable':
      return ar
          ? 'تم إيداع هذا الشيك أو تسويته بالفعل، فلا يمكن إرفاق صورة به.'
          : 'This cheque has already been deposited or settled, so a scan cannot be attached.';
    case 'cheque_number_already_used_on_lease':
    case 'duplicate_cheque_number_in_request':
      return ar
          ? 'رقم الشيك مستخدم بالفعل في هذا العقد.'
          : 'That cheque number is already used on this lease.';
    case 'duplicate_cheque_id_in_request':
      return ar
          ? 'تم ربط شيكين بالقسط نفسه.'
          : 'Two cheques are mapped to the same instalment.';
    default:
      return ar
          ? 'تعذّر الحفظ ($reason). أعد التحميل وحاول مجددًا.'
          : 'Could not save ($reason). Reload and try again.';
  }
}
