package io.jburrow.server.config;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs use cases inside a transaction, so {@code application} code never carries {@code @Transactional}. */
final class TransactionalUseCases {

    private final TransactionTemplate transactions;

    TransactionalUseCases(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    <T> Supplier<T> wrap(Supplier<T> useCase) {
        return () -> transactions.execute(status -> useCase.get());
    }
}
