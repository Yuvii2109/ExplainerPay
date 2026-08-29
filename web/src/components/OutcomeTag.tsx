import * as label from "@/lib/labels";

type Props = { tag: string | null; responseCode: string | null; deviations: string[] };

/**
 * Two independent axes. The tag is what the rails said; the deviation set is what the expectation
 * model says. They disagree more often than is comfortable (PXE-007 is a SUCCESS carrying a debt)
 * and showing both side by side is the only way that reads as deliberate rather than as a bug.
 *
 * Each badge carries its symbol as a tooltip, so the vocabulary is one hover away without being
 * the first thing anyone has to decode.
 */
export function OutcomeTag({ tag, responseCode, deviations }: Props) {
  return (
    <div className="outcome">
      <span className={`tag tag-${tag ?? "PENDING"}`} title={tag ?? ""}>
        {tag ? label.tag(tag) : "…"}
      </span>
      {responseCode ? (
        <span className="code" title={"Response code from the rail: " + responseCode}>
          {responseCode} {label.code(responseCode)}
        </span>
      ) : null}
      <span className="deviations">
        {deviations.length === 0 ? (
          <span className="dim">nothing deviated from what we expected</span>
        ) : (
          deviations.map((d) => (
            <span key={d} className="deviation" title={d}>
              {label.deviation(d)}
            </span>
          ))
        )}
      </span>
    </div>
  );
}
