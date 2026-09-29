package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;

@ExtendWith(MockitoExtension.class)
public class OrderStateMachineTest {

  @Mock
  private OrderRepository orderRepo;

  private OrderStateService stateService;

  private Authentication kitchenAuth;
  private Authentication adminAuth;
  private Authentication userAuth;
  private Authentication otherUserAuth;

  @BeforeEach
  void setUp() {
    stateService = new OrderStateService(orderRepo);

    kitchenAuth = new UsernamePasswordAuthenticationToken("chef_mario", "pwd",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));

    adminAuth = new UsernamePasswordAuthenticationToken("admin_boss", "pwd",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    userAuth = new UsernamePasswordAuthenticationToken("juanito", "pwd",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    otherUserAuth = new UsernamePasswordAuthenticationToken("pedrito", "pwd",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }

  @Test
  @DisplayName("Flujo válido completo: CREATED -> ACCEPTED -> PREPARING -> READY")
  void validOrderLifecycle() {
    TacoOrder order = new TacoOrder();
    order.setId("order-101");
    order.setStatus(OrderStatus.CREATED);

    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // CREATED -> ACCEPTED (Kitchen)
    TacoOrder accepted = stateService.transition(order, OrderStatus.ACCEPTED, "Tomada por estación 1", "KITCHEN", kitchenAuth).block();
    assertThat(accepted.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    assertThat(accepted.getStatusHistory()).hasSize(1);
    assertThat(accepted.getStatusHistory().get(0).getFromStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(accepted.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.ACCEPTED);

    // ACCEPTED -> PREPARING (Kitchen)
    TacoOrder preparing = stateService.transition(accepted, OrderStatus.PREPARING, "Cocinando", "KITCHEN", kitchenAuth).block();
    assertThat(preparing.getStatus()).isEqualTo(OrderStatus.PREPARING);
    assertThat(preparing.getStatusHistory()).hasSize(2);

    // PREPARING -> READY (Kitchen)
    TacoOrder ready = stateService.transition(preparing, OrderStatus.READY, "Listo para despacho", "KITCHEN", kitchenAuth).block();
    assertThat(ready.getStatus()).isEqualTo(OrderStatus.READY);
    assertThat(ready.getStatusHistory()).hasSize(3);
  }

  @Test
  @DisplayName("Transición inválida: CREATED -> DELIVERED falla con BusinessRuleException")
  void invalidTransitionFails() {
    TacoOrder order = new TacoOrder();
    order.setId("order-102");
    order.setStatus(OrderStatus.CREATED);

    assertThatThrownBy(() -> stateService.transition(order, OrderStatus.DELIVERED, "Salto ilegal", "API", adminAuth).block())
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("Transición no permitida");
  }

  @Test
  @DisplayName("Transición idempotente: transicionar al mismo estado retorna orden sin error")
  void idempotentTransition() {
    TacoOrder order = new TacoOrder();
    order.setId("order-103");
    order.setStatus(OrderStatus.ACCEPTED);

    TacoOrder result = stateService.transition(order, OrderStatus.ACCEPTED, "Repetido", "API", kitchenAuth).block();
    assertThat(result.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    assertThat(result.getStatusHistory()).isEmpty(); // No duplica historial
  }

  @Test
  @DisplayName("Cliente puede cancelar su propia orden en CREATED")
  void ownerCanCancelInCreated() {
    TacoOrder order = new TacoOrder();
    order.setId("order-104");
    order.setStatus(OrderStatus.CREATED);
    User user = new User("juanito", "hash", "Juan", "Street", "City", "ST", "12345", "555", "juanito@tacocloud.com");
    order.setUser(user);

    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    TacoOrder cancelled = stateService.transition(order, OrderStatus.CANCELLED, "Ya no quiero tacos", "API", userAuth).block();
    assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(cancelled.getStatusHistory()).hasSize(1);
    assertThat(cancelled.getStatusHistory().get(0).getUpdatedBy()).isEqualTo("juanito");
  }

  @Test
  @DisplayName("Cliente ajeno no puede cancelar la orden de otro usuario")
  void otherUserCannotCancelOrder() {
    TacoOrder order = new TacoOrder();
    order.setId("order-105");
    order.setStatus(OrderStatus.CREATED);
    User user = new User("juanito", "hash", "Juan", "Street", "City", "ST", "12345", "555", "juanito@tacocloud.com");
    order.setUser(user);

    assertThatThrownBy(() -> stateService.transition(order, OrderStatus.CANCELLED, "Cancelar ajeno", "API", otherUserAuth).block())
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Cliente no puede cancelar una vez que la orden está en PREPARING")
  void clientCannotCancelWhenPreparing() {
    TacoOrder order = new TacoOrder();
    order.setId("order-106");
    order.setStatus(OrderStatus.PREPARING);
    User user = new User("juanito", "hash", "Juan", "Street", "City", "ST", "12345", "555", "juanito@tacocloud.com");
    order.setUser(user);

    assertThatThrownBy(() -> stateService.transition(order, OrderStatus.CANCELLED, "Arrepentido tarde", "API", userAuth).block())
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("ya está en preparación");
  }

  @Test
  @DisplayName("Cliente normal no puede marcar DELIVERED")
  void clientCannotMarkDelivered() {
    TacoOrder order = new TacoOrder();
    order.setId("order-107");
    order.setStatus(OrderStatus.READY);
    User user = new User("juanito", "hash", "Juan", "Street", "City", "ST", "12345", "555", "juanito@tacocloud.com");
    order.setUser(user);

    assertThatThrownBy(() -> stateService.transition(order, OrderStatus.DELIVERED, "Yo me lo entregué", "API", userAuth).block())
        .isInstanceOf(AccessDeniedException.class);
  }
}
