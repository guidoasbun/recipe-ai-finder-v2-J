import { request } from "node:https";
import { COGNITO_REGION } from "@/lib/constants";

const COGNITO_HOSTNAME = `cognito-idp.${COGNITO_REGION}.amazonaws.com`;

export function cognitoPost(
  target: string,
  body: Record<string, unknown>
): Promise<{ ok: boolean; status: number; data: Record<string, unknown> }> {
  return new Promise((resolve, reject) => {
    const bodyStr = JSON.stringify(body);
    const req = request(
      {
        hostname: COGNITO_HOSTNAME,
        path: "/",
        method: "POST",
        headers: {
          "Content-Type": "application/x-amz-json-1.1",
          "X-Amz-Target": target,
          "Content-Length": Buffer.byteLength(bodyStr),
        },
      },
      (res) => {
        let raw = "";
        res.on("data", (chunk) => (raw += chunk));
        res.on("end", () => {
          const data = (() => {
            try {
              return JSON.parse(raw);
            } catch {
              return {};
            }
          })();
          resolve({ ok: (res.statusCode ?? 500) < 300, status: res.statusCode ?? 500, data });
        });
      }
    );
    req.on("error", reject);
    req.write(bodyStr);
    req.end();
  });
}
