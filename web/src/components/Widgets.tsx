"use client";

import { useEffect, useState } from "react";
import { PUBLIC_API, money, type Counters } from "@/lib/pxe";

/**
 * The two widgets of section 23. They stay visible the entire time and are never hidden, because
 * they are the argument the whole system makes.
 *
 * Both numbers are easy to misread on their own, so each says underneath what it counts.
 * "Explanation debt 1" beside thirteen produced explanations is the system working; read as a
 * count of explanations it looks like the system has explained almost nothing. And a token counter
 * means nothing without the denominator: the point is not that it is low, it is that it covers
 * three payments out of thirteen and the other ten cost nothing at all.
 *
 * The value animates on opacity and transform alone. A number that visibly moves is read as live;
 * a number that jumps is read as a refresh.
 */
export function Widgets() {
  const [counters, setCounters] = useState<Counters | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    const poll = async () => {
      try {
        const response = await fetch(`${PUBLIC_API}/api/counters`, { cache: "no-store" });
        const next: Counters = await response.json();
        if (!alive) return;
        setCounters((previous) => {
          if (
            previous &&
            (previous.debtOpen !== next.debtOpen || previous.tokensSpent !== next.tokensSpent)
          ) {
            setTick((t) => t + 1);
          }
          return next;
        });
      } catch {
        /* the widgets never blank on a hiccup; they hold their last known value */
      }
    };
    poll();
    const timer = setInterval(poll, 1200);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  const owed = counters?.debtOpen ?? 0;
  const explained = counters?.explained ?? 0;
  const viaModel = counters?.viaModel ?? 0;
  const tokens = counters?.tokensSpent ?? 0;

  return (
    <div className="widgets">
      <div className="widget">
        <span className="widget-label">Explanations still owed</span>
        <span key={`d${tick}`} className="widget-value">
          {owed}
        </span>
        <span className="widget-sub">
          {counters
            ? owed === 0
              ? `${explained} explained, nothing outstanding`
              : `${explained} explained · ${money(counters.exposureMinor, "INR")} exposed`
            : " "}
        </span>
      </div>
      <div className="widget">
        <span className="widget-label">Tokens spent</span>
        <span key={`t${tick}`} className="widget-value">
          {tokens.toLocaleString("en-IN")}
        </span>
        <span className="widget-sub">
          {tokens === 0
            ? "no payment has earned a call"
            : `on ${viaModel} of ${explained} explanations`}
        </span>
      </div>
    </div>
  );
}
