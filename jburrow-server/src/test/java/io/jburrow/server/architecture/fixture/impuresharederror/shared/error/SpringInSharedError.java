package io.jburrow.server.architecture.fixture.impuresharederror.shared.error;

import org.springframework.http.HttpStatus;

public class SpringInSharedError extends RuntimeException {

    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
