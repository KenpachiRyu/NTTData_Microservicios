package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.web.api.EmailOrder.EmailTaco;

public class EmailOrderServiceTest {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private EmailOrderService emailOrderService;

  private User testUser;
  private PaymentMethod testPaymentMethod;

  @BeforeEach
  public void setup() {
    userRepo = Mockito.mock(UserRepository.class);
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    paymentMethodRepo = Mockito.mock(PaymentMethodRepository.class);

    emailOrderService = new EmailOrderService(userRepo, ingredientRepo, paymentMethodRepo);

    testUser = new User("alice", "pass", "Alice Smith", "123 Main St", "Dallas", "TX", "75001", "555-1234", "alice@example.com");
    testUser.setId("user-1");

    testPaymentMethod = new PaymentMethod(testUser, "4111111111111111", "123", "12/28");
  }

  @Test
  public void shouldConvertEmailOrderToDomainOrderSuccessfully_HappyPath() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);
    Ingredient ched = new Ingredient("CHED", "Cheddar", Type.CHEESE);

    when(userRepo.findByEmail("alice@example.com")).thenReturn(Mono.just(testUser));
    when(paymentMethodRepo.findByUserId("user-1")).thenReturn(Mono.just(testPaymentMethod));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flto));
    when(ingredientRepo.findById("GRBF")).thenReturn(Mono.just(grbf));
    when(ingredientRepo.findById("CHED")).thenReturn(Mono.just(ched));

    EmailTaco taco1 = new EmailTaco();
    taco1.setName("Beef Wrap");
    taco1.setIngredients(Arrays.asList("FLTO", "GRBF"));

    EmailTaco taco2 = new EmailTaco();
    taco2.setName("Cheesy Wrap");
    taco2.setIngredients(Arrays.asList("FLTO", "CHED"));

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("alice@example.com");
    emailOrder.setTacos(Arrays.asList(taco1, taco2));

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .assertNext(order -> {
          assertNotNull(order);
          assertEquals(testUser, order.getUser());
          assertEquals("Alice Smith", order.getDeliveryName());
          assertEquals("123 Main St", order.getDeliveryStreet());
          assertEquals("Dallas", order.getDeliveryCity());
          assertEquals("TX", order.getDeliveryState());
          assertEquals("75001", order.getDeliveryZip());
          assertEquals(testPaymentMethod.getPaymentToken(), order.getCcNumber());
          assertNull(order.getCcCVV());
          assertEquals("12/28", order.getCcExpiration());
          assertNotNull(order.getPlacedAt());

          List<Taco> tacos = order.getTacos();
          assertEquals(2, tacos.size());

          Taco firstTaco = tacos.get(0);
          assertEquals("Beef Wrap", firstTaco.getName());
          assertEquals(2, firstTaco.getIngredients().size());
          assertEquals(flto, firstTaco.getIngredients().get(0));
          assertEquals(grbf, firstTaco.getIngredients().get(1));

          Taco secondTaco = tacos.get(1);
          assertEquals("Cheesy Wrap", secondTaco.getName());
          assertEquals(2, secondTaco.getIngredients().size());
          assertEquals(flto, secondTaco.getIngredients().get(0));
          assertEquals(ched, secondTaco.getIngredients().get(1));
        })
        .verifyComplete();
  }

  @Test
  public void shouldFailWithUserNotFoundExceptionWhenEmailDoesNotExist() {
    when(userRepo.findByEmail("unknown@example.com")).thenReturn(Mono.empty());

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("unknown@example.com");
    emailOrder.setTacos(Collections.emptyList());

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .expectErrorMatches(throwable ->
            throwable instanceof UserNotFoundException &&
            throwable.getMessage().contains("unknown@example.com")
        )
        .verify();
  }

  @Test
  public void shouldFailWithPaymentMethodNotFoundExceptionWhenUserHasNoPayment() {
    when(userRepo.findByEmail("alice@example.com")).thenReturn(Mono.just(testUser));
    when(paymentMethodRepo.findByUserId("user-1")).thenReturn(Mono.empty());

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("alice@example.com");
    emailOrder.setTacos(Collections.emptyList());

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .expectErrorMatches(throwable ->
            throwable instanceof PaymentMethodNotFoundException &&
            throwable.getMessage().contains("user-1")
        )
        .verify();
  }

  @Test
  public void shouldFailWithIngredientNotFoundExceptionWhenIngredientDoesNotExist() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(userRepo.findByEmail("alice@example.com")).thenReturn(Mono.just(testUser));
    when(paymentMethodRepo.findByUserId("user-1")).thenReturn(Mono.just(testPaymentMethod));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flto));
    when(ingredientRepo.findById("UNKNOWN_ING")).thenReturn(Mono.empty());

    EmailTaco taco = new EmailTaco();
    taco.setName("Broken Taco");
    taco.setIngredients(Arrays.asList("FLTO", "UNKNOWN_ING"));

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("alice@example.com");
    emailOrder.setTacos(Collections.singletonList(taco));

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .expectErrorMatches(throwable ->
            throwable instanceof IngredientNotFoundException &&
            throwable.getMessage().contains("UNKNOWN_ING")
        )
        .verify();
  }

  @Test
  public void shouldPreserveTacoOrderDeterministically() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    when(userRepo.findByEmail("alice@example.com")).thenReturn(Mono.just(testUser));
    when(paymentMethodRepo.findByUserId("user-1")).thenReturn(Mono.just(testPaymentMethod));
    when(ingredientRepo.findById(any(String.class))).thenReturn(Mono.just(flto));

    EmailTaco taco1 = new EmailTaco();
    taco1.setName("First Taco");
    taco1.setIngredients(Collections.singletonList("FLTO"));

    EmailTaco taco2 = new EmailTaco();
    taco2.setName("Second Taco");
    taco2.setIngredients(Collections.singletonList("FLTO"));

    EmailTaco taco3 = new EmailTaco();
    taco3.setName("Third Taco");
    taco3.setIngredients(Collections.singletonList("FLTO"));

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("alice@example.com");
    emailOrder.setTacos(Arrays.asList(taco1, taco2, taco3));

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .assertNext(order -> {
          List<Taco> tacos = order.getTacos();
          assertEquals(3, tacos.size());
          assertEquals("First Taco", tacos.get(0).getName());
          assertEquals("Second Taco", tacos.get(1).getName());
          assertEquals("Third Taco", tacos.get(2).getName());
        })
        .verifyComplete();
  }
}
