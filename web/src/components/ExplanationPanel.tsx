"use client";

import { useState } from "react";
import type { Explained } from "@/lib/pxe";

type Props = { explanation: Explained | null; debtOpen: boolean; onExplain?: () => void; busy?: boolean };

type Audience = "merchant" | "support" | "engineer";

const AUDIENCES: Audience[] = ["merchant", "support", "engineer"];

/**
 * Pre-reserved. The panel holds its height from the first paint whether or not an explanation
 * exists, so its arrival lights a box rather than pushing the timeline down.
 *
 * On the NONE, CODE and RULE paths the explanation is already here when the page renders: it
 * travelled on the stream, computed the instant the outcome landed. Revealing it is not fast, it is
 * free. The audience switcher changes the rendering, never the fact set.
 */
export function ExplanationPanel({ explanation, debtOpen, onExplain, busy }: Props) {
  const [audience, setAudience] = useState<Audience>("merchant");

  if (!explanation) {
    return (
      <section className="explanation explanation-empty" data-testid="explanation">
        {debtOpen ? (
          <>
            <span className="dim">
              Explanation owed. No response code and no rule accounted for this one.
            </span>
            {onExplain ? (
              <button className="ask" onClick={onExplain} disabled={busy}>
                {busy ? "asking the model…" : "ask why"}
              </button>
            ) : null}
          </>
        ) : (
          <span className="dim">Nothing owed. A payment that worked owes you no explanation.</span>
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
        <span className={`path path-${explanation.path}`}>{explanation.path}</span>
        <span className="level">{explanation.level}</span>
        {explanation.hypothesis ? <span className="hypothesis">HYPOTHESIS</span> : null}
        {explanation.abstained ? <span className="abstained">CANNOT DETERMINE</span> : null}
        {explanation.confidence != null ? (
          <span className="confidence">confidence {explanation.confidence.toFixed(2)}</span>
        ) : null}
        <span className="audiences">
          {AUDIENCES.map((a) => (
            <button
              key={a}
              className={`audience ${a === audience ? "on" : ""}`}
              onClick={() => setAudience(a)}
            >
              {a}
            </button>
          ))}
        </span>
      </div>

      <div className="root-cause">
        {explanation.rootCause ?? "Cause not determinable from available evidence"}
      </div>

      <p className="rendering" data-audience={audience}>
        {text ?? <span className="dim">No rendering at this level.</span>}
      </p>

      <div className="citations">
        {explanation.rules.map((r) => (
          <span key={r} className="citation rule">
            rule:{r}
          </span>
        ))}
        {citations.map((c) => (
          <span key={c} className="citation">
            {c}
          </span>
        ))}
      </div>
      <div className="of">
        fact set {explanation.factSetHash.slice(0, 16)}…
        {explanation.promptVersion ? ` · ${explanation.promptVersion}` : " · no model was called"}
      </div>
    </section>
  );
}
