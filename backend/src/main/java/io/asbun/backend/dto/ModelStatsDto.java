package io.asbun.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelStatsDto {

    private List<ModelTimeStat> imageModelStats;
    private List<ModelTimeStat> textModelStats;
    private List<DailyAvgStat> dailyImageAvg;
    private String computedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelTimeStat {
        private String model;
        private String displayName;
        private double avgMs;
        private long sampleCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyAvgStat {
        private String date;
        private double avgMs;
        private long count;
    }
}
