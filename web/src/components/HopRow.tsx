"use client";

import { memo } from "react";
import * as label from "@/lib/labels";
import { clock, money, type Hop } from "@/lib/pxe";

type Props = { stage: string; hop: Hop | null };

/**
 * One row. Memoised on the hop's seq, so a hop arriving is O(1) DOM work and every row already on
 * screen is left alone.
 *
 * The stage reads in English and carries its symbol as a tooltip, because a timeline is the first
 * thing a merchant looks at and PAYEE_CREDIT tells them nothing. The engineer rendering in the
 * panel above still speaks in symbols, which is where that vocabulary belongs.
 *
 * Three states, all the same height, because a row that changes height moves everything below it:
 * pending (the skeleton, waiting), landed, and absent, an event that did not happen, which is
 * drawn rather than skipped because naming it precisely is the answer.
 */
function HopRowImpl({ stage, hop }: Props) {
  const state = !hop ? "pending" : hop.absent ? "absent" : "landed";
  const elapsed =
    hop?.absent && typeof hop.attrs?.elapsedHours === "number"
      ? `${hop.attrs.elapsedHours}h waited, ${hop.attrs.slaHours}h allowed`
      : "";

  return (
    <li className={`hop hop-${state}`} title={stage}>
      <span className="hop-dot" aria-hidden>
        {state === "landed" ? "●" : "○"}
      </span>
      <span className="hop-stage">{label.stage(stage)}</span>
      <span className="hop-actor">{hop ? label.actor(hop.actor) : ""}</span>
      <span className="hop-time">
        {state === "absent" ? "never arrived" : (clock(hop?.occurredAt ?? null) ?? "")}
      </span>
      <span className="hop-status">
        {state === "absent" ? "" : label.status(hop?.status)}
        {hop?.code && hop.code !== "00" ? ` ${hop.code}` : ""}
      </span>
      <span className="hop-detail">
        {[
          hop?.latencyMs != null ? `${hop.latencyMs.toLocaleString("en-IN")} ms` : "",
          hop?.amountMinor != null ? money(hop.amountMinor, "INR") : "",
          hop?.batch ? `batch ${hop.batch}` : "",
          elapsed,
        ]
          .filter(Boolean)
          .join(" · ")}
      </span>
    </li>
  );
}

export const HopRow = memo(
  HopRowImpl,
  (a, b) => a.stage === b.stage && a.hop?.seq === b.hop?.seq && a.hop?.status === b.hop?.status,
);
