"use client";

import { useCallback, useEffect, useState } from "react";
import { PUBLIC_API } from "@/lib/pxe";

type Verdict = {
  rejected: boolean;
  rejectedBy: string | null;
  detail: string | null;
  kept: { id: string; kind: string; text: string; citations: string[] }[];
  dropped: { id: string; rule: string; why: string }[];
};

type Probe = { paymentId: string; rule: string; payload: string; verdict: Verdict };

type Rejection = {
  paymentId: string;
  job: string;
  rejectedBy: string;
  detail: string;
  payload: string | null;
};

const PROBES = ["G4", "G1", "G3", "G6", "G9"];

/**
 * Beat 10. A deliberately malformed response, run through the real validator, with the rejected
 * payload on screen.
 *
 * A safety rule visibly catching the model is worth more than any number of correct outputs. This
 * is the same validator a live generation goes through — nothing here is a simulation of the rule,
 * it is the rule.
 */
export function GroundingScreen() {
  const [probe, setProbe] = useState<Probe | null>(null);
  const [rejections, setRejections] = useState<Rejection[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const loadRejections = useCallback(async () => {
    const response = await fetch(`${PUBLIC_API}/api/grounding/rejections`, { cache: "no-store" });
    setRejections(await response.json());
  }, []);

  useEffect(() => {
    loadRejections();
  }, [loadRejections]);

  async function run(rule: string) {
    setBusy(rule);
    try {
      const response = await fetch(`${PUBLIC_API}/api/grounding/probe/PXE-006/${rule}`, {
        method: "POST",
      });
      setProbe(await response.json());
      await loadRejections();
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <h2>Send the validator a malformed response</h2>
      <div className="probe-row">
        {PROBES.map((rule) => (
          <button key={rule} className="ask" disabled={busy !== null} onClick={() => run(rule)}>
            {busy === rule ? "validating…" : `violate ${rule}`}
          </button>
        ))}
      </div>

      {probe ? (
        <div className={probe.verdict.rejected ? "rejection" : "panel"} style={{ padding: 16 }}>
          <div className="rejection-head">
            {probe.verdict.rejected ? (
              <>
                <span className="rejected-by">{probe.verdict.rejectedBy} REJECTED</span>
                <span className="dim">{probe.verdict.detail}</span>
              </>
            ) : (
              <>
                <span className="MET">accepted</span>
                <span className="dim">
                  {probe.verdict.kept.length} claim(s) kept, {probe.verdict.dropped.length} dropped
                </span>
              </>
            )}
          </div>

          {probe.verdict.dropped.length > 0 ? (
            <ul className="dropped">
              {probe.verdict.dropped.map((d) => (
                <li key={d.id}>
                  <span className="rejected-by">{d.rule}</span> {d.why}
                </li>
              ))}
            </ul>
          ) : null}

          <div className="of" style={{ marginBottom: 6 }}>
            the payload that was thrown away
          </div>
          <pre className="payload">{probe.payload}</pre>
        </div>
      ) : null}

      <h2>Rejection log</h2>
      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Payment</th>
              <th>Job</th>
              <th>Rule</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {rejections.length === 0 ? (
              <tr>
                <td colSpan={4} className="dim">
                  Nothing has been rejected.
                </td>
              </tr>
            ) : (
              rejections.map((r, i) => (
                <tr key={`${r.paymentId}-${i}`}>
                  <td data-label="Payment" className="mono">
                    {r.paymentId}
                  </td>
                  <td data-label="Job" className="dim mono">
                    {r.job}
                  </td>
                  <td data-label="Rule" className="NOT_MET mono">
                    {r.rejectedBy}
                  </td>
                  <td data-label="Detail" className="dim">
                    {r.detail}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <p className="note">
        Every row here is a response the model produced and the system refused to believe. The
        payload is kept, because a rejection you cannot read is a claim rather than a record.
      </p>
    </>
  );
}
