export interface ModelTimeStat {
  model: string;
  displayName: string;
  avgMs: number;
  sampleCount: number;
}

export interface DailyAvgStat {
  date: string;
  avgMs: number;
  count: number;
}

export interface ModelStats {
  imageModelStats: ModelTimeStat[];
  textModelStats: ModelTimeStat[];
  dailyImageAvg: DailyAvgStat[];
  computedAt: string;
}
