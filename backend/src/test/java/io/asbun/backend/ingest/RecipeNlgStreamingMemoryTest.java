package io.asbun.backend.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory-safety check for streaming the full RecipeNLG dataset. Disabled by default (needs the
 * ~2.1 GB local file); enable with -DrecipenlgFile=/abs/path and a small -Xmx to prove the
 * stream path never accumulates the dataset.
 *
 *   ./mvnw test -Dtest=RecipeNlgStreamingMemoryTest \
 *     -DrecipenlgFile=$PWD/data/recipeNGL/RecipeNLG_dataset.csv \
 *     -DargLine="-Xmx256m"
 */
class RecipeNlgStreamingMemoryTest {

    @Test
    @EnabledIfSystemProperty(named = "recipenlgFile", matches = ".+")
    void streamsFullDatasetInBoundedMemory() {
        Path file = Path.of(System.getProperty("recipenlgFile"));
        RecipeNlgCsvSource source = new RecipeNlgCsvSource(file, Integer.MAX_VALUE, 0);

        AtomicLong count = new AtomicLong();
        // Consume and discard each recipe — if stream() accumulated, this would OOM under -Xmx256m.
        source.stream(r -> {
            count.incrementAndGet();
            if (count.get() % 500_000 == 0) {
                System.out.println("streamed " + count.get());
            }
        });

        // The dataset has ~2.23M rows; assert we streamed a large number without OOM.
        assertThat(count.get()).isGreaterThan(2_000_000L);
    }
}
