package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderStatus;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.OrderRepository;

@ExtendWith(MockitoExtension.class)
public class KitchenQueueTest {

  @Mock
  private ReactiveMongoTemplate mongoTemplate;

  @Mock
  private OrderRepository orderRepo;

  private OrderStateService stateService;
  private KitchenOrderService kitchenService;

  private Authentication kitchenAuth;

  @BeforeEach
  void setUp() {
    stateService = new OrderStateService(orderRepo);
    kitchenService = new KitchenOrderService(mongoTemplate, stateService);

    kitchenAuth = new UsernamePasswordAuthenticationToken("chef_luigi", "pwd",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));
  }

  @Test
  @DisplayName("Cálculo determinista de ETA según cantidad de tacos y posición en cola")
  void testEtaCalculation() {
    TacoOrder order = new TacoOrder();
    Taco taco1 = new Taco();
    taco1.setName("Carnitas");
    taco1.setIngredients(Arrays.asList(
        new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP),
        new Ingredient("CARN", "Carnitas", Ingredient.Type.PROTEIN)
    ));

    Taco taco2 = new Taco();
    taco2.setName("Super Complex");
    taco2.setIngredients(Arrays.asList(
        new Ingredient("COTO", "Corn Tortilla", Ingredient.Type.WRAP),
        new Ingredient("GRBF", "Ground Beef", Ingredient.Type.PROTEIN),
        new Ingredient("CHED", "Cheddar", Ingredient.Type.CHEESE),
        new Ingredient("JACK", "Monterrey Jack", Ingredient.Type.CHEESE),
        new Ingredient("SLSA", "Salsa", Ingredient.Type.SAUCE) // > 4 ingredientes -> +1 complejidad
    ));

    order.setTacos(Arrays.asList(taco1, taco2));

    // Base = 5, 2 tacos * 2 min = 4 min, 1 complejo = 1 min -> 10 min en pos 0
    int etaPos0 = kitchenService.calculateEta(order, 0);
    assertThat(etaPos0).isEqualTo(10);

    // En pos 2: 10 + 2 * 3 = 16 min
    int etaPos2 = kitchenService.calculateEta(order, 2);
    assertThat(etaPos2).isEqualTo(16);
  }

  @Test
  @DisplayName("Claim atómico con findAndModify asigna stationId y cookId")
  void testClaimNextAtomic() {
    TacoOrder order = new TacoOrder();
    order.setId("order-claim-1");
    order.setStatus(OrderStatus.ACCEPTED);
    order.setStationId("STATION_A");
    order.setCookId("chef_luigi");
    order.setDeliveryCity("Mexico City");

    // Estación no ocupada
    when(mongoTemplate.exists(any(Query.class), eq(TacoOrder.class))).thenReturn(Mono.just(false));

    // findAndModify retorna orden reclamada
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TacoOrder.class)))
        .thenReturn(Mono.just(order));

    KitchenOrderDto claimed = kitchenService.claimNext("STATION_A", "chef_luigi", kitchenAuth).block();

    assertThat(claimed).isNotNull();
    assertThat(claimed.getId()).isEqualTo("order-claim-1");
    assertThat(claimed.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    assertThat(claimed.getStationId()).isEqualTo("STATION_A");
    assertThat(claimed.getCookId()).isEqualTo("chef_luigi");
    // No expone dirección detallada ni pago
    assertThat(claimed.getDeliveryCity()).isEqualTo("Mexico City");
  }

  @Test
  @DisplayName("Impedir que una estación reclame dos veces si ya está ocupada")
  void testStationAlreadyBusyFails() {
    // Estación ya tiene una orden activa
    when(mongoTemplate.exists(any(Query.class), eq(TacoOrder.class))).thenReturn(Mono.just(true));

    assertThatThrownBy(() -> kitchenService.claimNext("STATION_BUSY", "chef_luigi", kitchenAuth).block())
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("ya tiene una orden activa");
  }
}
