"use client";

import { useCallback, useEffect, useState } from "react";
import { PUBLIC_API, money, type Payable } from "@/lib/pxe";

function day(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}

/**
 * The payables queue, section 8.1. What the platform still owes, and what a payment did to it.
 *
 * It is on the console rather than tucked away because the moment worth seeing is a row closing
 * while the debt counter goes up, or a row refusing to close after the rails reported success.
 * Either way the answer is on one screen instead of two.
 */
export function Owed() {
  const [rows, setRows] = useState<Payable[] | null>(null);
  const [resetting, setResetting] = useState(false);

  const read = useCallback(() => {
    fetch(`${PUBLIC_API}/api/payables`, { cache: "no-store" })
      .then((r) => r.json())
      .then(setRows)
      .catch(() => setRows([]));
  }, []);

  useEffect(() => {
    read();
    window.addEventListener("focus", read);
    return () => window.removeEventListener("focus", read);
  }, [read]);

  async function reset() {
    setResetting(true);
    await fetch(`${PUBLIC_API}/api/payables/reset`, { method: "POST" }).catch(() => {});
    read();
    setResetting(false);
  }

  if (rows === null) return null;

  const total = rows.reduce((sum, r) => sum + r.remainingMinor, 0);
  const part = rows.filter((r) => r.part).length;

  return (
    <>
      <h2>Still owed to merchants</h2>
      <p className="note selector-note">
        Money this platform holds and has not handed over yet. A row comes down by what the merchant
        was <strong>credited</strong>, never by what the customer was charged, so a payment the rails
        called successful can leave one open. That is not the same thing as an explanation debt and
        paying does nothing to that one.
      </p>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Merchant</th>
              <th>What for</th>
              <th>Due</th>
              <th className="num">Still owed</th>
              <th className="num">Was</th>
              <th>State</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td data-label="Merchant">{r.merchantName}</td>
                <td data-label="What for">{r.description}</td>
                <td data-label="Due" className={r.overdue ? "NOT_MET" : "dim"}>
                  {day(r.dueOn)}
                </td>
                <td data-label="Still owed" className="num">
                  {money(r.remainingMinor, r.currency)}
                </td>
                <td data-label="Was" className="num dim">
                  {r.part ? money(r.amountMinor, r.currency) : ""}
                </td>
                <td data-label="State" className={r.part ? "NOT_MET" : "dim"}>
                  {r.part ? "short after " + r.lastPaymentId : r.overdue ? "overdue" : "waiting"}
                </td>
              </tr>
            ))}
            {rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="dim">
                  Everything owed has been settled.
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>

      <p className="note owed-foot">
        <span>
          {rows.length} open · {money(total, "INR")} outstanding
          {part > 0 ? ` · ${part} left short by a payment that succeeded` : ""}
        </span>
        <button className="scan" onClick={reset} disabled={resetting}>
          {resetting ? "restoring…" : "restore the queue"}
        </button>
      </p>
    </>
  );
}
