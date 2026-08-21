package com.sse.app.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "sse.seed.enabled", havingValue = "true", matchIfMissing = false)
public class CanonicalDatasetSeeder {
    private static final Set<String> ALLOWED_DATASETS = Set.of(
            "baseline", "demo", "scenario", "full-demo");

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
                                "'. Use baseline, demo, scenario or full-demo.");
            }
            if ("baseline".equals(dataset)) {
                log.info("[seed] Canonical dataset disabled; reference data only.");
                return;
            }

            if ("full-demo".equals(dataset)) {
                executePostgresBatch(dataSource, "db/seed/full-demo-reset.sql");
                executePostgresBatch(dataSource, "db/seed/full-demo.sql");
            } else {
                execute(dataSource, "db/seed/demo.sql");
            }
            if ("scenario".equals(dataset)) {
                execute(dataSource, "db/seed/scenario.sql");
            }
            log.info("[seed] Canonical '{}' dataset is ready.", dataset);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "sse.seed.exit-after-run", havingValue = "true")
    ApplicationListener<ApplicationReadyEvent> exitAfterSeed(
            ConfigurableApplicationContext context) {
        return event -> {
            log.info("[seed] Seed-only process completed; shutting down cleanly.");
            SpringApplication.exit(context);
        };
    }

    private void execute(DataSource dataSource, String resourcePath) {
        log.info("[seed] Applying {}.", resourcePath);
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource(resourcePath));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    /**
     * Full Demo uses PostgreSQL DO blocks and an explicit transaction. Spring's
     * generic SQL splitter treats semicolons inside dollar-quoted blocks as
     * statement delimiters, so execute this trusted classpath resource as one
     * PostgreSQL batch instead.
     */
    private void executePostgresBatch(DataSource dataSource, String resourcePath)
            throws Exception {
        log.info("[seed] Applying {} as one PostgreSQL transaction batch.", resourcePath);
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        new JdbcTemplate(dataSource).execute(sql);
    }
}
