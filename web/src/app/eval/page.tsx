type Status = "MET" | "NOT_MET" | "NOT_MEASURED";

type Metric = {
  name: string;
  definition: string;
  numerator: number;
  denominator: number;
  value: number;
  unit: string;
  target: string;
  enforcement: string;
  status: Status;
};

type Row = {
  paymentId: string;
  expectedPath: string;
  actualPath: string | null;
  injectedCause: string | null;
  namedCause: string | null;
  explained: boolean;
  modelCalls: number;
  pathAsExpected: boolean;
  causeCorrect: boolean;
};

type EvalReport = {
  ranAt: string;
  scenarios: number;
  explained: number;
  metrics: Metric[];
  rows: Row[];
};

const API = process.env.PXE_API_URL ?? "http://localhost:18080";

function show(metric: Metric) {
  if (metric.unit === "tokens") {
    return metric.value.toFixed(0);
  }
  return `${(metric.value * 100).toFixed(1)}%`;
}

function label(status: Status) {
  return status === "NOT_MEASURED" ? "not measured" : status === "MET" ? "met" : "not met";
}

export const dynamic = "force-dynamic";

export default async function Eval() {
  const response = await fetch(`${API}/api/eval`, { cache: "no-store" });
  const report: EvalReport = await response.json();

  return (
    <main>
      <h1>Evaluation</h1>
      <p className="sub">
        {report.scenarios} scenarios in the golden set, {report.explained} explained. Ground truth is
        read from the dataset, never from the database.
      </p>

      <h2>Metrics</h2>
      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Metric</th>
              <th className="num">Value</th>
              <th className="num">Target</th>
              <th>Enforcement</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {report.metrics.map((m) => (
              <tr key={m.name}>
                <td data-label="Metric">
                  <div>
                    <div>{m.name}</div>
                    <div className="of">{m.definition}</div>
                  </div>
                </td>
                <td data-label="Value" className="num">
                  <div>
                    <div className={`value ${m.status}`}>{show(m)}</div>
                    <div className="of">
                      {m.numerator} / {m.denominator}
                    </div>
                  </div>
                </td>
                <td data-label="Target" className="num">
                  {m.target}
                </td>
                <td data-label="Enforcement" className="dim">
                  {m.enforcement}
                </td>
                <td data-label="Status" className={m.status}>
                  {label(m.status)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="note">
        A metric with an empty denominator reports zero because nothing has been measured. A metric
        with a full denominator reports zero because the system is not yet covering anything. Both
        read as 0, and they are different facts.
      </p>

      <h2>The funnel</h2>
      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Payment</th>
              <th>Expected path</th>
              <th>Actual path</th>
              <th className="num">Model calls</th>
              <th>Injected cause</th>
              <th>Named cause</th>
            </tr>
          </thead>
          <tbody>
            {report.rows.map((r) => (
              <tr key={r.paymentId}>
                <td data-label="Payment" className="mono">
                  {r.paymentId}
                </td>
                <td data-label="Expected" className="mono">
                  {r.expectedPath}
                </td>
                <td
                  data-label="Actual"
                  className={
                    "mono " +
                    (r.actualPath === null ? "dim" : r.pathAsExpected ? "MET" : "NOT_MET")
                  }
                >
                  {r.actualPath ?? "not produced"}
                </td>
                <td data-label="Model calls" className="num">
                  {r.modelCalls}
                </td>
                <td data-label="Injected cause" className="dim mono">
                  {r.injectedCause ?? "—"}
                </td>
                <td
                  data-label="Named cause"
                  className={
                    "mono " +
                    (r.namedCause === null ? "dim" : r.causeCorrect ? "MET" : "NOT_MET")
                  }
                >
                  {r.namedCause ?? "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="note">
        If a scenario marked CODE ever reaches the model, the funnel has regressed.
      </p>
    </main>
  );
}
