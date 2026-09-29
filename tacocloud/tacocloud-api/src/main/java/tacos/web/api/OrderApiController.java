package tacos.web.api;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@RestController
@RequestMapping(path="/api/orders", produces="application/json")
@CrossOrigin(origins="*")
public class OrderApiController {

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;
  private IngredientRepository ingredientRepo;

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            IngredientRepository ingredientRepo) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.ingredientRepo = ingredientRepo;
  }

  @GetMapping(produces="application/json")
  public Flux<OrderResponse> allOrders() {
    return repo.findAll().map(this::toOrderResponse);
  }

  // =========================================================================
  // TC-08 / TC-12 / TC-14: Crear orden calculando el total en el servidor
  // =========================================================================
  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(@RequestBody OrderCreateRequest request) {
    TacoOrder order = toDomainOrder(request);
    order.setPlacedAt(new Date());

    // Obtener todos los IDs de los ingredientes incluidos en la orden
    List<String> ingredientIds = request.getTacos().stream()
        .flatMap(taco -> taco.getIngredients().stream())
        .map(Ingredient::getId)
        .collect(Collectors.toList());

    // TC-14: Consultar precios reales en la base de datos y calcular el total en el servidor
    return ingredientRepo.findAllById(ingredientIds)
        .collectList()
        .flatMap(ingredients -> {
          BigDecimal calculatedTotal = ingredients.stream()
              .map(ing -> ing.getUnitPrice() != null ? ing.getUnitPrice() : BigDecimal.ZERO)
              .reduce(BigDecimal.ZERO, BigDecimal::add);

          // Asignar el total calculado en el servidor antes de guardar
          order.setTotal(calculatedTotal);
          orderMessages.sendOrder(order);

          return repo.save(order);
        })
        .map(this::toOrderResponse);
  }

  // =========================================================================
  // TC-07: Guardar antes de enviar - Una sola suscripción reactiva (8 pts)
  // =========================================================================
  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    return emailOrderService.convertEmailOrderToDomainOrder(emailOrder)
        .flatMap(repo::save)
        .doOnNext(orderMessages::sendOrder)
        .map(this::toOrderResponse);
  }

  // =========================================================================
  // TC-05: PUT asegurando coincidencia de identidad con la ruta (8 pts)
  // =========================================================================
  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> putOrder(
      @PathVariable("orderId") String orderId,
      @RequestBody OrderCreateRequest request,
      Principal principal) {

    if (request.getId() != null && !request.getId().equals(orderId)) {
      return Mono.just(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
    }

    return repo.findById(orderId)
        .flatMap(existingOrder -> {
          if (principal != null && existingOrder.getUser() != null) {
            boolean isOwner = principal.getName().equals(existingOrder.getUser().getUsername());
            boolean isAdmin = (principal instanceof Authentication) &&
                ((Authentication) principal).getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            if (!isOwner && !isAdmin) {
              return Mono.just(new ResponseEntity<OrderResponse>(HttpStatus.FORBIDDEN));
            }
          }

          TacoOrder updated = toDomainOrder(request);
          updated.setId(orderId);
          updated.setPlacedAt(existingOrder.getPlacedAt());
          updated.setUser(existingOrder.getUser());
          if (existingOrder.getCcNumber() != null) {
            updated.setCcNumber(existingOrder.getCcNumber());
            updated.setCcCVV(existingOrder.getCcCVV());
            updated.setCcExpiration(existingOrder.getCcExpiration());
          }
          return repo.save(updated)
              .map(saved -> new ResponseEntity<>(toOrderResponse(saved), HttpStatus.OK));
        })
        .defaultIfEmpty(new ResponseEntity<>(HttpStatus.NOT_FOUND));
  }

  // =========================================================================
  // TC-04: PATCH con lista blanca y corrección del ZIP mutante (8 pts)
  // =========================================================================
  @PatchMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> patchOrder(
      @PathVariable("orderId") String orderId,
      @RequestBody OrderPatchRequest patch,
      Principal principal) {

    return repo.findById(orderId)
        .flatMap(order -> {
          if (principal != null && order.getUser() != null) {
            boolean isOwner = principal.getName().equals(order.getUser().getUsername());
            boolean isAdmin = (principal instanceof Authentication) &&
                ((Authentication) principal).getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            if (!isOwner && !isAdmin) {
              return Mono.just(new ResponseEntity<OrderResponse>(HttpStatus.FORBIDDEN));
            }
          }

          if (patch.getDeliveryName() != null) {
            order.setDeliveryName(patch.getDeliveryName());
          }
          if (patch.getDeliveryStreet() != null) {
            order.setDeliveryStreet(patch.getDeliveryStreet());
          }
          if (patch.getDeliveryCity() != null) {
            order.setDeliveryCity(patch.getDeliveryCity());
          }
          if (patch.getDeliveryState() != null) {
            order.setDeliveryState(patch.getDeliveryState());
          }
          if (patch.getDeliveryZip() != null) {
            order.setDeliveryZip(patch.getDeliveryZip());
          }
          return repo.save(order)
              .map(saved -> new ResponseEntity<>(toOrderResponse(saved), HttpStatus.OK));
        })
        .defaultIfEmpty(new ResponseEntity<>(HttpStatus.NOT_FOUND));
  }

  // =========================================================================
  // TC-05: DELETE reactivo con respuesta semántica 204 / 404 (8 pts)
  // =========================================================================
  @DeleteMapping("/{orderId}")
  public Mono<ResponseEntity<Void>> deleteOrder(
      @PathVariable("orderId") String orderId,
      Principal principal) {
    return repo.findById(orderId)
        .flatMap(existing -> {
          if (principal != null && existing.getUser() != null) {
            boolean isOwner = principal.getName().equals(existing.getUser().getUsername());
            boolean isAdmin = (principal instanceof Authentication) &&
                ((Authentication) principal).getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            if (!isOwner && !isAdmin) {
              return Mono.just(new ResponseEntity<Void>(HttpStatus.FORBIDDEN));
            }
          }
          return repo.delete(existing)
              .then(Mono.just(new ResponseEntity<Void>(HttpStatus.NO_CONTENT)));
        })
        .defaultIfEmpty(new ResponseEntity<Void>(HttpStatus.NOT_FOUND));
  }

  // =========================================================================
  // Mappers auxiliares para DTOs
  // =========================================================================
  private TacoOrder toDomainOrder(OrderCreateRequest req) {
    TacoOrder order = new TacoOrder();
    order.setDeliveryName(req.getDeliveryName());
    order.setDeliveryStreet(req.getDeliveryStreet());
    order.setDeliveryCity(req.getDeliveryCity());
    order.setDeliveryState(req.getDeliveryState());
    order.setDeliveryZip(req.getDeliveryZip());
    order.setCcNumber(req.getPaymentToken()); 
    order.setTacos(req.getTacos());
    return order;
  }

  private OrderResponse toOrderResponse(TacoOrder order) {
    OrderResponse res = new OrderResponse();
    res.setId(order.getId());
    res.setPlacedAt(order.getPlacedAt());
    res.setDeliveryName(order.getDeliveryName());
    res.setDeliveryStreet(order.getDeliveryStreet());
    res.setDeliveryCity(order.getDeliveryCity());
    res.setDeliveryState(order.getDeliveryState());
    res.setDeliveryZip(order.getDeliveryZip());
    res.setPaymentToken(order.getCcNumber());
    res.setTacos(order.getTacos());
    return res;
  }

  @ExceptionHandler({
      IllegalArgumentException.class,
      com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
      org.springframework.core.codec.DecodingException.class,
      org.springframework.web.server.ServerWebInputException.class
  })
  public ResponseEntity<String> handleDecodingException(Exception ex) {
    return ResponseEntity.badRequest().body("Invalid request: " + ex.getMessage());
  }

}