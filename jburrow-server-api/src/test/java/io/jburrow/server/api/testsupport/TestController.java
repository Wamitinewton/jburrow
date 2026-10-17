package io.jburrow.server.api.testsupport;

import io.jburrow.server.api.shared.error.ErrorCode;
import io.jburrow.server.api.shared.error.JBurrowException;
import io.jburrow.server.api.shared.web.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exercises each path through {@code GlobalExceptionHandler}. Mapped without the API prefix on purpose. */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    public record Signup(
            @NotBlank String name, @NotBlank @Email String email) {}

    @GetMapping
    public ApiResponse<String> ok() {
        return ApiResponse.ok("Test", "hello");
    }

    @GetMapping("/errors/{code}")
    public ApiResponse<Void> fail(@PathVariable ErrorCode code) {
        throw new TestException(code);
    }

    @PostMapping("/signups")
    public ApiResponse<Signup> signup(@Valid @RequestBody Signup signup) {
        return ApiResponse.ok("Signed up", signup);
    }

    @GetMapping("/pages")
    public ApiResponse<Integer> page(@RequestParam @Min(1) int number) {
        return ApiResponse.ok("Page", number);
    }

    @GetMapping("/constraint-violation")
    public ApiResponse<Void> constraintViolation() {
        throw new ConstraintViolationException(VALIDATOR.validate(new Signup("", "not-an-email")));
    }

    @GetMapping("/boom")
    public ApiResponse<Void> boom() {
        throw new RuntimeException("secret detail");
    }

    static final class TestException extends JBurrowException {
        TestException(ErrorCode code) {
            super(code, "Test failure " + code.code());
        }
    }
}
