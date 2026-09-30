package tacos.web.api.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OutboxEvent;
import tacos.TacoOrder;
import tacos.messaging.OrderEventMapper;
import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventType;

public class CorrelationIdTest {

  private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

  @Test
  @DisplayName("Request sin header X-Correlation-Id genera automáticamente un UUID válido en respuesta")
  void testMissingHeaderGeneratesUuid() {
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders").build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    WebFilterChain chain = ex -> {
      assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNotNull();
      return Mono.empty();
    };

    StepVerifier.create(filter.filter(exchange, chain))
        .verifyComplete();

    String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationContext.CORRELATION_ID_HEADER);
    assertThat(responseHeader).isNotNull();
    assertThat(UUID.fromString(responseHeader)).isNotNull();
    // MDC debe quedar limpio tras terminar
    assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("Request con header X-Correlation-Id válido preserva el valor original")
  void testValidHeaderPreserved() {
    String validId = "custom-corr-12345_abc";
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
        .header(CorrelationContext.CORRELATION_ID_HEADER, validId)
        .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    WebFilterChain chain = ex -> {
      assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo(validId);
      return Mono.empty();
    };

    StepVerifier.create(filter.filter(exchange, chain))
        .verifyComplete();

    String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationContext.CORRELATION_ID_HEADER);
    assertThat(responseHeader).isEqualTo(validId);
    assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("Header malicioso con CRLF o caracteres ilegales es reemplazado por UUID seguro")
  void testMaliciousHeaderReplaced() {
    String maliciousId = "invalid\r\nInjected-Header: evil";
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
        .header(CorrelationContext.CORRELATION_ID_HEADER, maliciousId)
        .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
        .verifyComplete();

    String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationContext.CORRELATION_ID_HEADER);
    assertThat(responseHeader).isNotNull();
    assertThat(responseHeader).isNotEqualTo(maliciousId);
    assertThat(UUID.fromString(responseHeader)).isNotNull();
  }

  @Test
  @DisplayName("Header con longitud excesiva (> 64 caracteres) es reemplazado por UUID seguro")
  void testOverlyLongHeaderReplaced() {
    String longId = "a".repeat(70);
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/orders")
        .header(CorrelationContext.CORRELATION_ID_HEADER, longId)
        .build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);

    StepVerifier.create(filter.filter(exchange, ex -> Mono.empty()))
        .verifyComplete();

    String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationContext.CORRELATION_ID_HEADER);
    assertThat(responseHeader).isNotNull();
    assertThat(responseHeader).isNotEqualTo(longId);
    assertThat(responseHeader.length()).isLessThanOrEqualTo(64);
  }

  @Test
  @DisplayName("CorrelationId se propaga correctamente a OrderEvent")
  void testCorrelationIdPropagatesToOrderEvent() {
    TacoOrder order = new TacoOrder();
    order.setId("order-corr-test");
    String correlationId = "corr-test-999";

    OrderEvent event = OrderEventMapper.toOrderEvent(order, OrderEventType.ORDER_CREATED, correlationId);

    assertThat(event).isNotNull();
    assertThat(event.getCorrelationId()).isEqualTo(correlationId);
  }
}
