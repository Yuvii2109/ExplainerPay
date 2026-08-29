"use client";

import { useEffect, useState } from "react";
import { PUBLIC_API, money, type Counters } from "@/lib/pxe";

/**
 * The two widgets of section 23. They stay visible the entire time and are never hidden, because
 * they are the argument the whole system makes: a debt that should trend to zero, and a token
 * counter that does not move until a payment has earned the call.
 *
 * The value animates on opacity alone. A number that visibly moves is read as live; a number that
 * jumps is read as a refresh.
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
          if (previous && previous.debtOpen !== next.debtOpen) setTick((t) => t + 1);
          if (previous && previous.tokensSpent !== next.tokensSpent) setTick((t) => t + 1);
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

  return (
    <div className="widgets">
      <div className="widget">
        <span className="widget-label">Explanation debt</span>
        <span key={`d${tick}`} className="widget-value">
          {counters?.debtOpen ?? 0}
        </span>
        <span className="widget-sub">
          {counters ? money(counters.exposureMinor, "INR") + " exposed" : " "}
        </span>
      </div>
      <div className="widget">
        <span className="widget-label">Tokens spent</span>
        <span key={`t${tick}`} className="widget-value">
          {counters?.tokensSpent ?? 0}
        </span>
        <span className="widget-sub">
          {counters?.tokensSpent === 0 ? "no payment has earned a call" : " "}
        </span>
      </div>
    </div>
  );
}
