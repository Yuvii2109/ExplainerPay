"use client";

import Link from "next/link";
import QRCode from "qrcode";
import { useEffect, useState } from "react";
import { Owed } from "@/components/Owed";
import * as label from "@/lib/labels";
import { PUBLIC_API, money, type Header } from "@/lib/pxe";

/**
 * One QR on screen, one phone. You are the merchant, and you pay yourself.
 *
 * The QR is real and it is scannable. Scanning it does not open a record that already existed: it
 * takes a payment in, gives it its own id and its own debt, runs it through the whole funnel, and
 * sends the phone to watch it being processed. The hops land in your hand while the counters run
 * on the laptop.
 *
 * The rails are visibly ours, and that is the honest version. A scenario selector decides what the
 * next scan does, because no bank times out on cue: the interesting failures here are settlement
 * and reconciliation breaks that surface hours later, and no PSP sandbox emits those on demand.
 * Say that out loud. Pretending otherwise is what nobody believes.
 */
export function PayScreen({ payments }: { payments: Header[] }) {
  // PXE ids are the injected failures a scan can choose from. PAY ids are payments that have
  // actually been taken in, which is the list that grows while you demo.
  const templates = payments.filter((p) => p.paymentId.startsWith("PXE-"));
  const taken = payments.filter((p) => p.paymentId.startsWith("PAY-"));

  const [selected, setSelected] = useState(templates[0]?.paymentId ?? "");
  const [svg, setSvg] = useState<string | null>(null);
  const [target, setTarget] = useState("");

  // The QR never changes. One code on screen for the whole demo, and what it becomes is decided
  // here, behind it.
  useEffect(() => {
    const url = `${window.location.origin}/checkout`;
    setTarget(url);
    QRCode.toString(url, {
      type: "svg",
      margin: 1,
      errorCorrectionLevel: "M",
      color: { dark: "#e7ecf5", light: "#0000" },
    }).then(setSvg);
  }, []);

  // Arming is server state, so the phone that scans knows what the rails are about to do without
  // the QR having to carry it.
  useEffect(() => {
    if (!selected) return;
    fetch(`${PUBLIC_API}/api/pay/arm?as=${selected}`, { method: "POST" }).catch(() => {});
  }, [selected]);

  const reachable = !target.includes("localhost") && !target.includes("127.0.0.1");
  const chosen = templates.find((p) => p.paymentId === selected);

  return (
    <>
      <div className="pay">
        <div className="qr">
          <div className="qr-inner" aria-label={`QR code for ${selected}`}>
            {svg ? (
              <div className="qr-svg" dangerouslySetInnerHTML={{ __html: svg }} />
            ) : (
              <span className="qr-note">generating</span>
            )}
          </div>
          <div className="qr-target">
            {chosen ? (
              <>
                armed: <span className="mono">{selected}</span> · {label.rail(chosen.rail)} ·{" "}
                {chosen.deviations.length === 0
                  ? "clean"
                  : chosen.deviations.map(label.deviation).join(", ")}
              </>
            ) : null}
          </div>
          <a className="ask pay-now" href="/checkout">
            Open the checkout here
          </a>
          <p className="note qr-hint">
            {reachable ? (
              <>
                Scan it. The customer picks an amount and pays; what the rails do with it is armed
                below.
              </>
            ) : (
              <>
                This QR points at <span className="mono">{target || "…"}</span>, which only resolves
                on this machine. Open the console at your machine&apos;s network address for a phone
                to be able to reach it.
              </>
            )}
          </p>
        </div>
      </div>

      <h2>What the next scan does</h2>
      <p className="note selector-note">
        A payment has two independent axes. The tag is what the rails said; the deviation column is
        what the expectation model says. They disagree more often than is comfortable, which is why
        a payment can read <span className="MET">Succeeded</span> and still owe an explanation.
      </p>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Payment</th>
              <th>Rail</th>
              <th className="num">Amount</th>
              <th>Tag</th>
              <th>Code</th>
              <th>Against expectation</th>
              <th>Debt</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {templates.map((p) => (
              <tr
                key={p.paymentId}
                className={p.paymentId === selected ? "aimed" : ""}
                onClick={() => setSelected(p.paymentId)}
              >
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
                <td data-label="Against expectation">
                  {p.deviations.length === 0 ? (
                    <span className="dim">nothing</span>
                  ) : (
                    <span className="deviations">
                      {p.deviations.map((d) => (
                        <span key={d} className="deviation" title={d}>
                          {label.deviation(d)}
                        </span>
                      ))}
                    </span>
                  )}
                </td>
                <td data-label="Debt" className={p.debtOpen ? "NOT_MET" : "dim"}>
                  {p.debtOpen ? "open" : p.debtOpenedAt ? "paid" : "none"}
                </td>
                <td>
                  <Link
                    className="scan"
                    href={`/payment/${p.paymentId}?stream=1`}
                    onClick={(e) => e.stopPropagation()}
                  >
                    open →
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="note">
        Clicking a row chooses which failure the rails return for the next scan. Saying that out
        loud is the credible version: no bank times out on cue, and the failures worth explaining
        here surface in settlement hours later.
      </p>

      <Owed />

      {taken.length > 0 ? (
        <>
          <h2>Payments taken in</h2>
          <div className="panel">
            <table>
              <thead>
                <tr>
                  <th>Payment</th>
                  <th className="num">Amount</th>
                  <th>Tag</th>
                  <th>Against expectation</th>
                  <th>Debt</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {taken.map((p) => (
                  <tr key={p.paymentId}>
                    <td data-label="Payment" className="mono">
                      {p.paymentId}
                    </td>
                    <td data-label="Amount" className="num">
                      {money(p.amountMinor, p.currency)}
                    </td>
                    <td data-label="Tag">
                      <span className={`tag tag-${p.tag}`} title={p.tag ?? ""}>
                        {label.tag(p.tag)}
                      </span>
                    </td>
                    <td data-label="Against expectation">
                      {p.deviations.length === 0 ? (
                        <span className="dim">nothing</span>
                      ) : (
                        <span className="deviations">
                          {p.deviations.map((d) => (
                            <span key={d} className="deviation" title={d}>
                              {label.deviation(d)}
                            </span>
                          ))}
                        </span>
                      )}
                    </td>
                    <td data-label="Debt" className={p.debtOpen ? "NOT_MET" : "dim"}>
                      {p.debtOpen ? "open" : p.debtOpenedAt ? "paid" : "none"}
                    </td>
                    <td>
                      <Link className="scan" href={`/payment/${p.paymentId}`}>
                        open →
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : null}
    </>
  );
}
