import Link from "next/link";
import * as label from "@/lib/labels";
import { API, money, type Header } from "@/lib/pxe";

export const dynamic = "force-dynamic";

/**
 * The QR, large, and the two persistent widgets. Nothing else.
 *
 * The scenario selector decides what the next scan does. Say that out loud: choosing which failure
 * the rails return is honest, because no bank times out on cue. Pretending otherwise is not.
 */
export default async function Pay() {
  const payments: Header[] = await (
    await fetch(`${API}/api/payments`, { cache: "no-store" })
  ).json();

  return (
    <main>
      <div className="pay">
        <div className="qr" aria-label="payment QR">
          <div className="qr-inner">
            <span className="qr-note">QR</span>
          </div>
          <p className="note">
            Wiring to a PSP test mode is deferred. The selector below chooses which failure the
            rails return for the next scan.
          </p>
        </div>
      </div>

      <h2>Scenario selector</h2>
      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Payment</th>
              <th>Rail</th>
              <th className="num">Amount</th>
              <th>Tag</th>
              <th>Code</th>
              <th>Debt</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {payments.map((p) => (
              <tr key={p.paymentId}>
                <td data-label="Payment" className="mono">
                  {p.paymentId}
                </td>
                <td data-label="Rail" className="dim" title={p.rail}>
                  {label.rail(p.rail)}
                </td>
                <td data-label="Amount" className="num">
                  {money(p.amountMinor, p.currency)}
                </td>
                <td data-label="Tag">
                  <span className={`tag tag-${p.tag}`} title={p.tag ?? ""}>
                    {label.tag(p.tag)}
                  </span>
                </td>
                <td data-label="Code" className="dim" title={p.responseCode ?? ""}>
                  {p.responseCode ? label.code(p.responseCode) : label.NONE}
                </td>
                <td data-label="Debt" className={p.debtOpen ? "NOT_MET" : "dim"}>
                  {p.debtOpen ? "open" : p.debtOpenedAt ? "paid" : "none"}
                </td>
                <td>
                  <Link className="scan" href={`/payment/${p.paymentId}?stream=1`}>
                    scan →
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}
