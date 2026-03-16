import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _authServiceForProfileProvider = Provider<AuthService>((ref) {
  final client = ref.watch(apiClientProvider);
  return AuthService(client.dio);
});

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
    final changed = _nameController.text != (auth.name ?? '') ||
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
          const SnackBar(
            content: Text('Profile updated'),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Failed to update profile'),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _changePassword() async {
    if (_newPasswordController.text != _confirmPasswordController.text) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Passwords do not match'),
          backgroundColor: AppColors.danger,
        ),
      );
      return;
    }
    if (_newPasswordController.text.length < 6) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Password must be at least 6 characters'),
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
          const SnackBar(
            content: Text('Password changed successfully'),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isChangingPassword = false);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
                'Failed to change password. Check your current password.'),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _logout() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
        title: Text('Logout',
            style: GoogleFonts.cinzel(
              fontWeight: FontWeight.w600,
              fontSize: 18,
            )),
        content: Text('Are you sure you want to logout?',
            style: GoogleFonts.josefinSans()),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text('Cancel',
                style: GoogleFonts.josefinSans(
                    fontWeight: FontWeight.w600)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style:
                TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text('Logout',
                style: GoogleFonts.josefinSans(
                    fontWeight: FontWeight.w600)),
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

    return Scaffold(
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
        children: [
          // Page title
          Text(
            'Profile',
            style: GoogleFonts.cinzel(
              fontSize: 18,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
            ),
          ),
          const SizedBox(height: 16),
          // Avatar with gradient border
          Center(
            child: Column(
              children: [
                Container(
                  width: 96,
                  height: 96,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [AppColors.primary, AppColors.accent],
                    ),
                    boxShadow: [
                      BoxShadow(
                        color:
                            AppColors.primary.withValues(alpha: 0.2),
                        blurRadius: 16,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(3),
                    child: CircleAvatar(
                      radius: 45,
                      backgroundColor: AppColors.surface,
                      child: Text(
                        _getInitials(auth.name ?? 'U'),
                        style: GoogleFonts.cinzel(
                          fontSize: 28,
                          fontWeight: FontWeight.w700,
                          color: AppColors.primary,
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                if (auth.role != null)
                  StatusBadge(
                    label: auth.role!,
                    color: AppColors.primary,
                  ),
              ],
            ),
          ),
          const SizedBox(height: 32),

          // Section label
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 12),
            child: Text(
              'Personal Information',
              style: GoogleFonts.josefinSans(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: AppColors.textMuted,
                letterSpacing: 1.0,
              ),
            ),
          ),

          // Name
          TextFormField(
            controller: _nameController,
            style: GoogleFonts.josefinSans(fontSize: 15),
            decoration: const InputDecoration(
              labelText: 'Name',
              prefixIcon: Icon(Icons.person_outline, size: 20),
            ),
          ),
          const SizedBox(height: 16),

          // Email (read only)
          TextFormField(
            initialValue: auth.email ?? '',
            readOnly: true,
            style: GoogleFonts.josefinSans(
              fontSize: 15,
              color: AppColors.textSecondary,
            ),
            decoration: InputDecoration(
              labelText: 'Email',
              prefixIcon: const Icon(Icons.email_outlined, size: 20),
              filled: true,
              fillColor: AppColors.background,
              suffixIcon: Icon(Icons.lock_outline,
                  size: 16, color: AppColors.textMuted),
            ),
          ),
          const SizedBox(height: 16),

          // Phone
          TextFormField(
            controller: _phoneController,
            keyboardType: TextInputType.phone,
            style: GoogleFonts.josefinSans(fontSize: 15),
            decoration: const InputDecoration(
              labelText: 'Phone Number',
              prefixIcon: Icon(Icons.phone_outlined, size: 20),
              hintText: '+971 XX XXX XXXX',
            ),
          ),
          const SizedBox(height: 22),

          // Save button
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 250),
            transitionBuilder: (child, animation) => SizeTransition(
              sizeFactor: animation,
              child: FadeTransition(opacity: animation, child: child),
            ),
            child: _hasChanges
                ? SizedBox(
                    key: const ValueKey('save_btn'),
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: _isSaving ? null : _saveProfile,
                      child: _isSaving
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2, color: Colors.white),
                            )
                          : const Text('Save Changes'),
                    ),
                  )
                : const SizedBox.shrink(key: ValueKey('no_save')),
          ),
          const SizedBox(height: 28),

          // Section label
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 8),
            child: Text(
              'Security',
              style: GoogleFonts.josefinSans(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: AppColors.textMuted,
                letterSpacing: 1.0,
              ),
            ),
          ),

          // Change Password
          Container(
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(16),
              boxShadow: AppShadows.soft,
            ),
            child: Column(
              children: [
                ListTile(
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 16),
                  leading: const Icon(Icons.lock_outline,
                      color: AppColors.primary),
                  title: Text('Change Password',
                      style: GoogleFonts.josefinSans(
                          fontWeight: FontWeight.w500)),
                  trailing: AnimatedRotation(
                    turns: _showPasswordSection ? 0.5 : 0.0,
                    duration: const Duration(milliseconds: 200),
                    child: const Icon(Icons.keyboard_arrow_down,
                        color: AppColors.textMuted),
                  ),
                  onTap: () => setState(() =>
                      _showPasswordSection = !_showPasswordSection),
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
                          style: GoogleFonts.josefinSans(fontSize: 15),
                          decoration: InputDecoration(
                            labelText: 'Current Password',
                            prefixIcon: const Icon(
                                Icons.lock_outline,
                                size: 20),
                            suffixIcon: IconButton(
                              icon: Icon(
                                _obscureCurrent
                                    ? Icons.visibility_off_outlined
                                    : Icons.visibility_outlined,
                                size: 20,
                              ),
                              onPressed: () => setState(() =>
                                  _obscureCurrent = !_obscureCurrent),
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
                        TextFormField(
                          controller: _newPasswordController,
                          obscureText: _obscureNew,
                          style: GoogleFonts.josefinSans(fontSize: 15),
                          decoration: InputDecoration(
                            labelText: 'New Password',
                            prefixIcon: const Icon(Icons.lock_reset,
                                size: 20),
                            suffixIcon: IconButton(
                              icon: Icon(
                                _obscureNew
                                    ? Icons.visibility_off_outlined
                                    : Icons.visibility_outlined,
                                size: 20,
                              ),
                              onPressed: () => setState(
                                  () => _obscureNew = !_obscureNew),
                            ),
                          ),
                        ),
                        const SizedBox(height: 14),
                        TextFormField(
                          controller: _confirmPasswordController,
                          obscureText: true,
                          style: GoogleFonts.josefinSans(fontSize: 15),
                          decoration: const InputDecoration(
                            labelText: 'Confirm New Password',
                            prefixIcon:
                                Icon(Icons.lock_reset, size: 20),
                          ),
                        ),
                        const SizedBox(height: 18),
                        SizedBox(
                          width: double.infinity,
                          child: OutlinedButton(
                            onPressed: _isChangingPassword
                                ? null
                                : _changePassword,
                            child: _isChangingPassword
                                ? const SizedBox(
                                    width: 18,
                                    height: 18,
                                    child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: AppColors.primary),
                                  )
                                : const Text('Update Password'),
                          ),
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
          ),

          const SizedBox(height: 20),

          // Section label
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 8),
            child: Text(
              'Preferences',
              style: GoogleFonts.josefinSans(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: AppColors.textMuted,
                letterSpacing: 1.0,
              ),
            ),
          ),

          Container(
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(16),
              boxShadow: AppShadows.soft,
            ),
            child: Column(
              children: [
                // Language toggle
                ListTile(
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 16),
                  leading: const Icon(Icons.language,
                      color: AppColors.primary),
                  title: Text('Language',
                      style: GoogleFonts.josefinSans(
                          fontWeight: FontWeight.w500)),
                  trailing: Text(
                    'English',
                    style: GoogleFonts.josefinSans(
                      color: AppColors.textMuted,
                      fontSize: 14,
                    ),
                  ),
                  onTap: () {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                          content:
                              Text('Language selection coming soon')),
                    );
                  },
                ),
                Divider(
                  height: 1,
                  indent: 16,
                  endIndent: 16,
                  color: AppColors.border.withValues(alpha: 0.5),
                ),
                // App version
                ListTile(
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 16),
                  leading: const Icon(Icons.info_outline,
                      color: AppColors.textMuted),
                  title: Text('App Version',
                      style: GoogleFonts.josefinSans(
                          fontWeight: FontWeight.w500)),
                  trailing: Text(
                    '1.0.0',
                    style: GoogleFonts.josefinSans(
                      color: AppColors.textMuted,
                      fontSize: 14,
                    ),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 32),

          // Logout
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _logout,
              icon: const Icon(Icons.logout, color: AppColors.danger),
              label: Text('Logout',
                  style: GoogleFonts.josefinSans(
                      fontWeight: FontWeight.w600)),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.danger,
                side: const BorderSide(color: AppColors.danger),
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
          ),
          const SizedBox(height: 32),
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
