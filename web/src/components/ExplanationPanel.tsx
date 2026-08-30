"use client";

import { useState } from "react";
import { Prose } from "./Prose";
import * as label from "@/lib/labels";
import type { Explained } from "@/lib/pxe";

type Props = {
  explanation: Explained | null;
  debtOpen: boolean;
  stillOwed?: boolean;
  onExplain?: () => void;
  busy?: boolean;
};

type Audience = "merchant" | "support" | "engineer";

const AUDIENCES: { id: Audience; hint: string }[] = [
  { id: "merchant", hint: "What happened to their money" },
  { id: "support", hint: "What the agent must do" },
  { id: "engineer", hint: "The mechanism, with the hops" },
];

/**
 * Pre-reserved. The panel holds its height from the first paint whether or not an explanation
 * exists, so its arrival lights a box rather than pushing the timeline down.
 *
 * The cause leads in English and keeps its symbol underneath. The switcher changes who is being
 * spoken to, never the fact set: same evidence, three things to do about it.
 */
export function ExplanationPanel({ explanation, debtOpen, stillOwed, onExplain, busy }: Props) {
  const [audience, setAudience] = useState<Audience>("merchant");

  if (!explanation) {
    return (
      <section className="explanation explanation-empty" data-testid="explanation">
        {debtOpen ? (
          <>
            <span className="dim">
              An explanation is owed. No response code and no rule accounted for this one.
            </span>
            {onExplain ? (
              <button className="ask" onClick={onExplain} disabled={busy}>
                {busy ? "asking the model…" : "Ask why"}
              </button>
            ) : null}
          </>
        ) : (
          <span className="dim">
            Nothing owed. This payment worked, so there is nothing to explain and it cost nothing to
            say so.
          </span>
        )}
      </section>
    );
  }

  const citations: string[] = explanation.citations ? JSON.parse(explanation.citations) : [];
  const text =
    audience === "merchant"
      ? explanation.merchantText
      : audience === "support"
        ? explanation.supportText
        : explanation.engineerText;

  return (
    <section className="explanation" data-testid="explanation">
      <div className="explanation-head">
        <span className={`path path-${explanation.path}`} title={explanation.path}>
          {label.path(explanation.path)}
        </span>
        {explanation.hypothesis ? (
          <span className="hypothesis" title="Proposed, not established">
            Hypothesis
          </span>
        ) : null}
        {explanation.abstained ? <span className="abstained">Cannot determine</span> : null}
        {explanation.confidence != null ? (
          <span className="confidence">confidence {explanation.confidence.toFixed(2)}</span>
        ) : null}
        <span className="audiences">
          {AUDIENCES.map((a) => (
            <button
              key={a.id}
              className={`audience ${a.id === audience ? "on" : ""}`}
              title={a.hint}
              onClick={() => setAudience(a.id)}
            >
              {a.id}
            </button>
          ))}
        </span>
      </div>

      <h3 className="root-cause">
        {explanation.rootCause
          ? label.cause(explanation.rootCause)
          : "The cause cannot be determined from the available evidence"}
      </h3>
      {explanation.rootCause ? <div className="root-cause-symbol">{explanation.rootCause}</div> : null}

      <p className="rendering" data-audience={audience}>
        {text ? <Prose text={text} /> : <span className="dim">No rendering at this level.</span>}
      </p>

      {stillOwed ? (
        <p className="still-owed">
          The debt stays open. Nothing here explains the payment, so it remains on the queue until
          somebody does.
        </p>
      ) : null}

      <div className="citations">
        {explanation.rules.map((r) => (
          <span key={r} className="citation rule" title={r}>
            {label.rule(r)}
          </span>
        ))}
        {citations.map((c) => (
          <span key={c} className="citation" title={c}>
            {label.citation(c)}
          </span>
        ))}
      </div>
      <div className="of">
        fact set {explanation.factSetHash.slice(0, 12)}…
        {explanation.promptVersion
          ? ` · ${explanation.promptVersion}`
          : " · no model was called"}
      </div>
    </section>
  );
}
