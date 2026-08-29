import { Fragment } from "react";
import * as label from "@/lib/labels";

/** SCREAMING_SNAKE, plus the camelCase field names the engineer rendering names directly. */
const SYMBOL =
  /\b(?:[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|missedCutoff|boundReference|retryOf|duplicateOf|included|LedgerEntry)\b/g;

/**
 * An explanation, with its symbols set as tokens rather than left shouting in the middle of a
 * sentence.
 *
 * The engineer rendering names stages, rules and fields on purpose: that audience is asked for the
 * mechanism and the hops that show it, and PAYER_DEBIT is the precise word for the thing. What was
 * wrong was not that the symbols were there, it was that they were styled like prose, so a
 * paragraph read as if someone had pasted a log line into it.
 *
 * Set as tokens they read as references, and each one carries its English on hover, so the same
 * sentence works for the engineer who wants the symbol and the reader who does not know it.
 */
export function Prose({ text }: { text: string }) {
  const parts: React.ReactNode[] = [];
  let last = 0;

  for (const match of text.matchAll(SYMBOL)) {
    const at = match.index ?? 0;
    if (at > last) parts.push(text.slice(last, at));
    const symbol = match[0];
    parts.push(
      <span className="symbol" key={`${at}-${symbol}`} title={english(symbol)}>
        {symbol}
      </span>,
    );
    last = at + symbol.length;
  }
  if (last < text.length) parts.push(text.slice(last));

  return (
    <>
      {parts.map((part, i) => (
        <Fragment key={i}>{part}</Fragment>
      ))}
    </>
  );
}

/** Whichever vocabulary knows this symbol. Falls back to de-shouting it. */
function english(symbol: string): string {
  for (const lookup of [label.stage, label.rule, label.cause, label.deviation, label.actor]) {
    const named = lookup(symbol);
    if (named && named !== label.humanise(symbol)) return named;
  }
  return label.humanise(symbol);
}
