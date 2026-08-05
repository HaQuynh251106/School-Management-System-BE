package com.sse.app.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "sse.seed.enabled", havingValue = "true", matchIfMissing = true)
public class CanonicalDatasetSeeder {
    private static final Set<String> ALLOWED_DATASETS = Set.of("baseline", "demo", "scenario");

    @Bean
    @Order(20)
    ApplicationRunner canonicalDatasetRunner(
            DataSource dataSource,
            @Value("${sse.seed.dataset:baseline}") String configuredDataset) {
        return args -> {
            String dataset = configuredDataset.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_DATASETS.contains(dataset)) {
                throw new IllegalArgumentException(
                        "Unsupported sse.seed.dataset '" + configuredDataset +
                                "'. Use baseline, demo or scenario.");
            }
            if ("baseline".equals(dataset)) {
                log.info("[seed] Canonical dataset disabled; reference data only.");
                return;
            }

            execute(dataSource, "db/seed/demo.sql");
            if ("scenario".equals(dataset)) {
                execute(dataSource, "db/seed/scenario.sql");
            }
            log.info("[seed] Canonical '{}' dataset is ready.", dataset);
        };
    }

    private void execute(DataSource dataSource, String resourcePath) {
        log.info("[seed] Applying {}.", resourcePath);
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource(resourcePath));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }
}
