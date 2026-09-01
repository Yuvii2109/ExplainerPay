"use client";

import dynamic from "next/dynamic";

const Widgets = dynamic(() => import("@/components/Widgets").then(m => m.Widgets), {
  ssr: false,
});

export function WidgetsClient() {
  return <Widgets />;
}
