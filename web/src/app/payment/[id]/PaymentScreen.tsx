"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ExplanationPanel } from "@/components/ExplanationPanel";
import { OutcomeTag } from "@/components/OutcomeTag";
import { Timeline } from "@/components/Timeline";
import { usePaymentStream } from "@/lib/stream";
import { PUBLIC_API, money, type Explained, type Snapshot } from "@/lib/pxe";

/**
 * Page order is deliberate: header, outcome, explanation, then the timeline last.
 *
 * The timeline is the only block that grows, and nothing sits under it, so an unplanned hop
 * appending moves nothing already on screen. Everything above it is pre-reserved at a fixed height.
 */
export function PaymentScreen({ snapshot, live }: { snapshot: Snapshot; live: boolean }) {
  const router = useRouter();
  const [streaming, setStreaming] = useState(live);
  const [asked, setAsked] = useState<Explained | null>(null);
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
        setAsked(await response.json());
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

  // While streaming, the screen shows only what has arrived. Otherwise it shows the whole payment.
  const showing = streaming
    ? {
        skeleton: stream.skeleton.length ? stream.skeleton : snapshot.skeleton,
        hops: stream.hops,
        tag: stream.tag,
        responseCode: stream.responseCode,
        deviations: stream.deviations,
        explanation: stream.explanation,
      }
    : {
        skeleton: snapshot.skeleton,
        hops: snapshot.hops,
        tag: snapshot.header.tag,
        responseCode: snapshot.header.responseCode,
        deviations: snapshot.deviations,
        explanation: snapshot.explanation,
      };
  const explanation = asked ?? showing.explanation;

  return (
    <>
      <div className="payment-head">
        <h1>{snapshot.header.paymentId}</h1>
        <span className="amount">
          {money(snapshot.header.amountMinor, snapshot.header.currency)}
        </span>
        <span className="dim">
          {snapshot.header.instrument} · {snapshot.header.rail}
        </span>
        {!streaming ? (
          <button className="replay" onClick={() => setStreaming(true)}>
            replay hop by hop
          </button>
        ) : (
          <span className="streaming">{stream.done ? "complete" : "streaming"}</span>
        )}
      </div>

      <OutcomeTag
        tag={showing.tag}
        responseCode={showing.responseCode}
        deviations={showing.deviations}
      />

      <ExplanationPanel
        explanation={explanation}
        debtOpen={snapshot.header.debtOpen && !explanation}
        onExplain={explain}
        busy={busy}
      />
      {refused ? <p className="note NOT_MET">{refused}</p> : null}

      <h2>Timeline</h2>
      <Timeline skeleton={showing.skeleton} hops={showing.hops} />
    </>
  );
}
