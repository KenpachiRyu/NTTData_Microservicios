package tacos.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoFavorite;

public interface TacoFavoriteRepository extends ReactiveCrudRepository<TacoFavorite, String> {

  Mono<TacoFavorite> findByUserIdAndTacoId(String userId, String tacoId);

  Flux<TacoFavorite> findByUserId(String userId, Pageable pageable);

  Mono<Long> countByUserId(String userId);

  Mono<Void> deleteByUserIdAndTacoId(String userId, String tacoId);
}
