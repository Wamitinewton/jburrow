package io.jburrow.protocol.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BodyFramingTest {

    @Test
    void wireNamesAreTheSpecifications() {
        assertThat(BodyFraming.values())
                .extracting(BodyFraming::wireName)
                .containsExactly("empty", "length", "chunked");
    }

    @Test
    void fromWireNameIsExact() {
        assertThat(BodyFraming.fromWireName("empty")).contains(BodyFraming.EMPTY);
        assertThat(BodyFraming.fromWireName("length")).contains(BodyFraming.LENGTH);
        assertThat(BodyFraming.fromWireName("chunked")).contains(BodyFraming.CHUNKED);
        assertThat(BodyFraming.fromWireName("Chunked")).isEmpty();
        assertThat(BodyFraming.fromWireName("gzip")).isEmpty();
        assertThat(BodyFraming.fromWireName("")).isEmpty();
        assertThat(BodyFraming.fromWireName(null)).isEmpty();
    }
}
