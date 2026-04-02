'use client';

import { getRoleLabel, type UserRole } from '@/lib/rbac';
import { cn } from '@/lib/utils';

interface RoleFilterProps {
  roles: UserRole[];
  compact?: boolean;
  className?: string;
}

export default function RoleFilter({ roles, compact = false, className }: RoleFilterProps) {
  return (
    <div className={cn('flex flex-wrap gap-1.5', className)}>
      {roles.map((role) => (
        <span
          key={role}
          className={cn(
            'inline-flex items-center rounded-full bg-teal-50 text-teal-700 font-medium',
            compact ? 'px-2 py-0.5 text-[10px]' : 'px-2.5 py-1 text-xs'
          )}
        >
          {getRoleLabel(role)}
        </span>
      ))}
    </div>
  );
}
