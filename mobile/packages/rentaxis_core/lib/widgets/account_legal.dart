import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:url_launcher/url_launcher.dart';

import '../providers/auth_provider.dart';
import '../theme/app_theme.dart';
import '../utils/l10n.dart';

/// The public legal pages every app must link to, hosted with the web app.
enum MiftahLegalPage { privacy, terms, dataDeletion }

const _legalBase = 'https://rentaxis.uaenorth.cloudapp.azure.com';

/// The locale-specific URL for a legal page. Arabic users land on the Arabic
/// route rather than an English page they may not read.
String miftahLegalUrl(MiftahLegalPage page, {required bool arabic}) {
  final slug = switch (page) {
    MiftahLegalPage.privacy => 'privacy',
    MiftahLegalPage.terms => 'terms',
    MiftahLegalPage.dataDeletion => 'data-deletion',
  };
  return '$_legalBase/${arabic ? 'ar' : 'en'}/$slug';
}

/// Opens a URL. Injectable so widget tests can assert the link without a
/// platform channel.
typedef LegalUrlLauncher = Future<void> Function(String url);

Future<void> launchLegalUrl(String url) async {
  final uri = Uri.tryParse(url);
  if (uri == null) return;
  if (await canLaunchUrl(uri)) {
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }
}

TextStyle _rowStyle(bool ar, {Color? color, FontWeight weight = FontWeight.w500}) =>
    ar
        ? GoogleFonts.notoNaskhArabic(fontSize: 15, fontWeight: weight, color: color)
        : GoogleFonts.plusJakartaSans(fontSize: 14, fontWeight: weight, color: color);

/// Privacy Policy / Terms of Use / Account & data deletion rows. Drop it into
/// any settings card; it only draws the rows, the host supplies the card.
class LegalLinksList extends StatelessWidget {
  const LegalLinksList({super.key, LegalUrlLauncher? launcher, this.dense = false})
    : _launch = launcher ?? launchLegalUrl;

  final LegalUrlLauncher _launch;

  /// Tighter rows for bottom sheets.
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final m = context.miftah;
    final accent = m.isDark ? AppColors.accent : AppColors.primary;

    Widget row(IconData icon, String label, MiftahLegalPage page) {
      return ListTile(
        dense: dense,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16),
        leading: Icon(icon, color: accent),
        title: Text(label, style: _rowStyle(ar)),
        trailing: Icon(Icons.open_in_new, size: 16, color: m.textMuted),
        onTap: () => _launch(miftahLegalUrl(page, arabic: ar)),
      );
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        row(
          Icons.privacy_tip_outlined,
          ar ? 'سياسة الخصوصية' : 'Privacy Policy',
          MiftahLegalPage.privacy,
        ),
        Divider(height: 1, indent: 16, endIndent: 16, color: m.divider),
        row(
          Icons.description_outlined,
          ar ? 'شروط الاستخدام' : 'Terms of Use',
          MiftahLegalPage.terms,
        ),
        Divider(height: 1, indent: 16, endIndent: 16, color: m.divider),
        row(
          Icons.delete_outline,
          ar ? 'حذف الحساب والبيانات' : 'Account & data deletion',
          MiftahLegalPage.dataDeletion,
        ),
      ],
    );
  }
}

/// The in-app account deletion App Store Review Guideline 5.1.1(v) requires.
///
/// Two steps — a dialog that says plainly what goes and what the property
/// organisation keeps, then the call — and no typed confirmation, which Apple
/// does not ask for and which only punishes the people who mean it.
///
/// [onDeleted] runs after the server confirmed the deletion and the local
/// session was cleared; the Security app signs out of Firebase there. It runs
/// even if this widget has already been unmounted by the sign-out redirect.
class DeleteAccountButton extends ConsumerStatefulWidget {
  const DeleteAccountButton({super.key, this.onDeleted});

  final Future<void> Function()? onDeleted;

  @override
  ConsumerState<DeleteAccountButton> createState() => _DeleteAccountButtonState();
}

class _DeleteAccountButtonState extends ConsumerState<DeleteAccountButton> {
  bool _busy = false;

  Future<void> _confirmAndDelete() async {
    final ar = context.isAr;
    final m = context.miftah;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          ar ? 'حذف الحساب' : 'Delete account',
          style: _rowStyle(ar, weight: FontWeight.w600),
        ),
        content: Text(
          ar
              ? 'سيؤدي حذف حسابك إلى إزالة بيانات تسجيل الدخول وملفك الشخصي من '
                    'مفتاح وتسجيل خروجك من جميع الأجهزة. تحتفظ جهة إدارة العقار '
                    'بسجلات الإيجار والدفع والدخول وفق ما يقتضيه القانون واتفاقها '
                    'معك. لا يمكن التراجع عن هذا الإجراء.'
              : 'Deleting your account removes your sign-in and personal profile '
                    'from Miftah and signs you out on all devices. Tenancy, '
                    'payment and access records held by your property '
                    'organisation are kept as required by law and its agreement '
                    'with you. This cannot be undone.',
          style: _rowStyle(ar, weight: FontWeight.w400),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(ar ? 'إلغاء' : 'Cancel', style: _rowStyle(ar, weight: FontWeight.w600)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: m.danger),
            child: Text(
              ar ? 'حذف' : 'Delete',
              style: _rowStyle(ar, weight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _busy = true);
    final error = await ref.read(authProvider.notifier).deleteAccount();
    if (error == null) {
      // Deliberately before the mounted check: on success the sign-out
      // redirect usually unmounts this widget, and the host's cleanup (e.g.
      // Firebase sign-out) must still run.
      await widget.onDeleted?.call();
    }
    if (!mounted) return;
    setState(() => _busy = false);
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final m = context.miftah;
    return SizedBox(
      width: double.infinity,
      child: TextButton.icon(
        onPressed: _busy ? null : _confirmAndDelete,
        icon: _busy
            ? SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2, color: m.danger),
              )
            : Icon(Icons.delete_forever_outlined, size: 18, color: m.danger),
        style: TextButton.styleFrom(
          foregroundColor: m.danger,
          padding: const EdgeInsets.symmetric(vertical: 12),
        ),
        label: Text(
          ar ? 'حذف الحساب' : 'Delete account',
          style: _rowStyle(ar, color: m.danger, weight: FontWeight.w600),
        ),
      ),
    );
  }
}
