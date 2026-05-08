"use client";

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Cell,
  LineChart,
  Line,
  ResponsiveContainer,
} from "recharts";
import { ModelStats } from "@/types/stats";

const IMAGE_MODEL_COLORS: Record<string, string> = {
  STABILITY_CORE: "#f97316",
  GPT_IMAGE_1_5: "#22c55e",
  GOOGLE_IMAGEN_4: "#a855f7",
  GOOGLE_IMAGEN_4_FAST: "#3b82f6",
};

const TEXT_MODEL_COLORS: Record<string, string> = {
  CLAUDE_HAIKU: "#f97316",
  CLAUDE_SONNET: "#ef4444",
  AMAZON_TITAN: "#22c55e",
  LLAMA3: "#a855f7",
  NOVA_LITE: "#3b82f6",
};

function formatMs(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.round(ms)}ms`;
}

function formatDate(dateStr: string): string {
  const [, month, day] = dateStr.split("-");
  const months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
  return `${months[parseInt(month) - 1]} ${parseInt(day)}`;
}

function formatComputedAt(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

interface Props {
  stats: ModelStats;
}

export default function ModelStatsChart({ stats }: Props) {
  const dailyData = stats.dailyImageAvg.map((d) => ({
    date: formatDate(d.date),
    avgMs: d.count > 0 ? d.avgMs : null,
    count: d.count,
  }));

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Model Stats</h1>
        {stats.computedAt && (
          <span className="text-sm text-gray-600">
            Last updated: {formatComputedAt(stats.computedAt)}
          </span>
        )}
      </div>

      {/* Image generation time */}
      <div className="rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-1 text-lg font-semibold text-gray-800">
          Image Generation Time by Model
        </h2>
        <p className="mb-4 text-sm text-gray-500">Average time to generate an image</p>
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={stats.imageModelStats} margin={{ top: 4, right: 16, left: 8, bottom: 4 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
            <XAxis dataKey="displayName" tick={{ fontSize: 12 }} />
            <YAxis
              tickFormatter={(v) => formatMs(v)}
              tick={{ fontSize: 12 }}
              width={60}
            />
            <Tooltip
              contentStyle={{ borderRadius: "8px" }}
              labelStyle={{ color: "#111827" }}
              itemStyle={{ color: "#374151" }}
              formatter={(value, _, entry) => [
                `${formatMs(Number(value ?? 0))} (${entry.payload.sampleCount} recipes)`,
                "Avg time",
              ]}
            />
            <Bar dataKey="avgMs" radius={[4, 4, 0, 0]}>
              {stats.imageModelStats.map((entry) => (
                <Cell
                  key={entry.model}
                  fill={IMAGE_MODEL_COLORS[entry.model] ?? "#6b7280"}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Text generation time */}
      <div className="rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-1 text-lg font-semibold text-gray-800">
          Text Generation Time by Model
        </h2>
        <p className="mb-4 text-sm text-gray-500">Average time to generate recipe text</p>
        <ResponsiveContainer width="100%" height={280}>
          <BarChart data={stats.textModelStats} margin={{ top: 4, right: 16, left: 8, bottom: 4 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
            <XAxis dataKey="displayName" tick={{ fontSize: 12 }} />
            <YAxis
              tickFormatter={(v) => formatMs(v)}
              tick={{ fontSize: 12 }}
              width={60}
            />
            <Tooltip
              contentStyle={{ borderRadius: "8px" }}
              labelStyle={{ color: "#111827" }}
              itemStyle={{ color: "#374151" }}
              formatter={(value, _, entry) => [
                `${formatMs(Number(value ?? 0))} (${entry.payload.sampleCount} recipes)`,
                "Avg time",
              ]}
            />
            <Bar dataKey="avgMs" radius={[4, 4, 0, 0]}>
              {stats.textModelStats.map((entry) => (
                <Cell
                  key={entry.model}
                  fill={TEXT_MODEL_COLORS[entry.model] ?? "#6b7280"}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Daily trend */}
      <div className="rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-1 text-lg font-semibold text-gray-800">
          Image Generation Trend (Last 30 Days)
        </h2>
        <p className="mb-4 text-sm text-gray-500">Daily average across all image models</p>
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={dailyData} margin={{ top: 4, right: 16, left: 8, bottom: 4 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 11 }}
              interval={4}
            />
            <YAxis
              tickFormatter={(v) => formatMs(v)}
              tick={{ fontSize: 12 }}
              width={60}
            />
            <Tooltip
              contentStyle={{ borderRadius: "8px" }}
              labelStyle={{ color: "#111827" }}
              itemStyle={{ color: "#374151" }}
              formatter={(value, _, entry) => [
                `${formatMs(Number(value ?? 0))} (${entry.payload.count} recipes)`,
                "Avg time",
              ]}
            />
            <Line
              type="monotone"
              dataKey="avgMs"
              stroke="#f97316"
              strokeWidth={2}
              dot={false}
              connectNulls={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
