package tacos.data;

import java.util.Date;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OpsAnnouncement;

public interface OpsAnnouncementRepository extends ReactiveMongoRepository<OpsAnnouncement, String> {

  Flux<OpsAnnouncement> findByActiveTrue();

  Flux<OpsAnnouncement> findByActiveTrueAndExpiresAtAfter(Date now);

  Mono<Long> countByActiveTrue();
}
