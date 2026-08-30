"use client";

import { money, type Hop } from "@/lib/pxe";

/**
 * Where the money actually went, when it did not all arrive.
 *
 * A payment screen that shows one amount at the top and a different one inside the explanation
 * makes a reader check whether the system is confusing itself. It is not: the customer paid one
 * figure and the merchant was settled another, and the gap is the whole finding. Putting the chain
 * on screen answers the question before it is asked.
 *
 * Only rendered when the figures disagree. On a payment where everything matched, three identical
 * numbers in a row would be noise.
 */
export function MoneyFlow({ hops, currency }: { hops: Hop[]; currency: string }) {
  const at = (stage: string) =>
    hops.find((h) => h.stage === stage && h.amountMinor != null)?.amountMinor ?? null;

  const authorized = at("NETWORK_AUTH") ?? at("AUTH_REQUEST");
  const captured = at("CAPTURED") ?? at("PAYER_DEBIT");
  const settled = at("PAYOUT_CREDITED") ?? at("PAYEE_CREDIT");

  const known = [authorized, captured, settled].filter((v): v is number => v != null);
  if (known.length < 2 || new Set(known).size === 1) return null;

  const shortfall = captured != null && settled != null ? captured - settled : null;

  return (
    <div className="money-flow">
      {authorized != null ? (
        <span className="leg">
          <span className="leg-label">authorized</span>
          <span className="leg-value">{money(authorized, currency)}</span>
        </span>
      ) : null}
      {captured != null ? (
        <span className="leg">
          <span className="leg-label">you paid</span>
          <span className="leg-value">{money(captured, currency)}</span>
        </span>
      ) : null}
      {settled != null ? (
        <span className="leg">
          <span className="leg-label">merchant received</span>
          <span className="leg-value">{money(settled, currency)}</span>
        </span>
      ) : (
        <span className="leg">
          <span className="leg-label">merchant received</span>
          <span className="leg-value NOT_MET">nothing yet</span>
        </span>
      )}
      {shortfall != null && shortfall !== 0 ? (
        <span className="leg leg-gap">
          <span className="leg-label">{shortfall > 0 ? "short by" : "over by"}</span>
          <span className="leg-value NOT_MET">{money(Math.abs(shortfall), currency)}</span>
        </span>
      ) : null}
    </div>
  );
}
