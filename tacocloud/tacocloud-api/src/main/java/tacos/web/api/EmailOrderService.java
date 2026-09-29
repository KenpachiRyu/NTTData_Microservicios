package tacos.web.api;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;

@Service
public class EmailOrderService {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;

  public EmailOrderService(UserRepository userRepo, IngredientRepository ingredientRepo,
      PaymentMethodRepository paymentMethodRepo) {
    this.userRepo = userRepo;
    this.ingredientRepo = ingredientRepo;
    this.paymentMethodRepo = paymentMethodRepo;
  }

  public Mono<TacoOrder> convertEmailOrderToDomainOrder(Mono<EmailOrder> emailOrder) {
    return emailOrder.flatMap(eOrder -> {
      Mono<User> userMono = userRepo.findByEmail(eOrder.getEmail())
          .switchIfEmpty(Mono.error(new UserNotFoundException(eOrder.getEmail())));

      Mono<PaymentMethod> paymentMono = userMono.flatMap(user ->
          paymentMethodRepo.findByUserId(user.getId())
              .switchIfEmpty(Mono.error(new PaymentMethodNotFoundException(user.getId())))
      );

      Mono<List<Taco>> tacosMono = Flux.fromIterable(eOrder.getTacos() != null ? eOrder.getTacos() : Collections.emptyList())
          .concatMap(emailTaco -> {
            List<String> ingredientIds = emailTaco.getIngredients() != null ? emailTaco.getIngredients() : Collections.emptyList();
            return Flux.fromIterable(ingredientIds)
                .concatMap(ingId -> ingredientRepo.findById(ingId)
                    .switchIfEmpty(Mono.error(new IngredientNotFoundException(ingId)))
                )
                .collectList()
                .map(ingredients -> {
                  Taco taco = new Taco();
                  taco.setName(emailTaco.getName());
                  taco.setIngredients(ingredients);
                  return taco;
                });
          })
          .collectList();

      return Mono.zip(userMono, paymentMono, tacosMono)
          .map(tuple -> {
            User user = tuple.getT1();
            PaymentMethod paymentMethod = tuple.getT2();
            List<Taco> tacos = tuple.getT3();

            TacoOrder order = new TacoOrder();
            order.setUser(user);
            order.setCcNumber(paymentMethod.getCcNumber());
            order.setCcCVV(paymentMethod.getCcCVV());
            order.setCcExpiration(paymentMethod.getCcExpiration());
            order.setDeliveryName(user.getFullname());
            order.setDeliveryStreet(user.getStreet());
            order.setDeliveryCity(user.getCity());
            order.setDeliveryState(user.getState());
            order.setDeliveryZip(user.getZip());
            order.setPlacedAt(new Date());
            order.setTacos(tacos);

            return order;
          });
    });
  }

}
