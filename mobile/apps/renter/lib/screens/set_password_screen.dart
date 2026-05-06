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
    if (widget.token.isEmpty) {
      setState(() {
        _error = 'No invite token provided.';
        _loadingValidation = false;
      });
      return;
    }
    try {
      final info =
          await ref.read(authServiceProvider).validateInviteToken(widget.token);
      if (!mounted) return;
      if (info == null) {
        setState(() {
          _error = 'This invite link is invalid.';
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
        _error = 'This invite link is invalid or has expired.';
        _loadingValidation = false;
      });
    }
  }

  Future<void> _submit() async {
    if (_passwordCtrl.text.length < 8) {
      setState(() => _error = 'Password must be at least 8 characters.');
      return;
    }
    if (_passwordCtrl.text != _confirmCtrl.text) {
      setState(() => _error = "Passwords don't match.");
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final status = await ref.read(authServiceProvider).acceptInvite(
            token: widget.token,
            newPassword: _passwordCtrl.text,
          );
      if (!mounted) return;
      if (status == 204) {
        GoRouter.of(context).go('/login');
      } else if (status == 410) {
        setState(() => _error = 'This invite link has expired.');
      } else if (status == 409) {
        setState(
            () => _error = 'This invite has already been used. Please log in.');
      } else if (status == 400) {
        setState(() => _error = 'Password too short or invalid.');
      } else {
        setState(() => _error = 'Could not set password. Please try again.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = 'Network error. Please try again.');
    }
    if (mounted) setState(() => _submitting = false);
  }

  @override
  Widget build(BuildContext context) {
    if (_loadingValidation) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_email == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Invite invalid')),
        body: Padding(
          padding: const EdgeInsets.all(24),
          child: Center(child: Text(_error ?? 'Invalid invite.')),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(title: const Text('Set your password')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: ListView(
          children: [
            Text(
              'Welcome ${_name ?? ''}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text('Set a password for $_email'),
            const SizedBox(height: 24),
            TextField(
              controller: _passwordCtrl,
              obscureText: true,
              decoration: const InputDecoration(
                labelText: 'New password',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _confirmCtrl,
              obscureText: true,
              decoration: const InputDecoration(
                labelText: 'Confirm password',
                border: OutlineInputBorder(),
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child:
                    Text(_error!, style: const TextStyle(color: Colors.red)),
              ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: _submitting ? null : _submit,
              child: Text(_submitting ? 'Setting…' : 'Set password'),
            ),
          ],
        ),
      ),
    );
  }
}
