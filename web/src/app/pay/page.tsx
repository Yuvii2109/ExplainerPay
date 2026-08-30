import { PayScreen } from "./PayScreen";
import { API, type Header } from "@/lib/pxe";

export const dynamic = "force-dynamic";

export default async function Pay() {
  const payments: Header[] = await (
    await fetch(`${API}/api/payments`, { cache: "no-store" })
  ).json();

  return (
    <main>
      <PayScreen payments={payments} />
    </main>
  );
}
