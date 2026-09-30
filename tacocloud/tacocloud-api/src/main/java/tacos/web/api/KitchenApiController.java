package tacos.web.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path={"/api/v1/kitchen", "/api/kitchen"}, produces="application/json")
@CrossOrigin(origins="*")
@RequiredArgsConstructor
public class KitchenApiController {

  private final KitchenOrderService kitchenService;

  @GetMapping("/queue")
  public Flux<KitchenOrderDto> getQueue() {
    return kitchenService.getQueue();
  }

  @PostMapping("/orders/claim")
  public Mono<ResponseEntity<KitchenOrderDto>> claimNext(
      @RequestBody(required = false) KitchenClaimRequest request,
      Authentication auth) {
    String stationId = request != null ? request.getStationId() : null;
    String cookId = request != null ? request.getCookId() : null;

    return kitchenService.claimNext(stationId, cookId, auth)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PostMapping("/orders/{id}/claim")
  public Mono<ResponseEntity<KitchenOrderDto>> claimById(
      @PathVariable("id") String id,
      @RequestBody(required = false) KitchenClaimRequest request,
      Authentication auth) {
    String stationId = request != null ? request.getStationId() : null;
    String cookId = request != null ? request.getCookId() : null;

    return kitchenService.claimById(id, stationId, cookId, auth)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PatchMapping("/orders/{id}/status")
  public Mono<ResponseEntity<KitchenOrderDto>> updateStatus(
      @PathVariable("id") String id,
      @RequestBody OrderStatusUpdateRequest request,
      Authentication auth) {
    return kitchenService.updateStatus(id, request.getStatus(), request.getReason(), auth)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
}
