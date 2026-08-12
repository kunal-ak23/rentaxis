import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _authServiceForProfileProvider = Provider<AuthService>((ref) {
  final client = ref.watch(apiClientProvider);
  return AuthService(client.dio);
});

/// True when [error] is the backend's 400 for a wrong current password —
/// PUT /auth/me/password answers `{"error": "Current password is incorrect"}`
/// (AuthController). Lets the snackbar name the actual problem bilingually
/// instead of the blanket "failed" message.
///
/// Top-level (not a State method) so tests can pin the mapping directly.
/// Mirrors the manager app's profile_screen copy.
bool isWrongCurrentPasswordError(Object error) {
  if (error is! DioException || error.response?.statusCode != 400) {
    return false;
  }
  final data = error.response?.data;
  final message = data is Map ? data['error']?.toString() : null;
  return message != null && message.toLowerCase().contains('current password');
}

// ---------------------------------------------------------------------------
// Fonts helper — Arabic uses Noto Naskh instead of Cinzel/Josefin Sans, and
// never carries the EN tracked-uppercase letterSpacing (breaks glyph joining).
// ---------------------------------------------------------------------------

TextStyle _display(
  bool ar, {
  double size = 16,
  FontWeight weight = FontWeight.w600,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.cinzel(fontSize: size, fontWeight: weight, color: color);

TextStyle _body(
  bool ar, {
  double size = 14,
  FontWeight weight = FontWeight.w400,
  Color? color,
  double letterSpacing = 0,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.josefinSans(
        fontSize: size,
        fontWeight: weight,
        color: color,
        letterSpacing: letterSpacing,
      );

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});

  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  final _nameController = TextEditingController();
  final _phoneController = TextEditingController();
  bool _isSaving = false;
  bool _hasChanges = false;

  // Change password
  bool _showPasswordSection = false;
  final _currentPasswordController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  bool _isChangingPassword = false;
  bool _obscureCurrent = true;
  bool _obscureNew = true;

  @override
  void initState() {
    super.initState();
    _loadProfile();
  }

  void _loadProfile() {
    final auth = ref.read(authProvider);
    _nameController.text = auth.name ?? '';
    _nameController.addListener(_onFieldChanged);
    _phoneController.addListener(_onFieldChanged);
  }

  void _onFieldChanged() {
    final auth = ref.read(authProvider);
    final changed =
        _nameController.text != (auth.name ?? '') ||
        _phoneController.text.isNotEmpty;
    if (changed != _hasChanges) {
      setState(() => _hasChanges = changed);
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _phoneController.dispose();
    _currentPasswordController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  Future<void> _saveProfile() async {
    final l = _L(context.isAr);
    setState(() => _isSaving = true);
    try {
      final service = ref.read(_authServiceForProfileProvider);
      await service.updateProfile(
        name: _nameController.text.trim(),
        phoneNumber: _phoneController.text.trim().isNotEmpty
            ? _phoneController.text.trim()
            : null,
      );
      if (mounted) {
        setState(() {
          _hasChanges = false;
          _isSaving = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.profileUpdated),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.profileUpdateFailed),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _changePassword() async {
    final l = _L(context.isAr);
    if (_newPasswordController.text != _confirmPasswordController.text) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.passwordsDontMatch),
          backgroundColor: AppColors.danger,
        ),
      );
      return;
    }
    if (_newPasswordController.text.length < 6) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.passwordTooShort),
          backgroundColor: AppColors.danger,
        ),
      );
      return;
    }

    setState(() => _isChangingPassword = true);
    try {
      final service = ref.read(_authServiceForProfileProvider);
      await service.changePassword(
        _currentPasswordController.text,
        _newPasswordController.text,
      );
      if (mounted) {
        _currentPasswordController.clear();
        _newPasswordController.clear();
        _confirmPasswordController.clear();
        setState(() {
          _isChangingPassword = false;
          _showPasswordSection = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.passwordChanged),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isChangingPassword = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              isWrongCurrentPasswordError(e)
                  ? l.currentPasswordIncorrect
                  : errorMessage(e, l.passwordChangeFailed),
            ),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _logout() async {
    final l = _L(context.isAr);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          l.signOut,
          style: _display(l.ar, size: 18, weight: FontWeight.w600),
        ),
        content: Text(l.signOutConfirm, style: _body(l.ar)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text(l.signOut, style: _body(l.ar, weight: FontWeight.w600)),
          ),
        ],
      ),
    );

    if (confirm == true) {
      await ref.read(authProvider.notifier).logout();
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);
    final l = _L(context.isAr);

    return Scaffold(
      body: ListView(
        padding: EdgeInsets.fromLTRB(0, 0, 0, 24),
        children: [
          _buildChromeHeader(auth, l),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildAppearanceCard(l),
                const SizedBox(height: 14),
                _sectionLabel(l.personalInformation, l.ar),
                _buildPersonalInfoCard(auth, l),
                const SizedBox(height: 14),
                _sectionLabel(l.security, l.ar),
                _buildPasswordCard(l),
                const SizedBox(height: 14),
                _sectionLabel(l.preferences, l.ar),
                _buildPreferencesCard(l),
                const SizedBox(height: 26),
                _buildSignOut(l),
                const SizedBox(height: 30),
                _buildBrandFooter(l),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Dark chrome header: gold-ringed monogram, name, tenancy line — always
  /// near-black regardless of theme mode, matching the app chrome.
  Widget _buildChromeHeader(AuthState auth, _L l) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(24, 28, 24, 28),
      child: Column(
        children: [
          Container(
            width: 80,
            height: 80,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.primary,
              border: Border.all(
                color: AppColors.accent.withValues(alpha: 0.45),
              ),
            ),
            alignment: Alignment.center,
            child: Text(
              _getInitials(auth.name ?? 'U'),
              style: GoogleFonts.cinzel(
                fontSize: 26,
                fontWeight: FontWeight.w600,
                color: AppColors.accent,
              ),
            ),
          ),
          const SizedBox(height: 14),
          Text(
            auth.name ?? l.resident,
            style: _display(
              l.ar,
              size: 20,
              weight: FontWeight.w600,
              color: Colors.white,
            ),
          ),
          if (auth.email != null) ...[
            const SizedBox(height: 6),
            Text(
              auth.email!,
              style: _body(
                l.ar,
                size: 12,
                color: Colors.white.withValues(alpha: 0.5),
              ),
            ),
          ],
          if (auth.role != null) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(999),
                border: Border.all(
                  color: AppColors.accent.withValues(alpha: 0.3),
                ),
              ),
              child: Text(
                l.roleLabel(auth.role!),
                style: _body(l.ar, size: 10.5, color: AppColors.accent),
              ),
            ),
          ],
          // Language pill in the header, per design 1h.
          const SizedBox(height: 14),
          _LanguageToggle(m: context.miftah, onDark: true),
        ],
      ),
    );
  }

  Widget _sectionLabel(String text, bool ar) {
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 4, bottom: 10),
      child: Text(
        text.toUpperCase(),
        style: _body(
          ar,
          size: 11,
          weight: FontWeight.w500,
          color: m.textMuted,
          letterSpacing: 2.0,
        ),
      ),
    );
  }

  BoxDecoration _cardDecoration() {
    final m = context.miftah;
    return BoxDecoration(
      color: m.surface,
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: m.border),
    );
  }

  /// LIGHT / DARK / SYSTEM segmented selector, per design 2d.
  Widget _buildAppearanceCard(_L l) {
    final m = context.miftah;
    final mode = ref.watch(themeModeProvider);

    Widget option(String label, ThemeMode value) {
      final selected = mode == value;
      return Expanded(
        child: GestureDetector(
          onTap: () => ref.read(themeModeProvider.notifier).setMode(value),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            height: 34,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(8),
              color: selected
                  ? AppColors.accent.withValues(alpha: 0.12)
                  : Colors.transparent,
              border: Border.all(
                color: selected ? AppColors.accent : m.borderStrong,
                width: selected ? 1.2 : 1,
              ),
            ),
            alignment: Alignment.center,
            child: Text(
              label,
              style: _body(
                l.ar,
                size: 11.5,
                weight: selected ? FontWeight.w600 : FontWeight.w400,
                color: selected
                    ? (m.isDark ? AppColors.accent : AppColors.accentDark)
                    : m.textSecondary,
                letterSpacing: 1.6,
              ),
            ),
          ),
        ),
      );
    }

    return Container(
      decoration: _cardDecoration(),
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.appearance,
            style: _body(
              l.ar,
              size: 11,
              color: m.textMuted,
              letterSpacing: 2.0,
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              option(l.light, ThemeMode.light),
              const SizedBox(width: 8),
              option(l.dark, ThemeMode.dark),
              const SizedBox(width: 8),
              option(l.system, ThemeMode.system),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildPersonalInfoCard(AuthState auth, _L l) {
    final m = context.miftah;
    return Container(
      decoration: _cardDecoration(),
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          TextFormField(
            controller: _nameController,
            style: _body(l.ar, size: 15),
            decoration: InputDecoration(
              labelText: l.name,
              prefixIcon: const Icon(Icons.person_outline, size: 20),
            ),
          ),
          const SizedBox(height: 14),
          TextFormField(
            initialValue: auth.email ?? '',
            readOnly: true,
            style: _body(l.ar, size: 15, color: m.textSecondary),
            decoration: InputDecoration(
              labelText: l.email,
              prefixIcon: const Icon(Icons.email_outlined, size: 20),
              filled: true,
              fillColor: m.background,
              suffixIcon: Icon(
                Icons.lock_outline,
                size: 16,
                color: m.textMuted,
              ),
            ),
          ),
          const SizedBox(height: 14),
          TextFormField(
            controller: _phoneController,
            keyboardType: TextInputType.phone,
            style: _body(l.ar, size: 15),
            decoration: InputDecoration(
              labelText: l.phoneNumber,
              prefixIcon: const Icon(Icons.phone_outlined, size: 20),
              hintText: '+971 XX XXX XXXX',
            ),
          ),
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 250),
            transitionBuilder: (child, animation) => SizeTransition(
              sizeFactor: animation,
              child: FadeTransition(opacity: animation, child: child),
            ),
            child: _hasChanges
                ? Padding(
                    key: const ValueKey('save_btn'),
                    padding: const EdgeInsets.only(top: 18),
                    child: _isSaving
                        ? const Center(
                            child: SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        : GoldButton(
                            label: l.saveChanges,
                            height: 46,
                            onPressed: _saveProfile,
                          ),
                  )
                : const SizedBox.shrink(key: ValueKey('no_save')),
          ),
        ],
      ),
    );
  }

  Widget _buildPasswordCard(_L l) {
    final m = context.miftah;
    return Container(
      decoration: _cardDecoration(),
      child: Column(
        children: [
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            leading: Icon(
              Icons.lock_outline,
              color: m.isDark ? AppColors.accent : AppColors.primary,
            ),
            title: Text(
              l.changePassword,
              style: _body(l.ar, weight: FontWeight.w500),
            ),
            trailing: AnimatedRotation(
              turns: _showPasswordSection ? 0.5 : 0.0,
              duration: const Duration(milliseconds: 200),
              child: Icon(Icons.keyboard_arrow_down, color: m.textMuted),
            ),
            onTap: () =>
                setState(() => _showPasswordSection = !_showPasswordSection),
          ),
          AnimatedCrossFade(
            firstChild: const SizedBox.shrink(),
            secondChild: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Column(
                children: [
                  const SizedBox(height: 8),
                  TextFormField(
                    controller: _currentPasswordController,
                    obscureText: _obscureCurrent,
                    style: _body(l.ar, size: 15),
                    decoration: InputDecoration(
                      labelText: l.currentPassword,
                      prefixIcon: const Icon(Icons.lock_outline, size: 20),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscureCurrent
                              ? Icons.visibility_off_outlined
                              : Icons.visibility_outlined,
                          size: 20,
                        ),
                        onPressed: () =>
                            setState(() => _obscureCurrent = !_obscureCurrent),
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  TextFormField(
                    controller: _newPasswordController,
                    obscureText: _obscureNew,
                    style: _body(l.ar, size: 15),
                    decoration: InputDecoration(
                      labelText: l.newPassword,
                      prefixIcon: const Icon(Icons.lock_reset, size: 20),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _obscureNew
                              ? Icons.visibility_off_outlined
                              : Icons.visibility_outlined,
                          size: 20,
                        ),
                        onPressed: () =>
                            setState(() => _obscureNew = !_obscureNew),
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  TextFormField(
                    controller: _confirmPasswordController,
                    obscureText: true,
                    style: _body(l.ar, size: 15),
                    decoration: InputDecoration(
                      labelText: l.confirmNewPassword,
                      prefixIcon: const Icon(Icons.lock_reset, size: 20),
                    ),
                  ),
                  const SizedBox(height: 18),
                  _isChangingPassword
                      ? const Center(
                          child: SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : GoldButton.outlined(
                          label: l.updatePassword,
                          height: 46,
                          onPressed: _changePassword,
                        ),
                ],
              ),
            ),
            crossFadeState: _showPasswordSection
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            duration: const Duration(milliseconds: 250),
          ),
        ],
      ),
    );
  }

  Widget _buildPreferencesCard(_L l) {
    final m = context.miftah;
    return Container(
      decoration: _cardDecoration(),
      child: Column(
        children: [
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            leading: Icon(
              Icons.language,
              color: m.isDark ? AppColors.accent : AppColors.primary,
            ),
            title: Text(
              l.language,
              style: _body(l.ar, weight: FontWeight.w500),
            ),
            trailing: _LanguageToggle(m: m),
          ),
          Divider(height: 1, indent: 16, endIndent: 16, color: m.divider),
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 16),
            leading: Icon(Icons.info_outline, color: m.textMuted),
            title: Text(
              l.appVersion,
              style: _body(l.ar, weight: FontWeight.w500),
            ),
            trailing: Text(
              '1.1.0',
              style: _body(l.ar, size: 14, color: m.textMuted),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSignOut(_L l) {
    final m = context.miftah;
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton(
        onPressed: _logout,
        style: OutlinedButton.styleFrom(
          foregroundColor: m.danger,
          side: BorderSide(color: m.danger.withValues(alpha: 0.5)),
          padding: const EdgeInsets.symmetric(vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
        child: Text(
          l.signOutUpper,
          style: _body(
            l.ar,
            weight: FontWeight.w600,
            size: 12.5,
            letterSpacing: 2.4,
          ),
        ),
      ),
    );
  }

  Widget _buildBrandFooter(_L l) {
    final m = context.miftah;
    return Center(
      child: Column(
        children: [
          Opacity(
            opacity: 0.35,
            child: Image.asset(
              'assets/logo_horizontal.png',
              height: 22,
              fit: BoxFit.contain,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            l.versionFooter,
            style: _body(
              l.ar,
              size: 10.5,
              color: m.textMuted,
              letterSpacing: 2.0,
            ),
          ),
        ],
      ),
    );
  }

  String _getInitials(String name) {
    final parts = name.trim().split(' ');
    if (parts.length >= 2) {
      return '${parts[0][0]}${parts[1][0]}'.toUpperCase();
    }
    return name.isNotEmpty ? name[0].toUpperCase() : 'U';
  }
}

/// EN / عربي pill toggle per design 1h. Today it switches the brand lockup
/// (Arabic-first chrome); it becomes the full locale switch once translated
/// strings ship.
class _LanguageToggle extends ConsumerWidget {
  final LegacyMiftahColors m;

  /// On the dark chrome header, unselected labels read white instead of muted.
  final bool onDark;
  const _LanguageToggle({required this.m, this.onDark = false});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final language = ref.watch(appLanguageProvider);
    final unselected = onDark
        ? Colors.white.withValues(alpha: 0.55)
        : m.textMuted;

    Widget option({
      required bool selected,
      required VoidCallback onTap,
      required Widget child,
    }) {
      return GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(999),
            color: selected ? AppColors.accent : Colors.transparent,
          ),
          child: child,
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          option(
            selected: language == AppLanguage.en,
            onTap: () => ref
                .read(appLanguageProvider.notifier)
                .setLanguage(AppLanguage.en),
            child: Text(
              'EN',
              style: GoogleFonts.josefinSans(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.4,
                color: language == AppLanguage.en
                    ? AppColors.primary
                    : unselected,
              ),
            ),
          ),
          option(
            selected: language == AppLanguage.ar,
            onTap: () => ref
                .read(appLanguageProvider.notifier)
                .setLanguage(AppLanguage.ar),
            child: Text(
              'عربي',
              style: GoogleFonts.notoNaskhArabic(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: language == AppLanguage.ar
                    ? AppColors.primary
                    : unselected,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get resident => ar ? 'مستأجر' : 'Resident';
  String get personalInformation =>
      ar ? 'المعلومات الشخصية' : 'Personal information';
  String get security => ar ? 'الأمان' : 'Security';
  String get preferences => ar ? 'التفضيلات' : 'Preferences';
  String get appearance => ar ? 'المظهر' : 'APPEARANCE';
  String get light => ar ? 'فاتح' : 'LIGHT';
  String get dark => ar ? 'داكن' : 'DARK';
  String get system => ar ? 'تلقائي' : 'SYSTEM';
  String get name => ar ? 'الاسم' : 'Name';
  String get email => ar ? 'البريد الإلكتروني' : 'Email';
  String get phoneNumber => ar ? 'رقم الهاتف' : 'Phone Number';
  String get saveChanges => ar ? 'حفظ التغييرات' : 'Save changes';
  String get changePassword => ar ? 'تغيير كلمة المرور' : 'Change password';
  String get currentPassword => ar ? 'كلمة المرور الحالية' : 'Current Password';
  String get newPassword => ar ? 'كلمة مرور جديدة' : 'New Password';
  String get confirmNewPassword =>
      ar ? 'تأكيد كلمة المرور الجديدة' : 'Confirm New Password';
  String get updatePassword => ar ? 'تحديث كلمة المرور' : 'Update password';
  String get language => ar ? 'اللغة' : 'Language';
  String get appVersion => ar ? 'إصدار التطبيق' : 'App Version';
  String get signOut => ar ? 'تسجيل الخروج' : 'Sign out';
  String get signOutUpper => ar ? 'تسجيل الخروج' : 'SIGN OUT';
  String get signOutConfirm => ar
      ? 'هل أنت متأكد أنك تريد تسجيل الخروج؟'
      : 'Are you sure you want to sign out?';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get versionFooter => ar ? 'الإصدار 1.1.0' : 'VERSION 1.1.0';
  String get profileUpdated => ar ? 'تم تحديث الملف الشخصي' : 'Profile updated';
  String get profileUpdateFailed =>
      ar ? 'فشل تحديث الملف الشخصي' : 'Failed to update profile';
  String get passwordsDontMatch =>
      ar ? 'كلمتا المرور غير متطابقتين' : 'Passwords do not match';
  String get passwordTooShort => ar
      ? 'يجب أن تتكون كلمة المرور من 6 أحرف على الأقل'
      : 'Password must be at least 6 characters';
  String get passwordChanged =>
      ar ? 'تم تغيير كلمة المرور بنجاح' : 'Password changed successfully';
  String get passwordChangeFailed => ar
      ? 'فشل تغيير كلمة المرور. تحقق من كلمة المرور الحالية.'
      : 'Failed to change password. Check your current password.';
  String get currentPasswordIncorrect =>
      ar ? 'كلمة المرور الحالية غير صحيحة' : 'Current password is incorrect';

  String roleLabel(String role) {
    switch (role.toUpperCase()) {
      case 'RENTER':
        return ar ? 'مستأجر' : 'RENTER';
      default:
        return role.toUpperCase();
    }
  }
}
