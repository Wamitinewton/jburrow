package io.jburrow.server.api.instance.application.port;

import io.jburrow.server.api.instance.domain.BuildIdentity;

/** The identity of the running build. */
public interface BuildIdentitySource {

    BuildIdentity current();
}
