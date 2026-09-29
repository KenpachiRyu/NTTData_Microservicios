package tacos.web.api;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;

@RestController
@RequestMapping(path = "/api/users/me/orders", produces = "application/json")
@CrossOrigin(origins = "*")
public class UserOrdersController {

  private final OrderRepository orderRepo;
  private final UserRepository userRepo;
  private final ReactiveMongoTemplate mongoTemplate;
  private final OrderMapper orderMapper;

  @Autowired
  public UserOrdersController(OrderRepository orderRepo,
                              UserRepository userRepo,
                              ReactiveMongoTemplate mongoTemplate,
                              OrderMapper orderMapper) {
    this.orderRepo = orderRepo;
    this.userRepo = userRepo;
    this.mongoTemplate = mongoTemplate;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
  }

  // =========================================================================
  // TC-23: Historial paginado privado de órdenes para el usuario autenticado
  // =========================================================================
  @GetMapping
  public Mono<ResponseEntity<PageResponse<OrderResponse>>> getMyOrders(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "10") int size,
      Principal principal) {

    if (principal == null) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    int safePage = Math.max(0, page);
    int safeSize = (size <= 0) ? 10 : Math.min(size, 50);

    String username = principal.getName();

    Query query = Query.query(Criteria.where("user.username").is(username))
        .with(Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id")));

    Mono<Long> totalElementsMono = mongoTemplate.count(Query.query(Criteria.where("user.username").is(username)), TacoOrder.class);

    Mono<List<OrderResponse>> contentMono = mongoTemplate.find(
            query.with(PageRequest.of(safePage, safeSize)),
            TacoOrder.class)
        .map(this::toSanitizedResponse)
        .collectList();

    return Mono.zip(totalElementsMono, contentMono)
        .map(tuple -> {
          long totalElements = tuple.getT1();
          List<OrderResponse> content = tuple.getT2();
          int totalPages = safeSize > 0 ? (int) Math.ceil((double) totalElements / safeSize) : 0;
          boolean hasNext = (safePage + 1) < totalPages;

          PageResponse<OrderResponse> response = new PageResponse<>(
              content,
              safePage,
              safeSize,
              totalElements,
              totalPages,
              hasNext
          );
          return ResponseEntity.ok(response);
        });
  }

  // =========================================================================
  // TC-23: Detalle de orden con validación estricta de propiedad (ownership)
  // =========================================================================
  @GetMapping("/{id}")
  public Mono<ResponseEntity<OrderResponse>> getMyOrderById(
      @PathVariable("id") String id,
      Principal principal) {

    if (principal == null) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    return orderRepo.findById(id)
        .filter(order -> order.getUser() != null && principal.getName().equals(order.getUser().getUsername()))
        .map(order -> ResponseEntity.ok(toSanitizedResponse(order)))
        // Si no existe o pertenece a otro usuario, responde 404 para evitar revelar existencia
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  private OrderResponse toSanitizedResponse(TacoOrder order) {
    OrderResponse res = orderMapper.toResponse(order);
    if (res.getPaymentToken() != null && res.getPaymentToken().length() > 4) {
      // Ocultar token de pago para proteger datos sensibles
      res.setPaymentToken("tok_***" + res.getPaymentToken().substring(res.getPaymentToken().length() - 4));
    }
    return res;
  }
}
