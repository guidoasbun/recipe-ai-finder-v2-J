package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Running aggregate of model latency stats. Unlike {@link ModelStatsDto} (which is the derived,
 * client-facing view), this record stores raw sums and counts so that a single recipe/image
 * creation can update it in O(1) time — no full table scan required.
 *
 * <p>Averages are never stored; they are derived as {@code sumMs / count} when the DTO is built.
 * Storing sum+count keeps updates commutative and associative, so concurrent increments compose
 * cleanly.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@link #textModels} / {@link #imageModels}: keyed by the enum {@code name()}.</li>
 *   <li>{@link #dailyImage}: keyed by ISO date string ({@code yyyy-MM-dd}, UTC). Only the trailing
 *       window of days is retained; older buckets are pruned on write.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StatsAggregate {

    private Map<String, Bucket> textModels = new HashMap<>();
    private Map<String, Bucket> imageModels = new HashMap<>();
    private Map<String, Bucket> dailyImage = new HashMap<>();

    /** ISO-8601 timestamp of the last mutation, surfaced to clients as {@code computedAt}. */
    private String updatedAt;

    /** A running sum of latencies plus the number of samples that produced it. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bucket {
        private double sumMs;
        private long count;

        public void add(long ms) {
            this.sumMs += ms;
            this.count += 1;
        }

        public double avg() {
            return count == 0 ? 0 : sumMs / count;
        }
    }
}
