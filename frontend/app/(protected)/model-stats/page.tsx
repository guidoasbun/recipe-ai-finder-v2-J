import { getSession } from "@/lib/session";
import { apiFetch } from "@/lib/api";
import { ModelStats } from "@/types/stats";
import ModelStatsChart from "./ModelStatsChart";

export default async function ModelStatsPage() {
  const token = await getSession();
  const res = await apiFetch("/api/stats/models", {}, token ?? undefined);

  if (!res.ok) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold text-gray-900">Model Stats</h1>
        <p className="text-gray-500">Stats are unavailable right now. Try again shortly.</p>
      </div>
    );
  }

  const stats: ModelStats = await res.json();

  return <ModelStatsChart stats={stats} />;
}
