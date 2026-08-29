import { GroundingScreen } from "./GroundingScreen";
import { API } from "@/lib/pxe";

export const dynamic = "force-dynamic";

type Rule = { id: string; rule: string; onFailure: string };

export default async function Grounding() {
  const rules: Rule[] = await (
    await fetch(`${API}/api/grounding/rules`, { cache: "no-store" })
  ).json();

  return (
    <main>
      <h1>The grounding contract</h1>
      <p className="sub">
        Nine rules applied to every generated response before any of it is believed. Two outcomes: a
        claim that cannot be supported is dropped, and a response that breaks the protocol is thrown
        away whole.
      </p>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th>Rule</th>
              <th>Requires</th>
              <th>On failure</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id}>
                <td>{r.id}</td>
                <td>{r.rule}</td>
                <td className={r.onFailure.startsWith("Reject") ? "NOT_MET" : "dim"}>
                  {r.onFailure}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <GroundingScreen />
    </main>
  );
}
