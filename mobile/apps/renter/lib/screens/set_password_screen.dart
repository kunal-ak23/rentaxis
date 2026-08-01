import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class SetPasswordScreen extends ConsumerStatefulWidget {
  final String token;
  const SetPasswordScreen({super.key, required this.token});

  @override
  ConsumerState<SetPasswordScreen> createState() => _SetPasswordScreenState();
}

class _SetPasswordScreenState extends ConsumerState<SetPasswordScreen> {
  String? _email;
  String? _name;
  String? _error;
  bool _loadingValidation = true;
  bool _submitting = false;
  final _passwordCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _validate();
  }

  @override
  void dispose() {
    _passwordCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _validate() async {
    final l = _L(context.isAr);
    if (widget.token.isEmpty) {
      setState(() {
        _error = l.noToken;
        _loadingValidation = false;
      });
      return;
    }
    try {
      final info = await ref
          .read(authServiceProvider)
          .validateInviteToken(widget.token);
      if (!mounted) return;
      if (info == null) {
        setState(() {
          _error = l.inviteInvalid;
          _loadingValidation = false;
        });
        return;
      }
      setState(() {
        _email = info['email'] as String?;
        _name = info['name'] as String?;
        _loadingValidation = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = l.inviteInvalidOrExpired;
        _loadingValidation = false;
      });
    }
  }

  Future<void> _submit() async {
    final l = _L(context.isAr);
    if (_passwordCtrl.text.length < 8) {
      setState(() => _error = l.passwordTooShort);
      return;
    }
    if (_passwordCtrl.text != _confirmCtrl.text) {
      setState(() => _error = l.passwordsDontMatch);
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final status = await ref
          .read(authServiceProvider)
          .acceptInvite(token: widget.token, newPassword: _passwordCtrl.text);
      if (!mounted) return;
      if (status == 204) {
        GoRouter.of(context).go('/login');
      } else if (status == 410) {
        setState(() => _error = l.inviteExpired);
      } else if (status == 409) {
        setState(() => _error = l.inviteAlreadyUsed);
      } else if (status == 400) {
        setState(() => _error = l.passwordInvalid);
      } else {
        setState(() => _error = l.couldNotSetPassword);
      }
    } catch (e) {
      if (mounted) setState(() => _error = l.networkError);
    }
    if (mounted) setState(() => _submitting = false);
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    if (_loadingValidation) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_email == null) {
      return Scaffold(
        appBar: AppBar(title: Text(l.inviteInvalidTitle)),
        body: Padding(
          padding: const EdgeInsets.all(24),
          child: Center(child: Text(_error ?? l.inviteInvalidGeneric)),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(title: Text(l.setYourPassword)),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: ListView(
          children: [
            Text(
              l.welcome(_name ?? ''),
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(l.setPasswordFor(_email!)),
            const SizedBox(height: 24),
            TextField(
              controller: _passwordCtrl,
              obscureText: true,
              decoration: InputDecoration(
                labelText: l.newPassword,
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _confirmCtrl,
              obscureText: true,
              decoration: InputDecoration(
                labelText: l.confirmPassword,
                border: const OutlineInputBorder(),
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  _error!,
                  style: TextStyle(color: context.miftah.danger),
                ),
              ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: _submitting ? null : _submit,
              child: Text(_submitting ? l.setting : l.setPassword),
            ),
          ],
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get noToken =>
      ar ? 'لم يتم توفير رمز الدعوة.' : 'No invite token provided.';
  String get inviteInvalid =>
      ar ? 'رابط الدعوة غير صالح.' : 'This invite link is invalid.';
  String get inviteInvalidOrExpired => ar
      ? 'رابط الدعوة غير صالح أو منتهي الصلاحية.'
      : 'This invite link is invalid or has expired.';
  String get passwordTooShort => ar
      ? 'يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.'
      : 'Password must be at least 8 characters.';
  String get passwordsDontMatch =>
      ar ? 'كلمتا المرور غير متطابقتين.' : "Passwords don't match.";
  String get inviteExpired =>
      ar ? 'انتهت صلاحية رابط الدعوة.' : 'This invite link has expired.';
  String get inviteAlreadyUsed => ar
      ? 'تم استخدام هذه الدعوة بالفعل. يرجى تسجيل الدخول.'
      : 'This invite has already been used. Please log in.';
  String get passwordInvalid => ar
      ? 'كلمة المرور قصيرة جدًا أو غير صالحة.'
      : 'Password too short or invalid.';
  String get couldNotSetPassword => ar
      ? 'تعذر تعيين كلمة المرور. حاول مرة أخرى.'
      : 'Could not set password. Please try again.';
  String get networkError =>
      ar ? 'خطأ في الشبكة. حاول مرة أخرى.' : 'Network error. Please try again.';
  String get inviteInvalidTitle => ar ? 'الدعوة غير صالحة' : 'Invite invalid';
  String get inviteInvalidGeneric => ar ? 'دعوة غير صالحة.' : 'Invalid invite.';
  String get setYourPassword => ar ? 'تعيين كلمة المرور' : 'Set your password';
  String get newPassword => ar ? 'كلمة مرور جديدة' : 'New password';
  String get confirmPassword => ar ? 'تأكيد كلمة المرور' : 'Confirm password';
  String get setting => ar ? 'جارٍ التعيين…' : 'Setting…';
  String get setPassword => ar ? 'تعيين كلمة المرور' : 'Set password';

  String welcome(String name) => ar ? 'مرحباً $name' : 'Welcome $name';
  String setPasswordFor(String email) =>
      ar ? 'عيّن كلمة مرور لـ $email' : 'Set a password for $email';
}
