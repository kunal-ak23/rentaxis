import 'package:flutter/material.dart';

/// Public "forgot password" for both apps' sign-in screens. Asks for the email,
/// calls [send] (normally `AuthService.requestPasswordReset`) and then says the
/// one thing the server allows: if an account exists, a link was sent. The link
/// opens the web page that sets the new password, which then works in every
/// organisation the person belongs to.
Future<void> showForgotPasswordSheet(
  BuildContext context, {
  required Future<int> Function(String email) send,
  required bool ar,
  String initialEmail = '',
}) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: ForgotPasswordSheet(send: send, ar: ar, initialEmail: initialEmail),
    ),
  );
}

class ForgotPasswordSheet extends StatefulWidget {
  const ForgotPasswordSheet({
    super.key,
    required this.send,
    required this.ar,
    this.initialEmail = '',
  });

  final Future<int> Function(String email) send;
  final bool ar;
  final String initialEmail;

  @override
  State<ForgotPasswordSheet> createState() => _ForgotPasswordSheetState();
}

class _ForgotPasswordSheetState extends State<ForgotPasswordSheet> {
  late final TextEditingController _email =
      TextEditingController(text: widget.initialEmail);
  bool _sending = false;
  bool _sent = false;
  String? _error;

  bool get ar => widget.ar;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final email = _email.text.trim();
    if (!email.contains('@')) {
      setState(() => _error = ar
          ? 'أدخل بريدًا إلكترونيًا صالحًا'
          : 'Enter a valid email address');
      return;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      final status = await widget.send(email);
      if (!mounted) return;
      setState(() {
        _sending = false;
        if (status == 429) {
          _error = ar
              ? 'طلبات كثيرة جدًا. يرجى الانتظار دقيقة ثم المحاولة مرة أخرى.'
              : 'Too many requests. Please wait a minute and try again.';
        } else if (status >= 200 && status < 300) {
          _sent = true;
        } else {
          _error = ar
              ? 'حدث خطأ ما. يرجى المحاولة لاحقًا.'
              : 'Something went wrong. Please try again later.';
        }
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _error = ar
            ? 'خطأ في الشبكة. يرجى المحاولة مرة أخرى.'
            : 'Network error. Please try again.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              ar ? 'نسيت كلمة المرور؟' : 'Forgot your password?',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            if (_sent)
              Text(
                ar
                    ? 'إذا كان هناك حساب لهذا البريد الإلكتروني، فقد أرسلنا رابطًا لإعادة تعيين كلمة المرور. تحقق من بريدك الوارد.'
                    : "If an account exists for this email, we've sent a link to reset the password. Check your inbox.",
                key: const ValueKey('forgot-sent'),
                style: theme.textTheme.bodyMedium,
              )
            else ...[
              Text(
                ar
                    ? 'أدخل البريد الإلكتروني الذي تسجّل الدخول به. إذا كان له حساب في مفتاح، فسنرسل إليه رابطًا لاختيار كلمة مرور جديدة. يعمل الرابط مرة واحدة ولمدة 45 دقيقة.'
                    : "Enter the email you sign in with. If it has a Miftah account, we'll email a link to choose a new password. The link works once, for 45 minutes.",
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('forgot-email'),
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
                decoration: InputDecoration(
                  labelText: ar ? 'البريد الإلكتروني' : 'Email',
                ),
                onSubmitted: (_) => _submit(),
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(
                  _error!,
                  key: const ValueKey('forgot-error'),
                  style: TextStyle(color: theme.colorScheme.error),
                ),
              ],
              const SizedBox(height: 16),
              FilledButton(
                key: const ValueKey('forgot-send'),
                onPressed: _sending ? null : _submit,
                child: _sending
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(ar ? 'إرسال الرابط' : 'Send link'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
