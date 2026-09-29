package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.LegacyPaymentDataMigration;

public class PaymentTokenizationTest {

  private SimulatedPaymentGateway gateway;
  private PaymentApiController controller;
  private WebTestClient testClient;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setup() {
    gateway = new SimulatedPaymentGateway();
    controller = new PaymentApiController(gateway);
    testClient = WebTestClient.bindToController(controller).build();
    objectMapper = new ObjectMapper();
  }

  @Test
  public void shouldTokenizePaymentAndNeverStoreOrReturnCVV() throws Exception {
    TokenizeRequest request = new TokenizeRequest("4111 2222 3333 4444", "999", "12/28");

    testClient.post()
        .uri("/api/payment-methods/tokenize")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isCreated()
        .expectBody(TokenizeResponse.class)
        .value(response -> {
          assertNotNull(response.getPaymentToken());
          assertTrue(response.getPaymentToken().startsWith("tok_"));
          assertEquals("VISA", response.getBrand());
          assertEquals("4444", response.getLast4());
          assertEquals("12/28", response.getExpiration());
        });

    TokenizeResponse res = gateway.tokenize(request).block();
    String json = objectMapper.writeValueAsString(res);

    assertFalse(json.contains("999"), "CVV must never appear in tokenized response");
    assertFalse(json.contains("4111222233334444"), "Full PAN must never appear in response");
    assertFalse(json.contains("cvv"), "CVV field must not exist in response");
  }

  @Test
  public void orderAndPaymentMethodToStringMustNotLeakSensitivePaymentDataInLogs() {
    TacoOrder order = new TacoOrder();
    order.setId("order-safe-1");
    order.setPaymentToken("tok_secret_token_12345");
    order.setCardBrand("VISA");
    order.setCardLast4("4444");
    order.setCcCVV("999"); // Discarded internally

    String orderLog = order.toString();

    // Order logs sent to kitchen/console must contain zero PAN, CVV, or raw payment token
    assertFalse(orderLog.contains("tok_secret_token_12345"), "Token must be excluded from toString");
    assertFalse(orderLog.contains("999"), "CVV must not exist in toString");
    assertFalse(orderLog.contains("ccCVV"), "ccCVV field must not appear in log");

    User user = new User("alice", "hash", "Alice", "St", "City", "ST", "Zip", "Phone", "alice@example.com");
    PaymentMethod pm = new PaymentMethod(user, "tok_pm_secret_888", "VISA", "4444", "12/28");
    String pmLog = pm.toString();

    assertFalse(pmLog.contains("tok_pm_secret_888"), "Token must be excluded from PaymentMethod toString");
    assertFalse(pmLog.contains("ccCVV"), "CVV must not exist in PaymentMethod");
  }

  @Test
  public void legacyPaymentDataMigrationShouldUnsetLegacyFields() {
    ReactiveMongoTemplate mongoTemplate = Mockito.mock(ReactiveMongoTemplate.class);
    LegacyPaymentDataMigration migration = new LegacyPaymentDataMigration(mongoTemplate);

    UpdateResult updateResult = UpdateResult.acknowledged(10, 10L, null);
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq("tacoOrder")))
        .thenReturn(Mono.just(updateResult));
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq("paymentMethod")))
        .thenReturn(Mono.just(updateResult));

    StepVerifier.create(migration.purgeAllLegacyPaymentData())
        .verifyComplete();

    verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq("tacoOrder"));
    verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class), eq("paymentMethod"));
  }
}
