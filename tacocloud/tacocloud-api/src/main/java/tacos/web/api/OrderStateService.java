package tacos.web.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.data.OrderRepository;

@Service
@RequiredArgsConstructor
public class OrderStateService {

  private final OrderRepository orderRepo;

  // Matriz de transiciones permitidas: from -> Set<to>
  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = new HashMap<>();

  static {
    ALLOWED_TRANSITIONS.put(OrderStatus.CREATED, Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        OrderStatus.ACCEPTED, OrderStatus.CANCELLED
    ))));
    ALLOWED_TRANSITIONS.put(OrderStatus.ACCEPTED, Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        OrderStatus.PREPARING, OrderStatus.CANCELLED
    ))));
    ALLOWED_TRANSITIONS.put(OrderStatus.PREPARING, Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        OrderStatus.READY, OrderStatus.CANCELLED
    ))));
    ALLOWED_TRANSITIONS.put(OrderStatus.READY, Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED
    ))));
    ALLOWED_TRANSITIONS.put(OrderStatus.OUT_FOR_DELIVERY, Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        OrderStatus.DELIVERED
    ))));
    ALLOWED_TRANSITIONS.put(OrderStatus.DELIVERED, Collections.emptySet());
    ALLOWED_TRANSITIONS.put(OrderStatus.CANCELLED, Collections.emptySet());
  }

  public boolean isTransitionAllowed(OrderStatus from, OrderStatus to) {
    if (from == null || to == null) {
      return false;
    }
    if (from == to) {
      return true; // Idempotente
    }
    Set<OrderStatus> allowed = ALLOWED_TRANSITIONS.get(from);
    return allowed != null && allowed.contains(to);
  }

  public Mono<TacoOrder> transition(String orderId, OrderStatus targetStatus, String reason, String source, Authentication auth) {
    return orderRepo.findById(orderId)
        .switchIfEmpty(Mono.error(new BusinessRuleException("RESOURCE_NOT_FOUND", "Orden no encontrada: " + orderId)))
        .flatMap(order -> transition(order, targetStatus, reason, source, auth));
  }

  public Mono<TacoOrder> transition(TacoOrder order, OrderStatus targetStatus, String reason, String source, Authentication auth) {
    OrderStatus current = order.getStatus() != null ? order.getStatus() : OrderStatus.CREATED;

    // Idempotencia: si ya está en el estado solicitado, retornar orden sin error
    if (current == targetStatus) {
      return Mono.just(order);
    }

    // Validación de transición de la máquina de estados
    if (!isTransitionAllowed(current, targetStatus)) {
      return Mono.error(new BusinessRuleException("INVALID_ORDER_STATE_TRANSITION",
          "Transición no permitida de " + current + " a " + targetStatus));
    }

    // Validación de roles y permisos
    String username = auth != null ? auth.getName() : "SYSTEM";
    boolean isAdmin = hasRole(auth, "ROLE_ADMIN");
    boolean isKitchen = hasRole(auth, "ROLE_KITCHEN");
    boolean isDelivery = hasRole(auth, "ROLE_DELIVERY");
    boolean isOwner = order.getUser() != null && order.getUser().getUsername() != null &&
        order.getUser().getUsername().equals(username);

    if (targetStatus == OrderStatus.CANCELLED) {
      // Regla de cancelación: El usuario solo puede cancelar antes de PREPARING (en CREATED o ACCEPTED)
      if (!isAdmin && !isKitchen) {
        if (!isOwner) {
          return Mono.error(new AccessDeniedException("Solo el dueño o personal autorizado puede cancelar la orden"));
        }
        if (current != OrderStatus.CREATED && current != OrderStatus.ACCEPTED) {
          return Mono.error(new BusinessRuleException("INVALID_ORDER_STATE_TRANSITION",
              "La orden ya está en preparación y no puede ser cancelada por el cliente"));
        }
      }
    } else if (targetStatus == OrderStatus.ACCEPTED || targetStatus == OrderStatus.PREPARING || targetStatus == OrderStatus.READY) {
      // Cocina o Admin
      if (!isKitchen && !isAdmin) {
        return Mono.error(new AccessDeniedException("Se requiere rol KITCHEN o ADMIN para avanzar a " + targetStatus));
      }
    } else if (targetStatus == OrderStatus.OUT_FOR_DELIVERY) {
      // Delivery, Cocina o Admin
      if (!isDelivery && !isKitchen && !isAdmin) {
        return Mono.error(new AccessDeniedException("Se requiere rol DELIVERY, KITCHEN o ADMIN para despachar la orden"));
      }
    } else if (targetStatus == OrderStatus.DELIVERED) {
      // Delivery o Admin (el cliente NO puede marcar DELIVERED)
      if (!isDelivery && !isAdmin) {
        return Mono.error(new AccessDeniedException("Se requiere rol DELIVERY o ADMIN para marcar la orden como DELIVERED"));
      }
    }

    // Registrar cambio en historial
    order.recordStatusChange(current, targetStatus, username, source != null ? source : "API", reason);

    return orderRepo.save(order);
  }

  public Mono<TacoOrder> cancelOrder(String orderId, String reason, Authentication auth) {
    return transition(orderId, OrderStatus.CANCELLED, reason, "USER_CANCELLATION", auth);
  }

  private boolean hasRole(Authentication auth, String role) {
    if (auth == null || auth.getAuthorities() == null) {
      return false;
    }
    for (GrantedAuthority ga : auth.getAuthorities()) {
      if (role.equals(ga.getAuthority())) {
        return true;
      }
    }
    return false;
  }
}
