import type { Metadata } from "next";
import Link from "next/link";

import { Live } from "@/components/Live";
import { WidgetsClient } from "@/components/WidgetsClient";

import "./globals.css";

export const metadata: Metadata = {
  title: "PXE",
  description: "Payment Explainability Engine",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body suppressHydrationWarning>
        <header className="chrome">
          <nav>
            <Link href="/pay">pay</Link>
            <Link href="/debt">debt</Link>
            <Link href="/grounding">grounding</Link>
            <Link href="/eval">eval</Link>
          </nav>
          <WidgetsClient />
        </header>
        <Live />
        {children}
      </body>
    </html>
  );
}
