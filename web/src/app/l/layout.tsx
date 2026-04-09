import type { Metadata } from "next";
import { FontLinks } from "@/components/FontLinks";
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
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}
