import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The consent step before a coupon's redirect link opens: names the business
/// the renter is about to be sent to and every kind of their own data that is
/// inside that link. Resolves to `true` only on **Continue**; Cancel, a barrier
/// tap and a drag-down all resolve to `false`/`null`, which callers treat as no.
///
/// Shown only when the link carries renter data — a redirect with nothing
/// personal in it opens without asking.
class CouponRedirectSheet extends StatelessWidget {
  const CouponRedirectSheet({
    super.key,
    required this.businessName,
    required this.fields,
  });

  /// Already resolved to the app's language; blank when the card has no name.
  final String businessName;

  /// The server's source names, e.g. `RENTER_NAME`. Unknown ones are listed as
  /// "other details" rather than omitted — this sheet must not under-report.
  final List<String> fields;

  static Future<bool?> show(
    BuildContext context, {
    required String businessName,
    required List<String> fields,
  }) {
    return showModalBottomSheet<bool>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) =>
          CouponRedirectSheet(businessName: businessName, fields: fields),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final m = context.miftah;
    final l = _L(isAr);
    final who = businessName.trim().isEmpty
        ? l.thisBusiness
        : businessName.trim();

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(MiftahRadii.sheet),
        ),
      ),
      // `top: false` — the sheet's own rounded edge already clears the status
      // bar. The bottom inset is what keeps the buttons off the home indicator.
      child: SafeArea(
        top: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(
            MiftahSpacing.page,
            14,
            MiftahSpacing.page,
            24,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                child: Container(
                  width: 38,
                  height: 4,
                  decoration: BoxDecoration(
                    color: m.borderStrong,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 18),
              Text(
                l.title(who),
                key: const Key('coupon-redirect-title'),
                style: MiftahType.title(color: m.textPrimary),
              ),
              const SizedBox(height: 10),
              Text(
                l.lead(who),
                key: const Key('coupon-redirect-lead'),
                style: MiftahType.body(color: m.textMuted),
              ),
              const SizedBox(height: 6),
              Text(
                l.fieldList(fields),
                key: const Key('coupon-redirect-fields'),
                style: MiftahType.body(
                  color: m.textPrimary,
                ).copyWith(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 22),
              // A Row of Expandeds: the order flips with the app's direction,
              // so Cancel stays on the reading-start side in Arabic as well.
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const Key('coupon-redirect-cancel'),
                      onPressed: () => Navigator.of(context).pop(false),
                      child: Text(l.cancel),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      key: const Key('coupon-redirect-continue'),
                      onPressed: () => Navigator.of(context).pop(true),
                      child: Text(l.continueLabel),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _L {
  const _L(this.ar);
  final bool ar;

  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get continueLabel => ar ? 'متابعة' : 'Continue';
  String get thisBusiness => ar ? 'هذا النشاط التجاري' : 'this business';

  String title(String business) => ar ? 'فتح $business؟' : 'Open $business?';

  String lead(String business) => ar
      ? 'سيتلقى $business البيانات التالية عنك:'
      : '$business will receive your:';

  /// A source name from the backend, in the app's language. Anything this
  /// version does not know maps to one generic phrase, so the renter is still
  /// told that something else is going along.
  String field(String source) {
    switch (source) {
      case 'RENTER_NAME':
        return ar ? 'الاسم' : 'Name';
      case 'RENTER_PHONE':
        return ar ? 'رقم الهاتف' : 'Phone';
      case 'RENTER_EMAIL':
        return ar ? 'البريد الإلكتروني' : 'Email';
      case 'PROPERTY_NAME':
        return ar ? 'العقار' : 'Property';
      case 'UNIT_NUMBER':
        return ar ? 'رقم الوحدة' : 'Unit number';
      default:
        return ar ? 'بيانات أخرى' : 'Other details';
    }
  }

  /// `Name, Phone, Unit number` — each label once even when several unknown
  /// sources collapse onto "Other details" — joined with the language's own
  /// list separator (the Arabic comma is a different character).
  String fieldList(List<String> sources) =>
      sources.map(field).toSet().join(ar ? '، ' : ', ');
}
