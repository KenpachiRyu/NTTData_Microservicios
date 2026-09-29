package tacos.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;

public class UserOrdersHistoryTest {

  private OrderRepository orderRepo;
  private UserRepository userRepo;
  private ReactiveMongoTemplate mongoTemplate;
  private OrderMapper orderMapper;
  private UserOrdersController controller;
  private WebTestClient client;

  @BeforeEach
  public void setUp() {
    orderRepo = mock(OrderRepository.class);
    userRepo = mock(UserRepository.class);
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    orderMapper = new OrderMapper();
    controller = new UserOrdersController(orderRepo, userRepo, mongoTemplate, orderMapper);
    client = WebTestClient.bindToController(controller).build();
  }

  @Test
  public void testMyOrdersPaginationAndSanitization() {
    Principal principal = () -> "alice";

    User user = new User("alice", "pass", "Alice", "St", "City", "ST", "76000", "555", "alice@example.com");
    TacoOrder order = new TacoOrder();
    order.setId("ord-1");
    order.setUser(user);
    order.setPlacedAt(new Date());
    order.setCcNumber("tok_secret_payment_token_1234");

    when(mongoTemplate.count(any(Query.class), eq(TacoOrder.class))).thenReturn(Mono.just(1L));
    when(mongoTemplate.find(any(Query.class), eq(TacoOrder.class))).thenReturn(Flux.just(order));

    client.mutateWith((builder, httpHandlerBuilder, connector) -> {})
        .get().uri("/api/users/me/orders?page=0&size=10")
        .attribute("org.springframework.security.core.Authentication", principal)
        // or test direct controller invocation to verify principal handling
        .exchange();

    Mono<org.springframework.http.ResponseEntity<PageResponse<OrderResponse>>> responseMono =
        controller.getMyOrders(0, 10, principal);

    org.springframework.http.ResponseEntity<PageResponse<OrderResponse>> response = responseMono.block();
    assertNotNull(response);
    assertEquals(200, response.getStatusCodeValue());
    PageResponse<OrderResponse> page = response.getBody();
    assertNotNull(page);
    assertEquals(1, page.getTotalElements());

    OrderResponse resOrder = page.getContent().get(0);
    assertEquals("ord-1", resOrder.getId());
    // Verify token is masked (does not leak full token)
    assertTrue(resOrder.getPaymentToken().startsWith("tok_***"));
    assertFalse(resOrder.getPaymentToken().contains("secret_payment"));
  }

  @Test
  public void testGetOrderByIdOtherUserReturnsNotFound() {
    Principal principalAlice = () -> "alice";

    User bob = new User("bob", "pass", "Bob", "St", "City", "ST", "76000", "555", "bob@example.com");
    TacoOrder bobOrder = new TacoOrder();
    bobOrder.setId("ord-bob");
    bobOrder.setUser(bob);

    when(orderRepo.findById("ord-bob")).thenReturn(Mono.just(bobOrder));

    // When Alice requests Bob's order, she must get 404 Not Found to prevent data/existence leaking
    org.springframework.http.ResponseEntity<OrderResponse> res =
        controller.getMyOrderById("ord-bob", principalAlice).block();

    assertNotNull(res);
    assertEquals(404, res.getStatusCodeValue());
  }

  @Test
  public void testGetOrderByIdOwnOrderReturnsOk() {
    Principal principalAlice = () -> "alice";

    User alice = new User("alice", "pass", "Alice", "St", "City", "ST", "76000", "555", "alice@example.com");
    TacoOrder aliceOrder = new TacoOrder();
    aliceOrder.setId("ord-alice");
    aliceOrder.setUser(alice);
    aliceOrder.setCcNumber("tok_12345678");

    when(orderRepo.findById("ord-alice")).thenReturn(Mono.just(aliceOrder));

    org.springframework.http.ResponseEntity<OrderResponse> res =
        controller.getMyOrderById("ord-alice", principalAlice).block();

    assertNotNull(res);
    assertEquals(200, res.getStatusCodeValue());
    assertEquals("ord-alice", res.getBody().getId());
  }
}
