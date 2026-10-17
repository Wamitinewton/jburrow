package io.jburrow.server.config;

import io.jburrow.server.api.instance.adapter.persistence.JpaServerInstanceStore;
import io.jburrow.server.api.instance.adapter.persistence.ServerInstanceJpaRepository;
import io.jburrow.server.api.instance.application.DescribeInstance;
import io.jburrow.server.api.instance.application.RecordStartup;
import io.jburrow.server.api.instance.application.port.BuildIdentitySource;
import io.jburrow.server.api.instance.application.port.ServerInstanceStore;
import io.jburrow.server.api.instance.domain.InstanceId;
import java.time.Clock;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Constructs every use case with {@code new} and hands it its ports.
 *
 * <p>Spring Data repositories are found by Boot under the application's package; {@code @EnableJpaRepositories} here
 * would put Spring Data in the composition root, which the layer rules forbid. Entity scanning is narrowed because
 * the architecture fixtures in test sources include an {@code @Entity}.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = "io.jburrow.server.api")
class UseCaseConfiguration {

    @Bean
    InstanceId currentInstanceId() {
        return InstanceId.random();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionalUseCases transactionalUseCases(PlatformTransactionManager transactionManager) {
        return new TransactionalUseCases(new TransactionTemplate(transactionManager));
    }

    @Bean
    ServerInstanceStore serverInstanceStore(ServerInstanceJpaRepository repository) {
        return new JpaServerInstanceStore(repository);
    }

    @Bean
    RecordStartup recordStartup(
            ServerInstanceStore store, BuildIdentitySource identity, Clock clock, InstanceId currentInstanceId) {
        return new RecordStartup(store, identity, clock, currentInstanceId);
    }

    @Bean
    DescribeInstance describeInstance(ServerInstanceStore store, InstanceId currentInstanceId) {
        return new DescribeInstance(store, currentInstanceId);
    }

    @Bean
    StartupRecorder startupRecorder(RecordStartup recordStartup, TransactionalUseCases transactions) {
        return new StartupRecorder(transactions.wrap(recordStartup::execute));
    }
}
