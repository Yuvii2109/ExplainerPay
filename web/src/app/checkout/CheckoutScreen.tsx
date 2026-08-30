"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { PUBLIC_API, money, type Payable } from "@/lib/pxe";

type Payee = {
  id: string;
  name: string;
  category: string;
  unexplained: number;
  exposureMinor: number;
  outstanding: number;
  owedMinor: number;
};

/** Amounts a merchant would actually put on a QR, for a payment against no particular bill. */
const PRESETS = [50000, 120000, 500000, 1840000];

function day(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}

/**
 * What a customer sees after scanning: who they are paying, what that merchant is still owed, and
 * how much of it to settle now.
 *
 * The queue in the middle is ordinary accounts payable, section 8.1. It is not the explanation
 * debt, and the two are shown apart on purpose. Paying makes the first one shrink and does nothing
 * at all to the second.
 *
 * Nobody chooses what the rails do next. The merchant armed that on the console before the scan,
 * which is also how it works outside a demo.
 */
export function CheckoutScreen() {
  const router = useRouter();
  const [payees, setPayees] = useState<Payee[]>([]);
  const [merchant, setMerchant] = useState<Payee | null>(null);
  const [owed, setOwed] = useState<Payable[] | null>(null);
  const [bill, setBill] = useState<Payable | null>(null);
  const [freeform, setFreeform] = useState(false);
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

  useEffect(() => {
    if (!merchant) return;
    setOwed(null);
    fetch(`${PUBLIC_API}/api/payables?merchant=${merchant.id}`, { cache: "no-store" })
      .then((r) => r.json())
      .then(setOwed)
      .catch(() => setOwed([]));
  }, [merchant]);

  function choose(minor: number) {
    setAmountMinor(minor);
    setCustom("");
  }

  function typed(value: string) {
    setCustom(value);
    const rupees = Number(value);
    if (Number.isFinite(rupees) && rupees > 0) setAmountMinor(Math.round(rupees * 100));
  }

  function pick(row: Payable) {
    setBill(row);
    setFreeform(false);
    setAmountMinor(row.remainingMinor);
    setCustom("");
  }

  function anything() {
    setBill(null);
    setFreeform(true);
    setAmountMinor(PRESETS[0]);
    setCustom("");
  }

  function backToList() {
    setBill(null);
    setFreeform(false);
  }

  async function pay() {
    if (!merchant || amountMinor <= 0) return;
    setPaying(true);
    setFailed(null);
    try {
      const against = bill ? `&payable=${bill.id}` : "";
      const response = await fetch(
        `${PUBLIC_API}/api/pay?amountMinor=${amountMinor}&merchant=${merchant.id}${against}`,
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

  // ---- who ----

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
                <span className="payee-lines">
                  <span className={p.owedMinor > 0 ? "payee-owed" : "payee-owed dim"}>
                    {p.outstanding === 0
                      ? "nothing owed"
                      : `${p.outstanding} still owed · ${money(p.owedMinor, "INR")}`}
                  </span>
                  {p.unexplained > 0 ? (
                    <span className="payee-owed NOT_MET">
                      {p.unexplained} unexplained · {money(p.exposureMinor, "INR")}
                    </span>
                  ) : null}
                </span>
              </button>
            </li>
          ))}
        </ul>

        {failed ? <p className="note NOT_MET">{failed}</p> : null}
        <p className="note checkout-note">
          The first figure is money this platform holds and has not handed over yet. Paying it makes
          it go away. The second is money whose fate nobody can account for, and paying does nothing
          to that one at all.
        </p>
      </div>
    );
  }

  // ---- what ----

  if (!bill && !freeform) {
    return (
      <div className="checkout">
        <button className="back" onClick={() => setMerchant(null)}>
          ← another merchant
        </button>

        <div className="checkout-to">Still owed to</div>
        <h1 className="checkout-merchant">{merchant.name}</h1>

        {owed === null ? (
          <p className="note">reading the queue…</p>
        ) : (
          <ul className="payees">
            {owed.map((row) => (
              <li key={row.id}>
                <button className="payee bill" onClick={() => pick(row)}>
                  <span className="bill-head">
                    <span className="payee-name">{row.description}</span>
                    <span className="bill-amount">{money(row.remainingMinor, row.currency)}</span>
                  </span>
                  <span className="payee-category">
                    due {day(row.dueOn)}
                    {row.overdue ? <span className="flag late"> overdue</span> : null}
                    {row.part ? (
                      <span className="flag part">
                        {" "}
                        part paid, {money(row.amountMinor, row.currency)} was owed
                      </span>
                    ) : null}
                  </span>
                </button>
              </li>
            ))}
            <li>
              <button className="payee anything" onClick={anything}>
                <span className="payee-name">Pay a different amount</span>
                <span className="payee-category">Not against any of these</span>
              </button>
            </li>
          </ul>
        )}

        {owed !== null && owed.length === 0 ? (
          <p className="note">Nothing outstanding for this merchant.</p>
        ) : null}

        <p className="note checkout-note">
          Each row shrinks by what the merchant is actually credited, which is not always what you
          pay. A row that stays open after a payment the rails called successful is the interesting
          case, and it is the one worth watching for.
        </p>
      </div>
    );
  }

  // ---- how much ----

  const full = bill?.remainingMinor ?? 0;

  return (
    <div className="checkout">
      <button className="back" onClick={backToList}>
        ← what to pay
      </button>

      <div className="checkout-to">Paying</div>
      <h1 className="checkout-merchant">{merchant.name}</h1>
      <div className="checkout-category">{bill ? bill.description : merchant.category}</div>

      <div className="checkout-amount">{money(amountMinor, "INR")}</div>

      <div className="presets">
        {bill ? (
          <>
            <button
              className={`preset ${!custom && full === amountMinor ? "on" : ""}`}
              onClick={() => choose(full)}
            >
              all of it · {money(full, "INR").replace(".00", "")}
            </button>
            <button
              className={`preset ${!custom && Math.round(full / 2) === amountMinor ? "on" : ""}`}
              onClick={() => choose(Math.round(full / 2))}
            >
              half · {money(Math.round(full / 2), "INR").replace(".00", "")}
            </button>
          </>
        ) : (
          PRESETS.map((minor) => (
            <button
              key={minor}
              className={`preset ${!custom && minor === amountMinor ? "on" : ""}`}
              onClick={() => choose(minor)}
            >
              {money(minor, "INR").replace(".00", "")}
            </button>
          ))
        )}
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
        and you will watch it being processed.
        {bill
          ? " What is owed comes down by what reaches the merchant, so if the money goes missing on the way, this bill stays open."
          : " It is not against a bill, so nothing in the queue changes."}
      </p>
    </div>
  );
}
