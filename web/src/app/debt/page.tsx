import Link from "next/link";
import * as label from "@/lib/labels";
import { API, money } from "@/lib/pxe";

export const dynamic = "force-dynamic";

type Owed = {
  paymentId: string;
  amountMinor: number;
  currency: string;
  tag: string | null;
  responseCode: string | null;
  openedAt: string | null;
  ageSeconds: number;
  deviations: string[];
};

type Debt = { open: number; exposureMinor: number; queue: Owed[] };

function age(seconds: number) {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  return `${Math.floor(seconds / 3600)}h`;
}

/** The ops screen, and the one that makes the debt idea concrete. */
export default async function DebtQueue() {
  const debt: Debt = await (await fetch(`${API}/api/debt`, { cache: "no-store" })).json();

  return (
    <main>
      <h1>Explanation debt</h1>
      <p className="sub">
        {debt.open} open, {money(debt.exposureMinor, "INR")} exposed. Sorted by exposure. This
        number should trend to zero.
      </p>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Payment</th>
              <th className="num">Exposure</th>
              <th>Tag</th>
              <th>Against expectation</th>
              <th className="num">Age</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {debt.queue.length === 0 ? (
              <tr>
                <td colSpan={6} className="dim">
                  Nothing owed.
                </td>
              </tr>
            ) : (
              debt.queue.map((o) => (
                <tr key={o.paymentId}>
                  <td data-label="Payment" className="mono">
                    {o.paymentId}
                  </td>
                  <td data-label="Exposure" className="num">
                    {money(o.amountMinor, o.currency)}
                  </td>
                  <td data-label="Tag">
                    <span className={`tag tag-${o.tag}`} title={o.tag ?? ""}>
                      {label.tag(o.tag)}
                    </span>
                  </td>
                  <td data-label="Against expectation">
                    {o.deviations.length === 0 ? (
                      <span className="dim">nothing</span>
                    ) : (
                      <span className="deviations">
                        {o.deviations.map((d) => (
                          <span key={d} className="deviation" title={d}>
                            {label.deviation(d)}
                          </span>
                        ))}
                      </span>
                    )}
                  </td>
                  <td data-label="Age" className="num">
                    {age(o.ageSeconds)}
                  </td>
                  <td>
                    <Link className="scan" href={`/payment/${o.paymentId}`}>
                      open →
                    </Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <p className="note">
        Every payment here reached the end of the deterministic funnel without an answer. These are
        the only ones that can justify a model call.
      </p>
    </main>
  );
}
