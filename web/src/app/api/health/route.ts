// The container healthcheck target. Deliberately static: it must not depend on pxe-api, or the
// web container would report unhealthy whenever the API is merely slower to start.
export const dynamic = "force-static";

export function GET() {
  return Response.json({ status: "UP", service: "pxe-web" });
}
