import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../api/services/notification_service.dart';
import 'auth_provider.dart';

final notificationApiServiceProvider =
    Provider<NotificationApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return NotificationApiService(client.dio);
});

class NotificationState {
  final int unreadCount;
  final List<dynamic> notifications;
  final bool isLoading;

  const NotificationState({
    this.unreadCount = 0,
    this.notifications = const [],
    this.isLoading = false,
  });

  NotificationState copyWith(
      {int? unreadCount, List<dynamic>? notifications, bool? isLoading}) {
    return NotificationState(
      unreadCount: unreadCount ?? this.unreadCount,
      notifications: notifications ?? this.notifications,
      isLoading: isLoading ?? this.isLoading,
    );
  }
}

class NotificationNotifier extends StateNotifier<NotificationState> {
  final NotificationApiService _service;
  Timer? _pollingTimer;

  NotificationNotifier(this._service) : super(const NotificationState());

  void startPolling() {
    fetchUnreadCount();
    _pollingTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      fetchUnreadCount();
    });
  }

  void stopPolling() {
    _pollingTimer?.cancel();
  }

  Future<void> fetchUnreadCount() async {
    try {
      final count = await _service.getUnreadCount();
      state = state.copyWith(unreadCount: count);
    } catch (_) {}
  }

  Future<void> fetchNotifications({int page = 0, int size = 20}) async {
    state = state.copyWith(isLoading: true);
    try {
      final notifications =
          await _service.getNotifications(page: page, size: size);
      state =
          state.copyWith(notifications: notifications, isLoading: false);
    } catch (_) {
      state = state.copyWith(isLoading: false);
    }
  }

  Future<void> markAsRead(String id) async {
    await _service.markAsRead(id);
    state = state.copyWith(
      unreadCount: (state.unreadCount - 1).clamp(0, 999),
      notifications: state.notifications.map((n) {
        if (n['id'] == id) return {...n, 'isRead': true};
        return n;
      }).toList(),
    );
  }

  Future<void> markAllAsRead() async {
    await _service.markAllAsRead();
    state = state.copyWith(
      unreadCount: 0,
      notifications:
          state.notifications.map((n) => {...n, 'isRead': true}).toList(),
    );
  }

  @override
  void dispose() {
    _pollingTimer?.cancel();
    super.dispose();
  }
}

final notificationProvider =
    StateNotifierProvider<NotificationNotifier, NotificationState>((ref) {
  final service = ref.watch(notificationApiServiceProvider);
  return NotificationNotifier(service);
});
