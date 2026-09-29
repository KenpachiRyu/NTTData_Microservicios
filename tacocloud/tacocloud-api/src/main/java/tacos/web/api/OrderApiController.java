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
import tacos.Taco;
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
  private PricingService pricingService;
  private InventoryService inventoryService;
  private tacos.physics.TacoPhysicsValidator physicsValidator;
  private OrderMapper orderMapper;

  @org.springframework.beans.factory.annotation.Autowired
  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            IngredientRepository ingredientRepo,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) PricingService pricingService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) InventoryService inventoryService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) tacos.physics.TacoPhysicsValidator physicsValidator,
                            @org.springframework.beans.factory.annotation.Autowired(required = false) OrderMapper orderMapper) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.ingredientRepo = ingredientRepo;
    this.pricingService = pricingService;
    this.inventoryService = inventoryService;
    this.physicsValidator = physicsValidator;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            IngredientRepository ingredientRepo) {
    this(repo, orderMessages, emailOrderService, ingredientRepo, null, null, null, new OrderMapper());
  }

  @GetMapping(produces="application/json")
  public Flux<OrderResponse> allOrders() {
    return repo.findAll().map(this::toOrderResponse);
  }

  // =========================================================================
  // TC-08 / TC-12 / TC-14 / TC-15 / TC-16 / TC-18: Crear orden completa
  // =========================================================================
  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(@RequestBody OrderCreateRequest request) {
    TacoOrder order = toDomainOrder(request);
    order.setPlacedAt(new Date());

    if (pricingService != null) {
      // TC-18: Validar física de tacos antes de procesar o reservar
      List<Taco> tacosToValidate = (order.getItems() != null && !order.getItems().isEmpty())
          ? order.getItems().stream().map(tacos.OrderItem::getTaco).collect(Collectors.toList())
          : order.getTacos();

      Mono<Void> validationMono = (physicsValidator != null && tacosToValidate != null && !tacosToValidate.isEmpty())
          ? Flux.fromIterable(tacosToValidate)
              .concatMap(taco -> physicsValidator.validateTaco(taco)
                  .flatMap(report -> {
                    if (!report.isValid()) {
                      String detail = report.getViolations().isEmpty() ? "Regla física violada" : report.getViolations().get(0).getMessage();
                      return Mono.error(new BusinessRuleException("TACO_PHYSICS_VIOLATION", "Diseño inválido para '" + taco.getName() + "': " + detail));
                    }
                    return Mono.empty();
                  })
              )
              .then()
          : Mono.empty();

      return validationMono
          // TC-14 / TC-15: Calcular precios de servidor y cupones
          .then(pricingService.priceOrder(order, request.getCouponCode()))
          .flatMap(pricedOrder -> {
            // TC-16: Calcular ingredientes y reservar inventario atómicamente
            java.util.Map<String, Integer> demand = new java.util.HashMap<>();
            if (pricedOrder.getItems() != null) {
              for (tacos.OrderItem item : pricedOrder.getItems()) {
                if (item.getTaco() != null && item.getTaco().getIngredients() != null) {
                  for (Ingredient ing : item.getTaco().getIngredients()) {
                    if (ing != null && ing.getId() != null) {
                      demand.merge(ing.getId(), item.getQuantity(), Integer::sum);
                    }
                  }
                }
              }
            }

            Mono<Void> reserveMono = (inventoryService != null && !demand.isEmpty())
                ? inventoryService.reserve(pricedOrder.getId(), null, demand).then()
                : Mono.empty();

            // TC-07: Guardar y luego publicar (Save-then-Send)
            return reserveMono.then(repo.save(pricedOrder));
          })
          .doOnNext(orderMessages::sendOrder)
          .map(this::toOrderResponse);
    }

    // Fallback reactivo si pricingService no está inyectado (por ejemplo en tests unitarios antiguos)
    List<String> ingredientIds = (request.getTacos() != null)
        ? request.getTacos().stream()
            .flatMap(taco -> taco.getIngredients() != null ? taco.getIngredients().stream() : java.util.stream.Stream.empty())
            .map(Ingredient::getId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList())
        : java.util.Collections.emptyList();

    return ingredientRepo.findAllById(ingredientIds)
        .collectList()
        .flatMap(ingredients -> {
          BigDecimal calculatedTotal = ingredients.stream()
              .map(ing -> ing.getUnitPrice() != null ? ing.getUnitPrice() : BigDecimal.ZERO)
              .reduce(BigDecimal.ZERO, BigDecimal::add);

          order.setTotal(calculatedTotal);
          return repo.save(order);
        })
        .doOnNext(orderMessages::sendOrder)
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
    TacoOrder order = orderMapper.toDomain(req);
    if (req.getItems() != null && !req.getItems().isEmpty()) {
      List<tacos.OrderItem> items = new java.util.ArrayList<>();
      for (OrderItemRequest itemReq : req.getItems()) {
        items.add(new tacos.OrderItem(itemReq.getTaco(), itemReq.getQuantity()));
      }
      order.setItems(items);
    }
    return order;
  }

  private OrderResponse toOrderResponse(TacoOrder order) {
    return orderMapper.toResponse(order);
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