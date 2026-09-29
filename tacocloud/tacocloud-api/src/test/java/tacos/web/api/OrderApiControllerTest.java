package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.InOrder;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

public class OrderApiControllerTest {

  private OrderRepository orderRepo;
  private OrderMessagingService messagingService;
  private EmailOrderService emailOrderService;
  private IngredientRepository ingredientRepo;
  private OrderApiController controller;
  private WebTestClient testClient;

  @BeforeEach
  public void setup() {
    orderRepo = Mockito.mock(OrderRepository.class);
    when(orderRepo.findById(any(String.class))).thenReturn(Mono.empty());
    messagingService = Mockito.mock(OrderMessagingService.class);
    emailOrderService = Mockito.mock(EmailOrderService.class);
    ingredientRepo = Mockito.mock(IngredientRepository.class);

    controller = new OrderApiController(orderRepo, messagingService, emailOrderService, ingredientRepo);
    testClient = WebTestClient.bindToController(controller).build();
  }

  // =========================================================================
  // TC-04 Tests: PATCH con lista blanca y corrección de ZIP mutante
  // =========================================================================

  @Test
  public void shouldPatchDeliveryZipWithoutMutatingState() {
    TacoOrder existingOrder = new TacoOrder();
    existingOrder.setId("order-123");
    existingOrder.setDeliveryCity("Dallas");
    existingOrder.setDeliveryState("TX");
    existingOrder.setDeliveryZip("75001");

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(existingOrder));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    OrderPatchRequest patch = new OrderPatchRequest();
    patch.setDeliveryZip("75201"); // Only update ZIP

    testClient.patch()
        .uri("/api/orders/order-123")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(patch)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryZip").isEqualTo("75201")
        .jsonPath("$.deliveryState").isEqualTo("TX") // State must remain unchanged
        .jsonPath("$.deliveryCity").isEqualTo("Dallas");

    verify(orderRepo).save(existingOrder);
    org.junit.jupiter.api.Assertions.assertEquals("75201", existingOrder.getDeliveryZip());
    org.junit.jupiter.api.Assertions.assertEquals("TX", existingOrder.getDeliveryState());
  }

  @Test
  public void shouldRejectForbiddenFieldsInPatchWith400() {
    String jsonWithForbiddenField = "{\"deliveryZip\":\"75201\",\"total\":99.99}";

    testClient.patch()
        .uri("/api/orders/order-123")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(jsonWithForbiddenField)
        .exchange()
        .expectStatus().isBadRequest();

    verify(orderRepo, never()).findById(any(String.class));
    verify(orderRepo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldReturn404WhenPatchingNonExistentOrder() {
    when(orderRepo.findById("missing-order")).thenReturn(Mono.empty());

    OrderPatchRequest patch = new OrderPatchRequest();
    patch.setDeliveryCity("Austin");

    testClient.patch()
        .uri("/api/orders/missing-order")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(patch)
        .exchange()
        .expectStatus().isNotFound();

    verify(orderRepo).findById("missing-order");
    verify(orderRepo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldReturn403WhenPatchingOrderOwnedByDifferentUser() {
    TacoOrder order = new TacoOrder();
    order.setId("order-123");
    User owner = new User("alice", "pass", "Alice Smith", "123 Main", "City", "ST", "12345", "555-1234", "alice@example.com");
    order.setUser(owner);

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(order));

    OrderPatchRequest patch = new OrderPatchRequest();
    patch.setDeliveryCity("New City");

    Principal bobPrincipal = () -> "bob";

    Mono<ResponseEntity<OrderResponse>> responseMono = controller.patchOrder("order-123", patch, bobPrincipal);

    StepVerifier.create(responseMono)
        .expectNextMatches(entity -> entity.getStatusCode().value() == 403)
        .verifyComplete();

    verify(orderRepo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldAllowAdminToPatchOrderOwnedByDifferentUser() {
    TacoOrder order = new TacoOrder();
    order.setId("order-123");
    User owner = new User("alice", "pass", "Alice Smith", "123 Main", "City", "ST", "12345", "555-1234", "alice@example.com");
    order.setUser(owner);

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(order));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    OrderPatchRequest patch = new OrderPatchRequest();
    patch.setDeliveryCity("Admin Updated City");

    Authentication adminAuth = new TestingAuthenticationToken("admin_user", "pass", "ROLE_ADMIN");

    Mono<ResponseEntity<OrderResponse>> responseMono = controller.patchOrder("order-123", patch, adminAuth);

    StepVerifier.create(responseMono)
        .expectNextMatches(entity -> entity.getStatusCode().is2xxSuccessful() &&
            entity.getBody().getDeliveryCity().equals("Admin Updated City"))
        .verifyComplete();

    verify(orderRepo).save(order);
  }

  // =========================================================================
  // TC-05 Tests: PUT y DELETE de órdenes con identidad consistente
  // =========================================================================

  @Test
  public void shouldReturn400WhenPutHasContradictoryId() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setId("different-id");
    request.setDeliveryName("John Doe");

    testClient.put()
        .uri("/api/orders/order-123")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isBadRequest();

    verify(orderRepo, never()).findById(any(String.class));
    verify(orderRepo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldUpdateExistingOrderWithPutAndPreserveMetadata() {
    TacoOrder existing = new TacoOrder();
    existing.setId("order-123");
    Date placedAt = new Date(1000000000L);
    existing.setPlacedAt(placedAt);
    User owner = new User("charlie", "pass", "Charlie", "Street", "City", "ST", "Zip", "Phone", "charlie@example.com");
    existing.setUser(owner);
    existing.setCcNumber("1234-5678-9012-3456");
    existing.setCcCVV("123");
    existing.setCcExpiration("12/25");

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(existing));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderCreateRequest request = new OrderCreateRequest();
    request.setId("order-123"); // Matching ID
    request.setDeliveryName("Updated Charlie");
    request.setDeliveryStreet("New Street");
    request.setDeliveryCity("New City");
    request.setDeliveryState("NS");
    request.setDeliveryZip("99999");
    request.setPaymentToken("ignored-token");

    Principal charliePrincipal = () -> "charlie";

    Mono<ResponseEntity<OrderResponse>> responseMono = controller.putOrder("order-123", request, charliePrincipal);

    StepVerifier.create(responseMono)
        .expectNextMatches(entity -> {
          OrderResponse body = entity.getBody();
          return entity.getStatusCode().is2xxSuccessful() &&
              body.getId().equals("order-123") &&
              body.getDeliveryName().equals("Updated Charlie") &&
              body.getPlacedAt().equals(placedAt);
        })
        .verifyComplete();

    verify(orderRepo).save(any(TacoOrder.class));
  }

  @Test
  public void shouldReturn404WhenPutNonExistentOrder() {
    when(orderRepo.findById("missing-123")).thenReturn(Mono.empty());

    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Someone");

    testClient.put()
        .uri("/api/orders/missing-123")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isNotFound();

    verify(orderRepo).findById("missing-123");
    verify(orderRepo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void shouldDeleteExistingOrder_Returns204NoContent() {
    TacoOrder existing = new TacoOrder();
    existing.setId("order-123");

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(existing));
    when(orderRepo.delete(existing)).thenReturn(Mono.empty());

    testClient.delete()
        .uri("/api/orders/order-123")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    verify(orderRepo).findById("order-123");
    verify(orderRepo).delete(existing);
  }

  @Test
  public void shouldReturn404WhenDeletingNonExistentOrder() {
    when(orderRepo.findById("missing-123")).thenReturn(Mono.empty());

    testClient.delete()
        .uri("/api/orders/missing-123")
        .exchange()
        .expectStatus().isNotFound();

    verify(orderRepo).findById("missing-123");
    verify(orderRepo, never()).delete(any(TacoOrder.class));
  }

  @Test
  public void shouldReturn403WhenDeletingOrderOwnedByAnotherUser() {
    TacoOrder existing = new TacoOrder();
    existing.setId("order-123");
    User owner = new User("alice", "pass", "Alice Smith", "123 Main", "City", "ST", "12345", "555-1234", "alice@example.com");
    existing.setUser(owner);

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(existing));

    Principal bobPrincipal = () -> "bob";

    Mono<ResponseEntity<Void>> responseMono = controller.deleteOrder("order-123", bobPrincipal);

    StepVerifier.create(responseMono)
        .expectNextMatches(entity -> entity.getStatusCode().value() == 403)
        .verifyComplete();

    verify(orderRepo, never()).delete(any(TacoOrder.class));
  }

  @Test
  public void verifyDeleteComposesSearchAuthAndEffectWithoutPrematureDeletion() {
    TacoOrder existing = new TacoOrder();
    existing.setId("order-123");

    when(orderRepo.findById("order-123")).thenReturn(Mono.just(existing));
    when(orderRepo.delete(existing)).thenReturn(Mono.empty());

    Mono<ResponseEntity<Void>> responseMono = controller.deleteOrder("order-123", null);

    // No delete until subscription
    verify(orderRepo, never()).delete(any(TacoOrder.class));

    StepVerifier.create(responseMono)
        .expectNextMatches(entity -> entity.getStatusCode().is2xxSuccessful())
        .verifyComplete();

    verify(orderRepo).delete(existing);
  }

  // =========================================================================
  // TC-07 Tests: Guardar antes de enviar (save-then-send)
  // =========================================================================

  @Test
  public void shouldSaveBeforeSendingOrderWhenPostOrderFromEmail() {
    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("alice@example.com");

    TacoOrder unsavedOrder = new TacoOrder();
    unsavedOrder.setDeliveryName("Alice");

    TacoOrder savedOrder = new TacoOrder();
    savedOrder.setId("order-persisted-456");
    savedOrder.setDeliveryName("Alice");

    when(emailOrderService.convertEmailOrderToDomainOrder(any())).thenReturn(Mono.just(unsavedOrder));
    when(orderRepo.save(unsavedOrder)).thenReturn(Mono.just(savedOrder));

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(emailOrder)
        .exchange()
        .expectStatus().isCreated()
        .expectBody(OrderResponse.class)
        .value(res -> {
          org.junit.jupiter.api.Assertions.assertEquals("order-persisted-456", res.getId());
        });

    InOrder inOrder = inOrder(orderRepo, messagingService);
    inOrder.verify(orderRepo).save(unsavedOrder);
    inOrder.verify(messagingService).sendOrder(savedOrder);
  }

  @Test
  public void shouldNotSendMessageWhenSaveFailsInPostOrderFromEmail() {
    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("alice@example.com");

    TacoOrder unsavedOrder = new TacoOrder();
    unsavedOrder.setDeliveryName("Alice");

    when(emailOrderService.convertEmailOrderToDomainOrder(any())).thenReturn(Mono.just(unsavedOrder));
    when(orderRepo.save(unsavedOrder)).thenReturn(Mono.error(new RuntimeException("MongoDB connection timeout")));

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(emailOrder)
        .exchange()
        .expectStatus().is5xxServerError();

    verify(orderRepo).save(unsavedOrder);
    verify(messagingService, never()).sendOrder(any());
  }

  @Test
  public void shouldNotSaveNorSendWhenConversionFailsInPostOrderFromEmail() {
    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("invalid@example.com");

    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.error(new UserNotFoundException("invalid@example.com")));

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(emailOrder)
        .exchange()
        .expectStatus().is5xxServerError();

    verify(orderRepo, never()).save(any());
    verify(messagingService, never()).sendOrder(any());
  }
}
