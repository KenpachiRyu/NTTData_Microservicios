package tacos.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;

@Service
public class PricingService {

  public static final int MAX_QUANTITY_PER_LINE = 50;
  public static final String DEFAULT_CURRENCY = "USD";

  private final IngredientRepository ingredientRepo;
  private final CouponService couponService;

  @Autowired
  public PricingService(IngredientRepository ingredientRepo, CouponService couponService) {
    this.ingredientRepo = ingredientRepo;
    this.couponService = couponService;
  }

  public Mono<BigDecimal> calculateTacoPrice(Taco taco) {
    if (taco == null || taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return Mono.just(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    List<String> ingredientIds = taco.getIngredients().stream()
        .map(Ingredient::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    if (ingredientIds.isEmpty()) {
      return Mono.just(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    return ingredientRepo.findAllById(ingredientIds)
        .collectList()
        .map(ingredients -> {
          BigDecimal sum = ingredients.stream()
              .map(ing -> ing.getUnitPrice() != null ? ing.getUnitPrice() : BigDecimal.ZERO)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          return sum.setScale(2, RoundingMode.HALF_UP);
        });
  }

  public Mono<TacoOrder> priceOrder(TacoOrder order, String couponCode) {
    if (order == null) {
      return Mono.empty();
    }

    // Normalizar items si solo vinieron tacos en formato legado
    if ((order.getItems() == null || order.getItems().isEmpty()) && order.getTacos() != null) {
      List<OrderItem> items = new ArrayList<>();
      for (Taco taco : order.getTacos()) {
        items.add(new OrderItem(taco, 1));
      }
      order.setItems(items);
    }

    List<OrderItem> items = order.getItems() != null ? order.getItems() : new ArrayList<>();
    if (items.isEmpty()) {
      return Mono.error(new BusinessRuleException("La orden debe contener al menos un taco o línea de pedido"));
    }

    // Validar cantidades de cada línea
    for (OrderItem item : items) {
      if (item.getQuantity() <= 0) {
        return Mono.error(new BusinessRuleException("La cantidad debe ser mayor a cero (valor recibido: " + item.getQuantity() + ")"));
      }
      if (item.getQuantity() > MAX_QUANTITY_PER_LINE) {
        return Mono.error(new BusinessRuleException("La cantidad excede el límite máximo permitido de " + MAX_QUANTITY_PER_LINE + " unidades"));
      }
    }

    return Flux.fromIterable(items)
        .concatMap(item -> calculateTacoPrice(item.getTaco())
            .map(tacoPrice -> {
              item.setUnitPriceAtPurchase(tacoPrice);
              BigDecimal lineSubtotal = tacoPrice.multiply(BigDecimal.valueOf(item.getQuantity()))
                  .setScale(2, RoundingMode.HALF_UP);
              item.setSubtotal(lineSubtotal);
              return item;
            })
        )
        .collectList()
        .flatMap(pricedItems -> {
          order.setItems(pricedItems);

          // Subtotal acumulado de todas las líneas
          BigDecimal orderSubtotal = pricedItems.stream()
              .map(OrderItem::getSubtotal)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .setScale(2, RoundingMode.HALF_UP);

          order.setSubtotal(orderSubtotal);
          order.setCurrency(DEFAULT_CURRENCY);

          // Sincronizar tacos para compatibilidad con código existente
          List<Taco> syncedTacos = new ArrayList<>();
          for (OrderItem item : pricedItems) {
            for (int i = 0; i < item.getQuantity(); i++) {
              syncedTacos.add(item.getTaco());
            }
          }
          order.setTacos(syncedTacos);

          // Aplicar cupón si fue provisto
          String effectiveCoupon = couponCode != null ? couponCode : order.getCouponCode();
          if (effectiveCoupon != null && !effectiveCoupon.trim().isEmpty()) {
            CouponService.DiscountResult discountResult = couponService.applyCoupon(effectiveCoupon, orderSubtotal);
            if (!discountResult.isValid()) {
              return Mono.error(new BusinessRuleException("INVALID_COUPON", discountResult.getMessage()));
            }
            order.setDiscountAmount(discountResult.getDiscountAmount());
            order.setTotal(discountResult.getFinalTotal());
            order.setCouponCode(discountResult.getCode());
          } else {
            order.setDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            order.setTotal(orderSubtotal);
            order.setCouponCode(null);
          }

          return Mono.just(order);
        });
  }
}
