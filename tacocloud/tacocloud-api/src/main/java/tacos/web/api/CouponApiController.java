package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;

@RestController
@CrossOrigin(origins = "*")
public class CouponApiController {

  private final CouponService couponService;
  private final PricingService pricingService;

  @Autowired
  public CouponApiController(CouponService couponService, PricingService pricingService) {
    this.couponService = couponService;
    this.pricingService = pricingService;
  }

  @PostMapping(path = "/api/coupons/validate", consumes = "application/json", produces = "application/json")
  public Mono<ResponseEntity<CouponValidateResponse>> validateCoupon(
      @Valid @RequestBody CouponValidateRequest request) {

    CouponService.DiscountResult result = couponService.applyCoupon(request.getCode(), request.getSubtotal());

    CouponValidateResponse response = new CouponValidateResponse(
        result.isValid(),
        result.getCode(),
        result.getDiscountAmount(),
        result.getFinalTotal(),
        result.getMessage()
    );

    return Mono.just(ResponseEntity.ok(response));
  }

  @PostMapping(path = "/api/orders/quote", consumes = "application/json", produces = "application/json")
  public Mono<ResponseEntity<QuoteResponse>> quoteOrder(@RequestBody QuoteRequest request) {
    TacoOrder draftOrder = new TacoOrder();

    if (request.getItems() != null && !request.getItems().isEmpty()) {
      List<OrderItem> items = new ArrayList<>();
      for (OrderItemRequest itemReq : request.getItems()) {
        items.add(new OrderItem(itemReq.getTaco(), itemReq.getQuantity()));
      }
      draftOrder.setItems(items);
    } else if (request.getTacos() != null && !request.getTacos().isEmpty()) {
      List<OrderItem> items = new ArrayList<>();
      for (Taco taco : request.getTacos()) {
        items.add(new OrderItem(taco, 1));
      }
      draftOrder.setItems(items);
    } else {
      return Mono.error(new BusinessRuleException("La cotización requiere al menos un taco o línea de pedido"));
    }

    return pricingService.priceOrder(draftOrder, request.getCouponCode())
        .map(priced -> {
          QuoteResponse response = new QuoteResponse(
              priced.getItems(),
              priced.getSubtotal(),
              priced.getDiscountAmount(),
              priced.getTotal(),
              priced.getCouponCode(),
              priced.getCurrency()
          );
          return ResponseEntity.ok(response);
        });
  }
}
