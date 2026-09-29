package tacos.web.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.InventoryReservation;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.physics.TacoPhysicsValidator;

@Service
public class ReorderService {

  private final OrderRepository orderRepo;
  private final IngredientRepository ingredientRepo;
  private final PricingService pricingService;
  private final InventoryService inventoryService;
  private final TacoPhysicsValidator physicsValidator;
  private final OrderMessagingService orderMessages;
  private final OrderMapper orderMapper;

  @Autowired
  public ReorderService(OrderRepository orderRepo,
                        IngredientRepository ingredientRepo,
                        @Autowired(required = false) PricingService pricingService,
                        @Autowired(required = false) InventoryService inventoryService,
                        @Autowired(required = false) TacoPhysicsValidator physicsValidator,
                        @Autowired(required = false) OrderMessagingService orderMessages,
                        @Autowired(required = false) OrderMapper orderMapper) {
    this.orderRepo = orderRepo;
    this.ingredientRepo = ingredientRepo;
    this.pricingService = pricingService;
    this.inventoryService = inventoryService;
    this.physicsValidator = physicsValidator;
    this.orderMessages = orderMessages;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
  }

  public Mono<ReorderResponse> reorder(String originalOrderId, String username, ReorderRequest request) {
    ReorderRequest req = request != null ? request : new ReorderRequest();

    return orderRepo.findById(originalOrderId)
        .flatMap(originalOrder -> {
          // 1. Validar propiedad estricta (ownership)
          if (originalOrder.getUser() != null && !username.equals(originalOrder.getUser().getUsername())) {
            return Mono.error(new BusinessRuleException("FORBIDDEN_REORDER", "No se puede reordenar un pedido ajeno"));
          }

          // 2. Crear una nueva orden limpia (sin clonar IDs de Mongo ni timestamps originales)
          TacoOrder newOrder = new TacoOrder();
          newOrder.setId(null);
          newOrder.setUser(originalOrder.getUser());
          newOrder.setDeliveryName(originalOrder.getDeliveryName());
          newOrder.setDeliveryStreet(originalOrder.getDeliveryStreet());
          newOrder.setDeliveryCity(originalOrder.getDeliveryCity());
          newOrder.setDeliveryState(originalOrder.getDeliveryState());
          newOrder.setDeliveryZip(originalOrder.getDeliveryZip());
          newOrder.setPlacedAt(new Date());

          // TC-12 / TC-24: Usar exclusivamente paymentToken seguro, NO setCcNumber ni PAN/CVV
          String newPaymentToken = (req.getPaymentToken() != null && !req.getPaymentToken().trim().isEmpty())
              ? req.getPaymentToken().trim()
              : originalOrder.getPaymentToken();
          newOrder.setPaymentToken(newPaymentToken);
          newOrder.setCardBrand(originalOrder.getCardBrand());
          newOrder.setCardLast4(originalOrder.getCardLast4());
          newOrder.setCardExpiration(originalOrder.getCardExpiration());

          // Copiar items/tacos
          if (originalOrder.getItems() != null && !originalOrder.getItems().isEmpty()) {
            List<OrderItem> newItems = new ArrayList<>();
            for (OrderItem oldItem : originalOrder.getItems()) {
              newItems.add(new OrderItem(oldItem.getTaco(), oldItem.getQuantity()));
            }
            newOrder.setItems(newItems);
          } else if (originalOrder.getTacos() != null) {
            newOrder.setTacos(new ArrayList<>(originalOrder.getTacos()));
          }

          // Extraer tacos a validar
          List<Taco> tacosToValidate = (newOrder.getItems() != null && !newOrder.getItems().isEmpty())
              ? newOrder.getItems().stream().map(OrderItem::getTaco).collect(Collectors.toList())
              : newOrder.getTacos();

          // 3. Validar reglas de diseño físico (TC-18)
          Mono<Void> validationMono = (physicsValidator != null && tacosToValidate != null && !tacosToValidate.isEmpty())
              ? Flux.fromIterable(tacosToValidate)
                  .concatMap(taco -> physicsValidator.validateTaco(taco)
                      .flatMap(report -> {
                        if (!report.isValid()) {
                          String detail = report.getViolations().isEmpty() ? "Regla física violada" : report.getViolations().get(0).getMessage();
                          return Mono.error(new BusinessRuleException("TACO_PHYSICS_VIOLATION", "Diseño inválido para '" + taco.getName() + "': " + detail));
                        }
                        return Mono.empty();
                      }))
                  .then()
              : Mono.empty();

          // 4. Calcular demanda de ingredientes para verificar existencia, disponibilidad y reserva
          Map<String, Integer> demand = new HashMap<>();
          if (newOrder.getItems() != null && !newOrder.getItems().isEmpty()) {
            for (OrderItem item : newOrder.getItems()) {
              if (item.getTaco() != null && item.getTaco().getIngredients() != null) {
                for (Ingredient ing : item.getTaco().getIngredients()) {
                  if (ing != null && ing.getId() != null) {
                    demand.merge(ing.getId(), item.getQuantity(), Integer::sum);
                  }
                }
              }
            }
          } else if (newOrder.getTacos() != null) {
            for (Taco taco : newOrder.getTacos()) {
              if (taco.getIngredients() != null) {
                for (Ingredient ing : taco.getIngredients()) {
                  if (ing != null && ing.getId() != null) {
                    demand.merge(ing.getId(), 1, Integer::sum);
                  }
                }
              }
            }
          }

          // 5. Verificar existencia y disponibilidad en catálogo (TC-13 / TC-24)
          Mono<Void> checkIngredientsMono = ingredientRepo.findAllById(demand.keySet())
              .collectList()
              .flatMap(foundIngredients -> {
                Set<String> foundIds = foundIngredients.stream()
                    .map(Ingredient::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

                // Detectar si algún ingrediente solicitado ya no existe en la base de datos
                for (String demandedId : demand.keySet()) {
                  if (!foundIds.contains(demandedId)) {
                    return Mono.error(new BusinessRuleException("INGREDIENT_NOT_FOUND", "Ingrediente no encontrado en el catálogo: " + demandedId));
                  }
                }

                // Detectar si algún ingrediente está agotado / no disponible para venta
                for (Ingredient ing : foundIngredients) {
                  if (Boolean.FALSE.equals(ing.getAvailable())) {
                    return Mono.error(new BusinessRuleException("INGREDIENT_UNAVAILABLE", "Ingrediente no disponible: " + ing.getName()));
                  }
                }

                return Mono.empty();
              });

          // Cupón a aplicar (el nuevo o el original)
          String coupon = req.getCouponCode() != null ? req.getCouponCode() : originalOrder.getCouponCode();

          // 6. Recotizar precio actual con PricingService
          return validationMono
              .then(checkIngredientsMono)
              .then(Mono.defer(() -> {
                if (pricingService != null) {
                  Mono<TacoOrder> p = pricingService.priceOrder(newOrder, coupon);
                  if (p != null) return p;
                }
                return Mono.just(newOrder);
              }))
              .flatMap(pricedOrder -> {
                BigDecimal originalTotal = originalOrder.getTotal() != null ? originalOrder.getTotal() : BigDecimal.ZERO;
                BigDecimal currentTotal = pricedOrder.getTotal() != null ? pricedOrder.getTotal() : BigDecimal.ZERO;
                BigDecimal difference = currentTotal.subtract(originalTotal);

                // 7. Si el precio cambió y no fue confirmado explícitamente, devolver cotización/conflicto
                if (!req.isConfirmPriceChange() && difference.compareTo(BigDecimal.ZERO) != 0) {
                  return Mono.just(new ReorderResponse(
                      false,
                      null,
                      originalTotal,
                      currentTotal,
                      difference,
                      "El precio ha cambiado de " + originalTotal + " a " + currentTotal + ". Confirme el cambio con confirmPriceChange=true"
                  ));
                }

                // 8. Clave de idempotencia para la reserva y persistencia
                String idempotencyKey = (req.getIdempotencyKey() != null && !req.getIdempotencyKey().trim().isEmpty())
                    ? req.getIdempotencyKey().trim()
                    : "reorder-" + originalOrderId + "-" + (newPaymentToken != null ? newPaymentToken.hashCode() : "default");

                // 9. Reservar inventario atómicamente con compensación/rollback ante fallo de persistencia
                Mono<TacoOrder> persistMono;
                if (inventoryService != null && !demand.isEmpty()) {
                  persistMono = inventoryService.reserve(null, idempotencyKey, demand)
                      .flatMap(reservation -> {
                        return orderRepo.save(pricedOrder)
                            .onErrorResume(saveErr -> {
                              // Compensación verificable mediante release de InventoryService
                              return inventoryService.release(reservation.getId())
                                  .then(Mono.error(saveErr));
                            });
                      });
                } else {
                  persistMono = orderRepo.save(pricedOrder);
                }

                // 10. Persistir y emitir evento (Save-then-Send)
                return persistMono
                    .doOnNext(saved -> {
                      if (orderMessages != null) {
                        orderMessages.sendOrder(saved);
                      }
                    })
                    .map(saved -> new ReorderResponse(
                        true,
                        orderMapper.toResponse(saved),
                        originalTotal,
                        currentTotal,
                        difference,
                        "Orden reordenada exitosamente con nuevo ID"
                    ));
              });
        });
  }
}
