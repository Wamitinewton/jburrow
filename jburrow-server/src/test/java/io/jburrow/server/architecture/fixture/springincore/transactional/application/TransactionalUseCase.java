package io.jburrow.server.architecture.fixture.springincore.transactional.application;

import org.springframework.transaction.annotation.Transactional;

public class TransactionalUseCase {

    @Transactional
    public void execute() {}
}
