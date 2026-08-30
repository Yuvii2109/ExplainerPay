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
  merchantName: string;
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

/**
 * Money the platform still owes a merchant. Section 8.1.
 *
 * Not the explanation debt. This one is discharged by paying it, which is exactly what the other
 * one is not, and the two are kept in separate fields so no screen can quietly average them.
 */
export type Payable = {
  id: string;
  merchantId: string;
  merchantName: string;
  description: string;
  dueOn: string;
  currency: string;
  amountMinor: number;
  remainingMinor: number;
  overdue: boolean;
  part: boolean;
  lastPaymentId: string | null;
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

/**
 * Browser-side base URL, derived from whatever host the page was opened on.
 *
 * A phone that scans the QR loads the console from the machine's network address, and "localhost"
 * there means the phone. Taking the hostname from the page keeps the API on the same machine as
 * the page that asked for it, whether that page was opened on the laptop or across the room.
 */
const API_PORT = process.env.NEXT_PUBLIC_PXE_API_PORT ?? "18080";

export const PUBLIC_API =
  typeof window === "undefined"
    ? (process.env.NEXT_PUBLIC_PXE_API_URL ?? `http://localhost:${API_PORT}`)
    : `${window.location.protocol}//${window.location.hostname}:${API_PORT}`;

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
