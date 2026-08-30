import { redirect } from "next/navigation";
import { API } from "@/lib/pxe";

export const dynamic = "force-dynamic";

/**
 * Where a scan lands.
 *
 * A payment is taken in, and the browser is sent to watch it being processed. This is a GET that
 * creates something, which is not a shape to be proud of, and it is the only shape a phone camera
 * can produce: it opens a URL and nothing else.
 */
export default async function Scan({
  searchParams,
}: {
  searchParams: Promise<{ as?: string }>;
}) {
  const { as } = await searchParams;

  // Kept for a direct link. A scanned QR goes to the checkout, where the customer chooses an
  // amount first; this path takes the scenario amount as-is.
  const response = await fetch(as ? `${API}/api/pay?as=${as}` : `${API}/api/pay`, {
    method: "POST",
    cache: "no-store",
  });

  if (!response.ok) redirect("/pay");
  const taken: { paymentId: string } = await response.json();

  redirect(`/payment/${taken.paymentId}?stream=1`);
}
