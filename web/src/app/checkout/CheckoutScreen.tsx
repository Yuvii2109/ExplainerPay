"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { PUBLIC_API, money } from "@/lib/pxe";

type Payee = {
  id: string;
  name: string;
  category: string;
  unexplained: number;
  exposureMinor: number;
};

/** Amounts a merchant would actually put on a QR. */
const PRESETS = [50000, 120000, 500000, 1840000];

/**
 * What a customer sees after scanning: who they are paying, then how much.
 *
 * They do not choose what happens next. The merchant armed that on the console before the scan,
 * and the rails decide, which is how it works outside a demo too.
 *
 * The exposure shown against each merchant is money whose fate is unexplained, not money anybody
 * owes. Nobody settles an explanation debt by paying it. It is here because it is the most useful
 * thing to know about a merchant before you look at their payments, and because watching it move
 * when a new failure lands is the entire argument.
 */
export function CheckoutScreen() {
  const router = useRouter();
  const [payees, setPayees] = useState<Payee[]>([]);
  const [merchant, setMerchant] = useState<Payee | null>(null);
  const [amountMinor, setAmountMinor] = useState<number>(PRESETS[0]);
  const [custom, setCustom] = useState("");
  const [paying, setPaying] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${PUBLIC_API}/api/merchants`, { cache: "no-store" })
      .then((r) => r.json())
      .then(setPayees)
      .catch(() => setFailed("Could not reach the payments backend from this device."));
  }, []);

  function choose(minor: number) {
    setAmountMinor(minor);
    setCustom("");
  }

  function typed(value: string) {
    setCustom(value);
    const rupees = Number(value);
    if (Number.isFinite(rupees) && rupees > 0) setAmountMinor(Math.round(rupees * 100));
  }

  async function pay() {
    if (!merchant || amountMinor <= 0) return;
    setPaying(true);
    setFailed(null);
    try {
      const response = await fetch(
        `${PUBLIC_API}/api/pay?amountMinor=${amountMinor}&merchant=${merchant.id}`,
        { method: "POST" },
      );
      if (!response.ok) {
        setFailed("The payment could not be taken in. Nothing was created.");
        setPaying(false);
        return;
      }
      const taken: { paymentId: string } = await response.json();
      router.push(`/payment/${taken.paymentId}?stream=1`);
    } catch {
      setFailed("Could not reach the payments backend from this device.");
      setPaying(false);
    }
  }

  if (!merchant) {
    return (
      <div className="checkout">
        <div className="checkout-to">Who are you paying</div>
        <h1 className="checkout-merchant">Choose a merchant</h1>

        <ul className="payees">
          {payees.map((p) => (
            <li key={p.id}>
              <button className="payee" onClick={() => setMerchant(p)}>
                <span className="payee-name">{p.name}</span>
                <span className="payee-category">{p.category}</span>
                <span className={p.unexplained > 0 ? "payee-owed NOT_MET" : "payee-owed dim"}>
                  {p.unexplained === 0
                    ? "nothing unexplained"
                    : `${p.unexplained} unexplained · ${money(p.exposureMinor, "INR")}`}
                </span>
              </button>
            </li>
          ))}
        </ul>

        {failed ? <p className="note NOT_MET">{failed}</p> : null}
        <p className="note checkout-note">
          The figure under each merchant is money whose fate this system cannot yet account for. It
          is not a bill. Paying does not settle it; explaining it does.
        </p>
      </div>
    );
  }

  const exposure = merchant.exposureMinor;

  return (
    <div className="checkout">
      <button className="back" onClick={() => setMerchant(null)}>
        ← another merchant
      </button>

      <div className="checkout-to">Paying</div>
      <h1 className="checkout-merchant">{merchant.name}</h1>
      <div className="checkout-category">{merchant.category}</div>

      <div className="checkout-amount">{money(amountMinor, "INR")}</div>

      <div className="presets">
        {PRESETS.map((minor) => (
          <button
            key={minor}
            className={`preset ${!custom && minor === amountMinor ? "on" : ""}`}
            onClick={() => choose(minor)}
          >
            {money(minor, "INR").replace(".00", "")}
          </button>
        ))}
        {exposure > 0 ? (
          <button
            className={`preset exposure ${!custom && exposure === amountMinor ? "on" : ""}`}
            onClick={() => choose(exposure)}
            title="Match what this merchant currently has unexplained"
          >
            match their exposure · {money(exposure, "INR").replace(".00", "")}
          </button>
        ) : null}
      </div>

      <label className="custom">
        <span className="custom-label">Or enter an amount</span>
        <div className="custom-field">
          <span className="custom-symbol">INR</span>
          <input
            type="number"
            inputMode="decimal"
            min="1"
            step="0.01"
            placeholder="0.00"
            value={custom}
            onChange={(e) => typed(e.target.value)}
          />
        </div>
      </label>

      <button className="ask pay-cta" onClick={pay} disabled={paying || amountMinor <= 0}>
        {paying ? "taking it in…" : `Pay ${money(amountMinor, "INR")}`}
      </button>

      {failed ? <p className="note NOT_MET">{failed}</p> : null}

      <p className="note checkout-note">
        This is a real payment against this backend. It gets its own id, runs the whole pipeline,
        and you will watch it being processed. What the rails do with it was decided on the merchant
        console before you scanned, because no bank fails on cue.
      </p>
    </div>
  );
}
