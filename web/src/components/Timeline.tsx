"use client";

import { HopRow } from "./HopRow";
import type { Hop } from "@/lib/pxe";

type Props = { skeleton: string[]; hops: Hop[] };

/**
 * The skeleton is drawn first and every row keeps its place. A hop lands into the skeleton row for
 * its stage; anything on no happy path (a retry, a reversal, a batch closure) appends below.
 *
 * Nothing sits under the timeline on the page, so an append moves nothing that is already visible
 * and the cumulative layout shift stays zero by construction.
 */
export function Timeline({ skeleton, hops }: Props) {
  const claimed = new Set<number>();
  const rows = skeleton.map((stage) => {
    const hop = hops.find((h) => h.stage === stage && !claimed.has(h.seq)) ?? null;
    if (hop) claimed.add(hop.seq);
    return { key: `skeleton:${stage}`, stage, hop };
  });

  const unplanned = hops
    .filter((h) => !claimed.has(h.seq))
    .sort((a, b) => a.seq - b.seq)
    .map((hop) => ({ key: `hop:${hop.seq}`, stage: hop.stage, hop }));

  return (
    <ol className="timeline" data-testid="timeline">
      {[...rows, ...unplanned].map((r) => (
        <HopRow key={r.key} stage={r.stage} hop={r.hop} />
      ))}
    </ol>
  );
}
