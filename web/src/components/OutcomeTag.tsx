type Props = { tag: string | null; responseCode: string | null; deviations: string[] };

/**
 * Two independent axes. The tag is what the rails said; the deviation set is what the expectation
 * model says. PXE-007 is a SUCCESS carrying a debt, and showing both is the only way that reads as
 * deliberate rather than as a bug.
 */
export function OutcomeTag({ tag, responseCode, deviations }: Props) {
  return (
    <div className="outcome">
      <span className={`tag tag-${tag ?? "PENDING"}`}>{tag ?? "…"}</span>
      {responseCode ? <span className="code">{responseCode}</span> : null}
      <span className="deviations">
        {deviations.length === 0 ? (
          <span className="dim">no deviation</span>
        ) : (
          deviations.map((d) => (
            <span key={d} className="deviation">
              {d}
            </span>
          ))
        )}
      </span>
    </div>
  );
}
