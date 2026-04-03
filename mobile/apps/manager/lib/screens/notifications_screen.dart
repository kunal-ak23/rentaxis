import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

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
    await ref
        .read(notificationProvider.notifier)
        .fetchNotifications(page: 0);
    ref.read(notificationProvider.notifier).fetchUnreadCount();
  }

  @override
  Widget build(BuildContext context) {
    final notifState = ref.watch(notificationProvider);
    final allNotifications = notifState.notifications;
    final unreadNotifications =
        allNotifications.where((n) => n['isRead'] != true).toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Notifications'),
        actions: [
          if (notifState.unreadCount > 0)
            TextButton(
              onPressed: () =>
                  ref.read(notificationProvider.notifier).markAllAsRead(),
              child: Text(
                'Mark All Read',
                style: GoogleFonts.josefinSans(
                  color: AppColors.primary,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
        ],
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: AppColors.primary,
          labelColor: AppColors.primary,
          unselectedLabelColor: AppColors.textMuted,
          indicatorSize: TabBarIndicatorSize.label,
          tabs: [
            const Tab(text: 'All'),
            Tab(
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('Unread'),
                  if (notifState.unreadCount > 0) ...[
                    const SizedBox(width: 6),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 6, vertical: 2),
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
          ? const Center(
              child: CircularProgressIndicator(color: AppColors.primary))
          : TabBarView(
              controller: _tabController,
              children: [
                _buildNotificationList(allNotifications, showAll: true),
                _buildNotificationList(unreadNotifications, showAll: false),
              ],
            ),
    );
  }

  Widget _buildNotificationList(List<dynamic> notifications,
      {required bool showAll}) {
    if (notifications.isEmpty) {
      return const EmptyState(
        icon: Icons.notifications_none,
        title: 'No Notifications',
        subtitle: 'You\'re all caught up!',
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
        color: AppColors.primary,
        onRefresh: _refresh,
        child: ListView.separated(
          padding: const EdgeInsets.symmetric(vertical: 8),
          itemCount: notifications.length + (_isLoadingMore ? 1 : 0),
          separatorBuilder: (_, __) =>
              const Divider(height: 1, indent: 72),
          itemBuilder: (context, index) {
            if (index >= notifications.length) {
              return const Center(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: CircularProgressIndicator(color: AppColors.primary),
                ),
              );
            }

            final notification = notifications[index];
            final isRead = notification['isRead'] == true;
            final type = notification['type'] ?? '';

            return ListTile(
              contentPadding:
                  const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
              leading: _notificationIcon(type, isRead),
              title: Text(
                notification['title'] ?? 'Notification',
                style: GoogleFonts.josefinSans(
                  fontWeight: isRead ? FontWeight.w400 : FontWeight.w600,
                  fontSize: 14,
                  color: AppColors.textPrimary,
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
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                  const SizedBox(height: 4),
                  Text(
                    Formatters.timeAgo(notification['createdAt']),
                    style: GoogleFonts.josefinSans(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
              trailing: !isRead
                  ? Container(
                      width: 8,
                      height: 8,
                      decoration: const BoxDecoration(
                        color: AppColors.primary,
                        shape: BoxShape.circle,
                      ),
                    )
                  : null,
              tileColor:
                  isRead ? null : AppColors.primary.withValues(alpha: 0.03),
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
        color = AppColors.warning;
      case 'TICKET':
      case 'TICKET_UPDATE':
        icon = Icons.build_outlined;
        color = AppColors.info;
      case 'LEASE':
      case 'LEASE_UPDATE':
        icon = Icons.description_outlined;
        color = AppColors.primary;
      default:
        icon = Icons.notifications_outlined;
        color = AppColors.textSecondary;
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
