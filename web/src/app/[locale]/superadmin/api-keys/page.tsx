import PromoterApiKeysManager from "@/components/superadmin/PromoterApiKeysManager";

/** SUPER_ADMIN: find a leaked promoter API key across all organisations and revoke it with a reason. */
export default function SuperAdminApiKeysPage() {
    return <PromoterApiKeysManager />;
}
