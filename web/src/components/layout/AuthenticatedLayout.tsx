"use client";

import MvpSidebar from "@/components/ui/MvpSidebar";
import { cn } from "@/lib/utils";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { useRouter } from "@/i18n/routing";
import { useEffect } from "react";
import { Loader2 } from "lucide-react";
import { TopHeader } from "@/components/ui/TopHeader";
import TourProvider from "@/components/tour/TourProvider";
import HelpFAB from "@/components/help/HelpFAB";

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
            <div className="min-h-screen bg-background flex items-center justify-center">
                <div className="flex flex-col items-center gap-3">
                    <Loader2 className="w-8 h-8 animate-spin text-primary opacity-60" />
                    <span className="text-xs text-muted font-medium tracking-wide">Loading...</span>
                </div>
            </div>
        );
    }

    if (!session) return null;

    return (
        <TourProvider>
            <div className="flex h-screen overflow-hidden bg-background">
                <MvpSidebar />
                <div className="flex flex-col flex-1 min-w-0">
                    <TopHeader />
                    <main className="flex-1 overflow-y-auto">
                        <div className="max-w-7xl mx-auto py-8 px-4 md:px-8">
                            {children}
                        </div>
                    </main>
                </div>
            </div>
            <HelpFAB />
        </TourProvider>
    );
}
