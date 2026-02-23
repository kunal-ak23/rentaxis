"use client";

import MvpSidebar from "@/components/ui/MvpSidebar";
import { cn } from "@/lib/utils";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { useRouter } from "@/i18n/routing";
import { useEffect } from "react";
import { Loader2 } from "lucide-react";

export default function AuthenticatedLayout({
    children,
}: {
    children: React.ReactNode;
}) {
    const pathname = usePathname();
    const { data: session, status } = useSession();
    const router = useRouter();

    useEffect(() => {
        if (status === "unauthenticated") {
            router.push("/auth/login");
        }
    }, [status, router]);

    if (status === "loading") {
        return (
            <div className="min-h-screen bg-gray-50 flex items-center justify-center">
                <Loader2 className="w-8 h-8 animate-spin text-primary opacity-50" />
            </div>
        );
    }

    if (!session) return null;

    return (
        <div className="flex min-h-screen bg-gray-50/50">
            <MvpSidebar />
            <main className="flex-1 overflow-y-auto">
                <div className="max-w-7xl mx-auto py-8 px-4 md:px-8">
                    {children}
                </div>
            </main>
        </div>
    );
}
