"use client";

import { useEffect, useRef, useState } from "react";
import { PUBLIC_API, type Explained, type Hop } from "./pxe";

export type StreamState = {
  skeleton: string[];
  hops: Hop[];
  tag: string | null;
  responseCode: string | null;
  deviations: string[];
  explanation: Explained | null;
  tokensSpent: number;
  done: boolean;
};

const EMPTY: StreamState = {
  skeleton: [],
  hops: [],
  tag: null,
  responseCode: null,
  deviations: [],
  explanation: null,
  tokensSpent: 0,
  done: false,
};

/**
 * Section 17.2: the client appends. It never refetches and never re-renders the list.
 *
 * Each frame produces a new hop array with one element pushed, so React reconciles one added row
 * and leaves the existing rows alone. The skeleton lands before any hop, which is what reserves the
 * layout, and the explanation arrives on the stream rather than on request, which is why revealing
 * it costs nothing.
 */
export function usePaymentStream(paymentId: string, live: boolean) {
  const [state, setState] = useState<StreamState>(EMPTY);
  const source = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!live) return;

    const es = new EventSource(`${PUBLIC_API}/api/payments/${paymentId}/stream`);
    source.current = es;

    es.addEventListener("skeleton", (e) => {
      const frame = JSON.parse((e as MessageEvent).data);
      setState((s) => ({ ...s, skeleton: frame.stages }));
    });

    es.addEventListener("hop", (e) => {
      const hop: Hop = JSON.parse((e as MessageEvent).data);
      setState((s) => ({ ...s, hops: [...s.hops, hop] }));
    });

    es.addEventListener("outcome", (e) => {
      const frame = JSON.parse((e as MessageEvent).data);
      setState((s) => ({
        ...s,
        tag: frame.tag,
        responseCode: frame.responseCode,
        deviations: frame.deviations,
      }));
    });

    es.addEventListener("explanation", (e) => {
      const explanation: Explained = JSON.parse((e as MessageEvent).data);
      setState((s) => ({ ...s, explanation }));
    });

    es.addEventListener("done", (e) => {
      const frame = JSON.parse((e as MessageEvent).data);
      setState((s) => ({ ...s, tokensSpent: frame.tokensSpent, done: true }));
      es.close();
    });

    es.onerror = () => {
      es.close();
      setState((s) => ({ ...s, done: true }));
    };

    return () => {
      es.close();
      source.current = null;
    };
  }, [paymentId, live]);

  return state;
}
