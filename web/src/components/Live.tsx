"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

/**
 * Keeps a server-rendered screen honest.
 *
 * `force-dynamic` governs how a page is rendered on the server, not how long the App Router keeps
 * its payload on the client. Navigating back to a screen replays the cached one, so the debt queue
 * could show a payment as paid while the counter beside it, which polls, said the debt was open.
 * On a console whose whole point is a number that moves, a stale table is worse than a slow one.
 *
 * Refreshing on mount and on focus costs one request per navigation and removes the entire class
 * of problem.
 */
export function Live() {
  const router = useRouter();

  useEffect(() => {
    router.refresh();
    const onFocus = () => router.refresh();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [router]);

  return null;
}
