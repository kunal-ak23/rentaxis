import type { Metadata } from "next";
import { FontLinks } from "@/components/FontLinks";
import Image from "next/image";
import "../globals.css";

export const metadata: Metadata = {
  title: "RentAxis — Available Properties",
  description: "Browse available rental properties on RentAxis",
};

export default function PublicListingLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" dir="ltr">
      <head>
        <FontLinks />
      </head>
      <body className="antialiased bg-neutral-50">
        {/* Minimal public header */}
        <header className="sticky top-0 z-40 bg-white border-b border-neutral-200 shadow-sm">
          <div className="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
            <a href="/" className="flex items-center">
              <Image src="/logo.png" alt="RentAxis" width={120} height={34} className="object-contain" priority />
            </a>
            <a
              href="/en/auth/login"
              className="inline-flex items-center gap-2 px-4 py-1.5 rounded-lg bg-teal-700 text-white text-sm font-semibold hover:bg-teal-800 transition-colors"
            >
              Sign In
            </a>
          </div>
        </header>

        <main>{children}</main>

        {/* Minimal footer */}
        <footer className="mt-16 border-t border-neutral-200 bg-white py-6">
          <div className="max-w-5xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-2 text-xs text-neutral-400">
            <span>© {new Date().getFullYear()} RentAxis. All rights reserved.</span>
            <a href="/en/auth/login" className="hover:text-neutral-600 transition-colors">Sign In to your account</a>
          </div>
        </footer>
      </body>
    </html>
  );
}
