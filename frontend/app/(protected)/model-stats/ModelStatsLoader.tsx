"use client";

import { useEffect, useState } from "react";
import ModelStatsChart from "./ModelStatsChart";
import { ModelStats } from "@/types/stats";

function LoadingSkeleton() {
  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Model Stats</h1>
        <span className="text-sm text-gray-400">Computing stats…</span>
      </div>
      {[1, 2, 3].map((i) => (
        <div
          key={i}
          className="h-80 animate-pulse rounded-2xl border border-gray-200 bg-gray-100"
        />
      ))}
    </div>
  );
}

export default function ModelStatsLoader() {
  const [stats, setStats] = useState<ModelStats | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    const es = new EventSource("/api/backend/api/stats/stream");

    es.addEventListener("stats-ready", (e: MessageEvent) => {
      setStats(JSON.parse(e.data) as ModelStats);
      es.close();
    });

    es.onerror = () => {
      setError(true);
      es.close();
    };

    return () => es.close();
  }, []);

  if (error) {
    return (
      <div>
        <h1 className="mb-4 text-2xl font-bold text-gray-900">Model Stats</h1>
        <p className="text-gray-500">
          Stats are unavailable right now.{" "}
          <button
            className="underline hover:text-gray-700"
            onClick={() => window.location.reload()}
          >
            Retry
          </button>
        </p>
      </div>
    );
  }

  if (!stats) {
    return <LoadingSkeleton />;
  }

  return <ModelStatsChart stats={stats} />;
}
