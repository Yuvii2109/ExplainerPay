"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ExplanationPanel } from "@/components/ExplanationPanel";
import { MoneyFlow } from "@/components/MoneyFlow";
import { OutcomeTag } from "@/components/OutcomeTag";
import { Timeline } from "@/components/Timeline";
import { usePaymentStream } from "@/lib/stream";
import * as label from "@/lib/labels";
import { PUBLIC_API, money, type Snapshot } from "@/lib/pxe";

/**
 * Page order is deliberate: header, outcome, explanation, then the timeline last.
 *
 * The timeline is the only block that grows, and nothing sits under it, so an unplanned hop
 * appending moves nothing already on screen. Everything above it is pre-reserved at a fixed height.
 */
export function PaymentScreen({ snapshot, live }: { snapshot: Snapshot; live: boolean }) {
  const router = useRouter();
  const [streaming, setStreaming] = useState(live);
  const [answered, setAnswered] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const stream = usePaymentStream(snapshot.header.paymentId, streaming);

  // Beat 8: the only action in the console that can spend a token.
  async function explain() {
    setBusy(true);
    setRefused(null);
    try {
      const response = await fetch(
        `${PUBLIC_API}/api/payments/${snapshot.header.paymentId}/explain`,
        { method: "POST" },
      );
      if (response.ok) {
        setAnswered(await response.json());
        router.refresh();
      } else if (response.status === 422) {
        setRefused("The response was rejected by the grounding contract. No explanation was produced and the debt is still open.");
      } else {
        setRefused("The model could not be reached. The debt stays open rather than being answered badly.");
      }
    } finally {
      setBusy(false);
    }
  }

  // While streaming, the screen shows only what has arrived. Otherwise, and whenever the stream
  // could not be opened, it shows the whole payment: a broken stream must never read as a payment
  // that produced no events.
  const following = streaming && !stream.failed;
  const current = answered ?? snapshot;
  const showing = following
    ? {
        skeleton: stream.skeleton.length ? stream.skeleton : snapshot.skeleton,
        hops: stream.hops,
        tag: stream.tag,
        responseCode: stream.responseCode,
        deviations: stream.deviations,
        explanation: stream.explanation,
      }
    : {
        skeleton: current.skeleton,
        hops: current.hops,
        tag: current.header.tag,
        responseCode: current.header.responseCode,
        deviations: current.deviations,
        explanation: current.explanation,
      };
  const explanation = answered?.explanation ?? showing.explanation;

  return (
    <>
      <div className="payment-head">
        <h1>{snapshot.header.paymentId}</h1>
        <span className="amount">
          {money(snapshot.header.amountMinor, snapshot.header.currency)}
        </span>
        <span className="dim" title={`${snapshot.header.instrument} · ${snapshot.header.rail}`}>
          {label.rail(snapshot.header.rail)}
        </span>
        <span className="dim payee-on-payment">to {snapshot.header.merchantName}</span>
        {!streaming ? (
          <button className="replay" onClick={() => setStreaming(true)}>
            replay hop by hop
          </button>
        ) : stream.failed ? (
          <span className="streaming NOT_MET">stream unavailable, showing the record</span>
        ) : (
          <span className="streaming">{stream.done ? "complete" : "streaming"}</span>
        )}
      </div>

      <OutcomeTag
        tag={showing.tag}
        responseCode={showing.responseCode}
        deviations={showing.deviations}
      />

      <MoneyFlow hops={current.hops} currency={current.header.currency} />

      <ExplanationPanel
        explanation={explanation}
        debtOpen={current.header.debtOpen && !explanation}
        stillOwed={current.header.debtOpen && explanation != null}
        onExplain={explain}
        busy={busy}
      />
      {refused ? <p className="note NOT_MET">{refused}</p> : null}

      <h2>Timeline</h2>
      <Timeline skeleton={showing.skeleton} hops={showing.hops} />
    </>
  );
}
