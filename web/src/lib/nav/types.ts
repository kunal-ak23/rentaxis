// src/lib/nav/types.ts
import type { UserRole } from "../rbac";
/** A message key: `ns` is the next-intl namespace, `key` the key inside it. */
export type Label = { ns: string; key: string };
export interface NavContext {
    role: UserRole | undefined;
    isEnabled: (flag: string) => boolean;
    tenantSlug: string;
}
