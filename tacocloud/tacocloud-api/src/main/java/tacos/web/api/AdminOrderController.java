package tacos.web.api;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;

@RestController
@RequestMapping(path = "/api/admin/orders", produces = "application/json")
@CrossOrigin(origins = "*")
public class AdminOrderController {

  private final ReactiveMongoTemplate mongoTemplate;
  private final OrderMapper orderMapper;

  @Autowired
  public AdminOrderController(ReactiveMongoTemplate mongoTemplate, OrderMapper orderMapper) {
    this.mongoTemplate = mongoTemplate;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
  }

  @GetMapping
  public Mono<ResponseEntity<PageResponse<OrderResponse>>> getAllOrders(
      @RequestParam(name = "user", required = false) String username,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {

    int safePage = Math.max(0, page);
    int safeSize = (size <= 0) ? 20 : Math.min(size, 50);

    Query query = new Query();
    if (username != null && !username.trim().isEmpty()) {
      query.addCriteria(Criteria.where("user.username").is(username.trim()));
    }

    query.with(Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id")));

    Mono<Long> totalElementsMono = mongoTemplate.count(query, TacoOrder.class);

    Mono<List<OrderResponse>> contentMono = mongoTemplate.find(
            query.with(PageRequest.of(safePage, safeSize)),
            TacoOrder.class)
        .map(orderMapper::toResponse)
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
}
