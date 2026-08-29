import { notFound } from "next/navigation";
import { PaymentScreen } from "./PaymentScreen";
import { API, type Snapshot } from "@/lib/pxe";

export const dynamic = "force-dynamic";

export default async function PaymentPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ stream?: string }>;
}) {
  const { id } = await params;
  const { stream } = await searchParams;

  const response = await fetch(`${API}/api/payments/${id}`, { cache: "no-store" });
  if (!response.ok) notFound();
  const snapshot: Snapshot = await response.json();

  return (
    <main>
      <PaymentScreen snapshot={snapshot} live={stream === "1"} />
    </main>
  );
}
