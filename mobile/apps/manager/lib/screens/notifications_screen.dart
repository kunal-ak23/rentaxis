import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Notifications, mirrors the renter notifications pattern: dark chrome
/// app bar, All/Unread tabs, semantic icon chips per notification type.
class NotificationsScreen extends ConsumerStatefulWidget {
  const NotificationsScreen({super.key});

  @override
  ConsumerState<NotificationsScreen> createState() =>
      _NotificationsScreenState();
}

class _NotificationsScreenState extends ConsumerState<NotificationsScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  bool _isLoadingMore = false;
  int _currentPage = 0;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(notificationProvider.notifier).fetchNotifications();
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore) return;
    setState(() => _isLoadingMore = true);
    _currentPage++;
    await ref
        .read(notificationProvider.notifier)
        .fetchNotifications(page: _currentPage);
    if (mounted) setState(() => _isLoadingMore = false);
  }

  Future<void> _refresh() async {
    _currentPage = 0;
    await ref.read(notificationProvider.notifier).fetchNotifications(page: 0);
    ref.read(notificationProvider.notifier).fetchUnreadCount();
  }

  @override
  Widget build(BuildContext context) {
    final notifState = ref.watch(notificationProvider);
    final allNotifications = notifState.notifications;
    final unreadNotifications = allNotifications
        .where((n) => n['isRead'] != true)
        .toList();
    final m = context.miftah;
    final l = _L(context.isAr);
    final accentColor = m.isDark ? AppColors.accent : AppColors.primary;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppColors.navyDark,
        title: Text(l.title),
        actions: [
          if (notifState.unreadCount > 0)
            TextButton(
              onPressed: () =>
                  ref.read(notificationProvider.notifier).markAllAsRead(),
              child: Text(
                l.markAllRead,
                style: const TextStyle(color: AppColors.accent, fontSize: 13),
              ),
            ),
        ],
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: AppColors.accent,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white54,
          tabs: [
            Tab(text: l.all),
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(l.unread),
                  if (notifState.unreadCount > 0) ...[
                    const SizedBox(width: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: AppColors.danger,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        '${notifState.unreadCount}',
                        style: const TextStyle(
                          fontSize: 10,
                          color: Colors.white,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
      body: notifState.isLoading && allNotifications.isEmpty
          ? Center(child: CircularProgressIndicator(color: accentColor))
          : TabBarView(
              controller: _tabController,
              children: [
                _buildNotificationList(allNotifications, showAll: true),
                _buildNotificationList(unreadNotifications, showAll: false),
              ],
            ),
    );
  }

  Widget _buildNotificationList(
    List<dynamic> notifications, {
    required bool showAll,
  }) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final accentColor = m.isDark ? AppColors.accent : AppColors.primary;
    if (notifications.isEmpty) {
      return EmptyState(
        icon: Icons.notifications_none,
        title: l.noNotifications,
        subtitle: l.allCaughtUp,
      );
    }

    return NotificationListener<ScrollNotification>(
      onNotification: (scrollInfo) {
        if (showAll &&
            scrollInfo.metrics.pixels >=
                scrollInfo.metrics.maxScrollExtent - 200) {
          _loadMore();
        }
        return false;
      },
      child: RefreshIndicator(
        color: accentColor,
        onRefresh: _refresh,
        child: ListView.separated(
          padding: const EdgeInsets.symmetric(vertical: 8),
          itemCount: notifications.length + (_isLoadingMore ? 1 : 0),
          separatorBuilder: (context, index) =>
              const Divider(height: 1, indent: 72),
          itemBuilder: (context, index) {
            if (index >= notifications.length) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: CircularProgressIndicator(color: accentColor),
                ),
              );
            }

            final notification = notifications[index];
            final isRead = notification['isRead'] == true;
            final type = notification['type'] ?? '';

            return ListTile(
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 4,
              ),
              leading: _notificationIcon(type, isRead),
              title: Text(
                notification['title'] ?? l.notification,
                style: TextStyle(
                  fontWeight: isRead ? FontWeight.w400 : FontWeight.w600,
                  fontSize: 14,
                  color: m.textPrimary,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (notification['message'] != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      notification['message'],
                      style: Theme.of(context).textTheme.bodySmall,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                  const SizedBox(height: 4),
                  Text(
                    Formatters.timeAgo(
                      notification['createdAt'],
                      ar: context.isAr,
                    ),
                    style: Theme.of(context).textTheme.labelSmall,
                  ),
                ],
              ),
              trailing: !isRead
                  ? Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: accentColor,
                        shape: BoxShape.circle,
                      ),
                    )
                  : null,
              tileColor: isRead ? null : accentColor.withValues(alpha: 0.05),
              onTap: () {
                if (!isRead && notification['id'] != null) {
                  ref
                      .read(notificationProvider.notifier)
                      .markAsRead(notification['id']);
                }
              },
            );
          },
        ),
      ),
    );
  }

  Widget _notificationIcon(String type, bool isRead) {
    final m = context.miftah;
    IconData icon;
    Color color;

    switch (type.toUpperCase()) {
      case 'PAYMENT':
      case 'PAYMENT_DUE':
      case 'PAYMENT_OVERDUE':
      case 'PAYMENT_COLLECTED':
      case 'PAYMENT_CLEARED':
      case 'PAYMENT_BOUNCED':
        icon = Icons.payment;
        color = m.warning;
      case 'TICKET':
      case 'TICKET_UPDATE':
        icon = Icons.build_outlined;
        color = AppColors.accentDark;
      case 'LEASE':
      case 'LEASE_UPDATE':
        icon = Icons.description_outlined;
        color = m.isDark ? AppColors.accent : AppColors.primary;
      default:
        icon = Icons.notifications_outlined;
        color = m.textSecondary;
    }

    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: color.withValues(alpha: isRead ? 0.06 : 0.12),
        shape: BoxShape.circle,
      ),
      child: Icon(icon, color: color, size: 20),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'الإشعارات' : 'Notifications';
  String get markAllRead => ar ? 'تعليم الكل كمقروء' : 'Mark All Read';
  String get all => ar ? 'الكل' : 'All';
  String get unread => ar ? 'غير مقروء' : 'Unread';
  String get noNotifications => ar ? 'لا توجد إشعارات' : 'No Notifications';
  String get allCaughtUp =>
      ar ? 'أنت على اطلاع بكل شيء!' : 'You\'re all caught up!';
  String get notification => ar ? 'إشعار' : 'Notification';
}
