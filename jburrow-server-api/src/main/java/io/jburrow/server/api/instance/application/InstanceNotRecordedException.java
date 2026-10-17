package io.jburrow.server.api.instance.application;

import io.jburrow.server.api.instance.domain.InstanceId;
import io.jburrow.server.api.shared.error.ErrorCode;
import io.jburrow.server.api.shared.error.NotFoundException;

/** The running instance has no startup record, which only happens when the startup write failed. */
public final class InstanceNotRecordedException extends NotFoundException {

    public InstanceNotRecordedException(InstanceId id) {
        super(ErrorCode.INSTANCE_NOT_RECORDED, "Instance " + id + " was not recorded at startup.");
    }
}
