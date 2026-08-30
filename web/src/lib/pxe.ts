export type Hop = {
  seq: number;
  stage: string;
  actor: string;
  status: string;
  code: string | null;
  latencyMs: number | null;
  occurredAt: string | null;
  absent: boolean;
  amountMinor: number | null;
  batch: string | null;
  note: string | null;
  attrs: Record<string, unknown>;
};

export type Explained = {
  level: string;
  promptVersion?: string | null;
  path: string;
  rootCause: string | null;
  determinable: boolean;
  confidence: number | null;
  hypothesis: boolean;
  abstained: boolean;
  citations: string | null;
  factSetHash: string;
  rules: string[];
  merchantText: string | null;
  supportText: string | null;
  engineerText: string | null;
};

export type Header = {
  paymentId: string;
  merchantId: string;
  amountMinor: number;
  currency: string;
  instrument: string;
  rail: string;
  tag: string | null;
  responseCode: string | null;
  debtOpen: boolean;
  debtOpenedAt: string | null;
  debtClosedAt: string | null;
  deviations: string[];
};

export type Snapshot = {
  header: Header;
  skeleton: string[];
  hops: Hop[];
  deviations: string[];
  explanation: Explained | null;
  tokensSpent: number;
};

export type Counters = {
  debtOpen: number;
  exposureMinor: number;
  tokensSpent: number;
  explained: number;
  viaModel: number;
};

/** Server-side base URL, inside the compose network. */
export const API = process.env.PXE_API_URL ?? "http://localhost:18080";

/** Browser-side base URL, reachable from the host. */
export const PUBLIC_API =
  process.env.NEXT_PUBLIC_PXE_API_URL ?? "http://localhost:18080";

export function money(minor: number, currency: string) {
  return `${(minor / 100).toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })} ${currency}`;
}

export function clock(iso: string | null) {
  if (!iso) return null;
  return iso.slice(11, 19);
}
