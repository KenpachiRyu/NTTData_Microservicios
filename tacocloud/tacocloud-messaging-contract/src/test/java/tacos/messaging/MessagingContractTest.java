package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.messaging.event.OrderEvent;
import tacos.messaging.event.OrderEventPayload;
import tacos.messaging.event.OrderEventTaco;
import tacos.messaging.event.OrderEventType;

public class MessagingContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("Serialización y deserialización de OrderEvent v1")
  void testSerializationDeserialization() throws Exception {
    OrderEventTaco taco = OrderEventTaco.builder()
        .name("Carnitas Supreme")
        .ingredients(Arrays.asList("FLTO", "CARN", "SLSA"))
        .build();

    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId("ord-999")
        .placedAt(new Date())
        .status("CREATED")
        .total(new BigDecimal("18.50"))
        .deliveryCity("Guadalajara")
        .customerName("Héctor")
        .tacos(Arrays.asList(taco))
        .build();

    OrderEvent event = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .occurredAt(new Date())
        .correlationId(UUID.randomUUID().toString())
        .payload(payload)
        .build();

    String json = objectMapper.writeValueAsString(event);
    assertThat(json).isNotNull();

    OrderEvent deserialized = objectMapper.readValue(json, OrderEvent.class);
    assertThat(deserialized.getEventId()).isEqualTo(event.getEventId());
    assertThat(deserialized.getEventType()).isEqualTo(OrderEventType.ORDER_CREATED);
    assertThat(deserialized.getVersion()).isEqualTo("1.0");
    assertThat(deserialized.getPayload().getOrderId()).isEqualTo("ord-999");
    assertThat(deserialized.getPayload().getTacos().get(0).getName()).isEqualTo("Carnitas Supreme");
  }

  @Test
  @DisplayName("Prueba negativa: JSON no contiene PAN, CVV, password ni entidad User")
  void testNoSensitiveDataLeaked() throws Exception {
    OrderEventTaco taco = OrderEventTaco.builder()
        .name("Veggie")
        .ingredients(Arrays.asList("COTO", "TMTO", "LETC"))
        .build();

    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId("ord-123")
        .status("CREATED")
        .total(new BigDecimal("9.99"))
        .deliveryCity("Monterrey")
        .customerName("Carlos")
        .tacos(Arrays.asList(taco))
        .build();

    OrderEvent event = OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(OrderEventType.ORDER_CREATED)
        .version("1.0")
        .payload(payload)
        .build();

    String json = objectMapper.writeValueAsString(event);

    assertThat(json.toLowerCase())
        .doesNotContain("cvv")
        .doesNotContain("pan")
        .doesNotContain("ccnumber")
        .doesNotContain("password")
        .doesNotContain("paymenttoken")
        .doesNotContain("\"user\":");
  }

  @Test
  @DisplayName("Compatibilidad hacia adelante: ignora campos desconocidos en JSON")
  void testIgnoreUnknownProperties() throws Exception {
    String jsonWithExtraFields = "{\n" +
        "  \"eventId\": \"" + UUID.randomUUID() + "\",\n" +
        "  \"eventType\": \"ORDER_CREATED\",\n" +
        "  \"version\": \"1.0\",\n" +
        "  \"extraFieldV2\": \"some-future-data\",\n" +
        "  \"payload\": {\n" +
        "    \"orderId\": \"ord-future\",\n" +
        "    \"status\": \"CREATED\",\n" +
        "    \"futureMetadata\": { \"tag\": \"new-version\" }\n" +
        "  }\n" +
        "}";

    OrderEvent event = objectMapper.readValue(jsonWithExtraFields, OrderEvent.class);
    assertThat(event).isNotNull();
    assertThat(event.getPayload().getOrderId()).isEqualTo("ord-future");
  }

  @Test
  @DisplayName("Formato de eventId y correlationId son UUIDs válidos")
  void testUuidFormats() {
    OrderEvent event = OrderEvent.builder()
        .eventType(OrderEventType.ORDER_CREATED)
        .build();

    assertThat(UUID.fromString(event.getEventId())).isNotNull();
    assertThat(UUID.fromString(event.getCorrelationId())).isNotNull();
    assertThat(event.getVersion()).isEqualTo("1.0");
  }
}
