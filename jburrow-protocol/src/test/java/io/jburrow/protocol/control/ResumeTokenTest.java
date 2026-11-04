package io.jburrow.protocol.control;

import static io.jburrow.protocol.control.ValidationAssertions.assertInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResumeTokenTest {

    /** 32 bytes 0x00 to 0x1f, base64url: an obviously made-up token, the one the specification's examples use. */
    static final String EXAMPLE = "jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    private static final String RULE = "resumeToken must match jbr_[A-Za-z0-9_-]{43}";

    @Test
    void acceptsTheExample() {
        assertThat(new ResumeToken(EXAMPLE).value()).isEqualTo(EXAMPLE).hasSize(47);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh", // 46 characters
                "jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8A", // 48 characters
                "jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+", // standard base64, not base64url
                "jbr_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh=", // padding
                "JBR_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", // prefix is case-sensitive
                "jb_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8x", // an authtoken's prefix
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8abcd", // no prefix
                ""
            })
    void refusesAnyOtherShape(String value) {
        assertInvalid(() -> new ResumeToken(value), RULE);
    }

    @Test
    void isRequired() {
        assertInvalid(() -> new ResumeToken(null), "resumeToken is required");
    }

    @Test
    void toStringNeverShowsTheToken() {
        ResumeToken token = new ResumeToken(EXAMPLE);

        assertThat(token.toString()).isEqualTo("ResumeToken[jbr_***]").doesNotContain("AAEC");
        assertThat(new Register("jb_x", List.of(TCP), token).toString())
                .contains("resume=ResumeToken[jbr_***]")
                .doesNotContain("AAEC");
    }

    @Test
    void generateDrawsThirtyTwoBytesAsFortySevenCharacters() {
        ResumeToken token = ResumeToken.generate(new SecureRandom());

        assertThat(token.value()).hasSize(47).startsWith("jbr_");
    }

    @Test
    void generateIsTheBase64urlOfTheDrawnBytes() {
        Random fixed = new Random(0) {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) i;
                }
            }
        };

        assertThat(ResumeToken.generate(fixed)).isEqualTo(new ResumeToken(EXAMPLE));
    }

    @Test
    void generatedTokensAreDistinct() {
        SecureRandom random = new SecureRandom();
        Set<ResumeToken> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(ResumeToken.generate(random));
        }

        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void sha256IsTheDigestOfTheTokensText() throws NoSuchAlgorithmException {
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(EXAMPLE.getBytes(StandardCharsets.US_ASCII));

        assertThat(new ResumeToken(EXAMPLE).sha256()).hasSize(32).isEqualTo(expected);
    }

    @Test
    void equalTokensHaveEqualDigests() {
        assertThat(new ResumeToken(EXAMPLE)).isEqualTo(new ResumeToken(EXAMPLE));
        assertThat(new ResumeToken(EXAMPLE).sha256()).isEqualTo(new ResumeToken(EXAMPLE).sha256());
    }

    private static final TunnelRequest TCP = new TunnelRequest("default", TunnelKind.TCP, null);
}
