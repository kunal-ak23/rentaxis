"use client";

import MvpSidebar from "@/components/ui/MvpSidebar";
import { cn } from "@/lib/utils";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { useRouter } from "@/i18n/routing";
import { useEffect } from "react";
import { Loader2 } from "lucide-react";
import { TopHeader } from "@/components/ui/TopHeader";

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
            <div className="flex flex-col flex-1 overflow-hidden relative">
                {/* SVG Pattern Bleed layer sitting behind TopHeader and main content */}
                <div className="absolute top-0 left-1/2 ml-[-30rem] h-[27rem] w-[85rem] pointer-events-none z-0">
                    <div className="absolute inset-0 bg-gradient-to-r from-blue-600/30 to-blue-400/20 [mask-image:radial-gradient(farthest-side_at_top,white,transparent)]">
                        <svg aria-hidden="true" className="absolute inset-x-0 inset-y-[-50%] h-[200%] w-full skew-y-[-18deg] fill-black/10 stroke-black/5 mix-blend-overlay">
                            <defs>
                                <pattern id="header-pattern" width="72" height="56" patternUnits="userSpaceOnUse" x="-12" y="4">
                                    <path d="M.5 56V.5H72" fill="none"></path>
                                </pattern>
                            </defs>
                            <rect width="100%" height="100%" strokeWidth="0" fill="url(#header-pattern)"></rect>
                            <svg x="-12" y="4" className="overflow-visible">
                                <rect strokeWidth="0" width="73" height="57" x="288" y="168"></rect>
                                <rect strokeWidth="0" width="73" height="57" x="144" y="56"></rect>
                                <rect strokeWidth="0" width="73" height="57" x="504" y="168"></rect>
                                <rect strokeWidth="0" width="73" height="57" x="720" y="336"></rect>
                            </svg>
                        </svg>
                    </div>
                    <svg viewBox="0 0 1113 440" aria-hidden="true" className="absolute top-0 left-1/2 ml-[-19rem] w-[69.5625rem] fill-white/80 blur-[26px]">
                        <path d="M.016 439.5s-9.5-300 434-300S882.516 20 882.516 20V0h230.004v439.5H.016Z"></path>
                    </svg>
                </div>

                <div className="relative z-30">
                    <TopHeader />
                </div>
                <main className="flex-1 overflow-y-auto relative z-10 w-full">
                    <div className="max-w-7xl mx-auto py-8 px-4 md:px-8">
                        {children}
                    </div>
                </main>
            </div>
        </div>
    );
}
