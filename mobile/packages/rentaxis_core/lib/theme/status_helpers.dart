import 'package:flutter/material.dart';
import 'app_theme.dart';

class StatusHelper {
  static Color getPaymentStatusColor(String status) {
    return switch (status) {
      'PENDING' => AppColors.statusPending,
      'COLLECTED' => AppColors.statusCollected,
      'DEPOSITED' => AppColors.info,
      'CLEARED' => AppColors.statusCleared,
      'BOUNCED' => AppColors.statusBounced,
      'OVERDUE' => AppColors.statusOverdue,
      'ONLINE_PENDING' => AppColors.statusPending,
      'REPLACED' => AppColors.textMuted,
      _ => AppColors.textMuted,
    };
  }

  static Color getTicketStatusColor(String status) {
    return switch (status) {
      'OPEN' => AppColors.info,
      'ASSIGNED' => AppColors.statusPending,
      'IN_PROGRESS' => AppColors.warning,
      'RESOLVED' => AppColors.primary,
      'CLOSED' => AppColors.textMuted,
      'REOPENED' => AppColors.danger,
      _ => AppColors.textMuted,
    };
  }

  static Color getLeaseStatusColor(String status) {
    return switch (status) {
      'DRAFT' => AppColors.statusDraft,
      'PENDING_SIGNATURE' => AppColors.statusPending,
      'ACTIVE' => AppColors.statusActive,
      'NOTICE_GIVEN' => AppColors.warning,
      'TERMINATED' => AppColors.danger,
      'EXPIRED' => AppColors.textMuted,
      'CLOSED' => AppColors.textMuted,
      _ => AppColors.textMuted,
    };
  }

  static Color getPriorityColor(String priority) {
    return switch (priority) {
      'LOW' => AppColors.info,
      'MEDIUM' => AppColors.statusPending,
      'HIGH' => AppColors.warning,
      'URGENT' => AppColors.danger,
      _ => AppColors.textMuted,
    };
  }
}
