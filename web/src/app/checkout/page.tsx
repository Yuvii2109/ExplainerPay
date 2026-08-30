import { CheckoutScreen } from "./CheckoutScreen";

export const dynamic = "force-dynamic";

/** Where a scan lands. The QR is constant, so this page never changes address. */
export default function Checkout() {
  return (
    <main className="checkout-main">
      <CheckoutScreen />
    </main>
  );
}
