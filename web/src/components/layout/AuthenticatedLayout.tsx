"use client";

import MvpSidebar from "@/components/ui/MvpSidebar";
import { cn } from "@/lib/utils";
import { usePathname } from "next/navigation";

export default function AuthenticatedLayout({
    children,
}: {
    children: React.ReactNode;
}) {
    const pathname = usePathname();

    return (
        <div className="flex min-h-screen bg-gray-50/50">
            <MvpSidebar />
            <main className="flex-1 overflow-y-auto">
                <div className="max-w-7xl mx-auto py-8">
                    {children}
                </div>
            </main>
        </div>
    );
}
