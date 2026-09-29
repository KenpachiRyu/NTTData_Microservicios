package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import javax.validation.Valid;

public class RestExceptionHandlerTest {

  @RestController
  @RequestMapping("/test-errors")
  static class DummyErrorController {

    @PostMapping("/validate")
    public Mono<String> validate(@Valid @RequestBody OrderCreateRequest request) {
      return Mono.just("ok");
    }

    @GetMapping("/not-found")
    public Mono<String> notFound() {
      return Mono.error(new IngredientNotFoundException("UNKNOWN_ING"));
    }

    @GetMapping("/conflict")
    public Mono<String> conflict() {
      return Mono.error(new DuplicateKeyException("E11000 duplicate key error"));
    }

    @GetMapping("/business-rule")
    public Mono<String> businessRule() {
      return Mono.error(new BusinessRuleException("MINIMUM_ORDER_NOT_MET", "Orders must include at least 1 taco"));
    }

    @GetMapping("/access-denied")
    public Mono<String> accessDenied() {
      return Mono.error(new AccessDeniedException("Forbidden"));
    }
  }

  private WebTestClient testClient;

  @BeforeEach
  public void setup() {
    testClient = WebTestClient.bindToController(new DummyErrorController())
        .controllerAdvice(new RestExceptionHandler())
        .build();
  }

  @Test
  public void shouldReturnUniformProblemDetailsForValidationError() {
    // Missing required fields
    OrderCreateRequest invalidRequest = new OrderCreateRequest();

    testClient.post()
        .uri("/test-errors/validate")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalidRequest)
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectBody(ApiProblem.class)
        .value(problem -> {
          assertEquals(400, problem.getStatus());
          assertEquals("Validation Error", problem.getTitle());
          assertEquals("VALIDATION_FAILED", problem.getCode());
          assertEquals("/test-errors/validate", problem.getInstance());
          assertNotNull(problem.getCorrelationId());
          assertFalse(problem.getViolations().isEmpty());

          // Verify violations detail fields
          boolean hasDeliveryNameViolation = problem.getViolations().stream()
              .anyMatch(v -> "deliveryName".equals(v.getField()));
          assertTrue(hasDeliveryNameViolation);
        });
  }

  @Test
  public void shouldReturnUniformProblemDetailsForNotFound() {
    testClient.get()
        .uri("/test-errors/not-found")
        .exchange()
        .expectStatus().isNotFound()
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectBody(ApiProblem.class)
        .value(problem -> {
          assertEquals(404, problem.getStatus());
          assertEquals("Resource Not Found", problem.getTitle());
          assertEquals("RESOURCE_NOT_FOUND", problem.getCode());
          assertTrue(problem.getDetail().contains("UNKNOWN_ING"));
          assertEquals("/test-errors/not-found", problem.getInstance());
        });
  }

  @Test
  public void shouldReturnUniformProblemDetailsForConflictWithoutLeakingDriver() {
    testClient.get()
        .uri("/test-errors/conflict")
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectBody(ApiProblem.class)
        .value(problem -> {
          assertEquals(409, problem.getStatus());
          assertEquals("Conflict", problem.getTitle());
          assertEquals("DUPLICATE_RESOURCE", problem.getCode());
          // Ensure internal driver string (e.g. E11000) does not leak in detail
          assertFalse(problem.getDetail().contains("E11000"));
          assertEquals("The specified resource or key already exists", problem.getDetail());
        });
  }

  @Test
  public void shouldReturnUniformProblemDetailsForBusinessRuleViolation() {
    testClient.get()
        .uri("/test-errors/business-rule")
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectBody(ApiProblem.class)
        .value(problem -> {
          assertEquals(422, problem.getStatus());
          assertEquals("Unprocessable Entity", problem.getTitle());
          assertEquals("MINIMUM_ORDER_NOT_MET", problem.getCode());
          assertEquals("Orders must include at least 1 taco", problem.getDetail());
          assertEquals("/test-errors/business-rule", problem.getInstance());
        });
  }

  @Test
  public void shouldReturnUniformProblemDetailsForAccessDenied() {
    testClient.get()
        .uri("/test-errors/access-denied")
        .exchange()
        .expectStatus().isForbidden()
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectBody(ApiProblem.class)
        .value(problem -> {
          assertEquals(403, problem.getStatus());
          assertEquals("Forbidden", problem.getTitle());
          assertEquals("ACCESS_DENIED", problem.getCode());
          assertEquals("/test-errors/access-denied", problem.getInstance());
        });
  }
}
