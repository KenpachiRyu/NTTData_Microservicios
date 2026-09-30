package tacos.web.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.web.api.versioning.ApiDeprecationFilter;

public class OpenApiContractTest {

  @Test
  @DisplayName("TC-35: openapi.yaml specification exists and is non-empty")
  void openApiSpecificationExists() throws Exception {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("openapi.yaml")) {
      assertThat(is).isNotNull();
      String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(content).isNotEmpty();
      assertThat(content).contains("openapi: 3.0.3");
    }
  }

  @Test
  @DisplayName("TC-35: openapi.yaml documents required versioned v1 routes and headers")
  void openApiRoutesAndHeadersDocumented() throws Exception {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("openapi.yaml")) {
      String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);

      assertThat(content).contains("/api/v1/orders:");
      assertThat(content).contains("/api/v1/ingredients:");
      assertThat(content).contains("/api/v1/tacos:");
      assertThat(content).contains("Idempotency-Key");
      assertThat(content).contains("X-Correlation-Id");
    }
  }

  @Test
  @DisplayName("TC-35: openapi.yaml documents key schemas and does NOT leak sensitive payment data in responses")
  void openApiSchemasAndSecurity() throws Exception {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("openapi.yaml")) {
      String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);

      assertThat(content).contains("OrderCreateRequest:");
      assertThat(content).contains("OrderResponse:");
      assertThat(content).contains("ApiProblem:");
      assertThat(content).contains("Ingredient:");
      assertThat(content).contains("Taco:");

      // Verify that sensitive fields are not in OrderResponse
      int orderResponseIndex = content.indexOf("OrderResponse:");
      int nextSchemaIndex = content.indexOf("ApiProblem:", orderResponseIndex);
      String orderResponseSection = content.substring(orderResponseIndex, nextSchemaIndex);

      assertThat(orderResponseSection).doesNotContain("ccNumber");
      assertThat(orderResponseSection).doesNotContain("ccCVV");
      assertThat(orderResponseSection).doesNotContain("cvv");
      assertThat(orderResponseSection).doesNotContain("pan");
    }
  }

  @Test
  @DisplayName("TC-35: ApiDeprecationFilter adds RFC 7234 Warning header on unversioned legacy routes")
  void deprecationFilterAddsWarningOnLegacyRoutes() {
    ApiDeprecationFilter filter = new ApiDeprecationFilter();
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    WebFilterChain chain = ex -> Mono.empty();

    StepVerifier.create(filter.filter(exchange, chain))
        .verifyComplete();

    assertThat(exchange.getResponse().getHeaders().getFirst("Warning"))
        .contains("299")
        .contains("Deprecated API endpoint");
  }

  @Test
  @DisplayName("TC-35: ApiDeprecationFilter does NOT add Warning header on versioned /api/v1 routes")
  void deprecationFilterOmitsWarningOnVersionedRoutes() {
    ApiDeprecationFilter filter = new ApiDeprecationFilter();
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    WebFilterChain chain = ex -> Mono.empty();

    StepVerifier.create(filter.filter(exchange, chain))
        .verifyComplete();

    assertThat(exchange.getResponse().getHeaders().getFirst("Warning")).isNull();
  }
}
