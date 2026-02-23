import AuthenticatedLayout from "@/components/layout/AuthenticatedLayout";

export default function SuperAdminLayout({
    children,
}: {
    children: React.ReactNode;
}) {
    return <AuthenticatedLayout>{children}</AuthenticatedLayout>;
}
