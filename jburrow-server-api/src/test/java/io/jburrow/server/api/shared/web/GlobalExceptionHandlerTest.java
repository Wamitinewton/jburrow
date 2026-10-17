package io.jburrow.server.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.jburrow.server.api.shared.error.ErrorCode;
import io.jburrow.server.api.testsupport.WebSliceTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest
@ContextConfiguration(classes = WebSliceTestConfiguration.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void prefixIsAppliedToJBurrowControllers() {
        assertThat(mvc.get().uri("/api/v1/test")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"success": true, "message": "Test", "data": "hello"}
                        """);
    }

    @Test
    void unprefixedPathIsNotFound() {
        assertThat(mvc.get().uri("/test")).hasStatus(404).bodyJson().isLenientlyEqualTo("""
                        {"success": false, "code": "NOT_FOUND", "fieldErrors": [], "path": "/test"}
                        """);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void eachErrorCodeRendersItsStatusAndCode(ErrorCode code) {
        String path = "/api/v1/test/errors/" + code.name();

        assertThat(mvc.get().uri(path)).hasStatus(code.httpStatus()).bodyJson().isLenientlyEqualTo("""
                        {"success": false, "code": "%s", "message": "Test failure %s", "fieldErrors": [], "path": "%s"}
                        """.formatted(
                        code.code(), code.code(), path));
    }

    @Test
    void errorBodyHasExactlyTheDocumentedFieldsInOrder() {
        assertThat(mvc.get().uri("/api/v1/test/errors/NOT_FOUND"))
                .bodyText()
                .matches("^\\{\"success\":false,\"code\":\"NOT_FOUND\",\"message\":\"[^\"]*\",\"fieldErrors\":\\[],"
                        + "\"timestamp\":\"[^\"]+\",\"path\":\"/api/v1/test/errors/NOT_FOUND\"}$");
    }

    @Test
    void invalidBodyListsFieldErrors() {
        assertThat(mvc.post()
                        .uri("/api/v1/test/signups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "email": "not-an-email"}
                                """))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                    assertThat(json)
                            .extractingPath("$.fieldErrors[*].field")
                            .asArray()
                            .containsExactlyInAnyOrder("name", "email");
                });
    }

    @Test
    void invalidRequestParameterListsFieldErrors() {
        assertThat(mvc.get().uri("/api/v1/test/pages?number=0"))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                    assertThat(json)
                            .extractingPath("$.fieldErrors[*].field")
                            .asArray()
                            .containsExactly("number");
                });
    }

    @Test
    void constraintViolationListsFieldErrors() {
        assertThat(mvc.get().uri("/api/v1/test/constraint-violation"))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
                    assertThat(json)
                            .extractingPath("$.fieldErrors[*].field")
                            .asArray()
                            .containsExactlyInAnyOrder("name", "email");
                });
    }

    @Test
    void unexpectedExceptionLeaksNoDetail() {
        assertThat(mvc.get().uri("/api/v1/test/boom"))
                .hasStatus(500)
                .satisfies(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret detail")
                        .doesNotContain("RuntimeException"))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"success": false, "code": "INTERNAL_ERROR", "message": "An unexpected error occurred."}
                        """);
    }
}
