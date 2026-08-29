"use client";

import { memo } from "react";
import { clock, money, type Hop } from "@/lib/pxe";

type Props = { stage: string; hop: Hop | null };

/**
 * One row. Memoised on the hop's seq, so a hop arriving is O(1) DOM work and every row already on
 * screen is left alone.
 *
 * Three states, all the same height, because a row that changes height moves everything below it:
 *  - pending: the skeleton, greyed, waiting
 *  - landed:  the event arrived
 *  - absent:  the event did not happen, and naming it precisely is the answer (section 19)
 */
function HopRowImpl({ stage, hop }: Props) {
  const state = !hop ? "pending" : hop.absent ? "absent" : "landed";
  const dot = state === "landed" ? "●" : "○";

  return (
    <li className={`hop hop-${state}`}>
      <span className="hop-dot" aria-hidden>
        {dot}
      </span>
      <span className="hop-stage">{stage}</span>
      <span className="hop-actor">{hop?.actor ?? ""}</span>
      <span className="hop-time">
        {state === "absent" ? "ABSENT" : (clock(hop?.occurredAt ?? null) ?? "")}
      </span>
      <span className="hop-status">
        {hop?.status ?? ""}
        {hop?.code ? ` ${hop.code}` : ""}
      </span>
      <span className="hop-detail">
        {hop?.latencyMs != null ? `${hop.latencyMs} ms` : ""}
        {hop?.amountMinor != null ? ` ${money(hop.amountMinor, "INR")}` : ""}
        {hop?.batch ? ` ${hop.batch}` : ""}
        {hop?.absent && typeof hop.attrs?.elapsedHours === "number"
          ? `${hop.attrs.elapsedHours}h elapsed / ${hop.attrs.slaHours}h SLA`
          : ""}
      </span>
    </li>
  );
}

export const HopRow = memo(
  HopRowImpl,
  (a, b) => a.stage === b.stage && a.hop?.seq === b.hop?.seq && a.hop?.status === b.hop?.status,
);
