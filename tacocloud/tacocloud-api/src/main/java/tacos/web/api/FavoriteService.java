package tacos.web.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoFavorite;
import tacos.User;
import tacos.data.TacoFavoriteRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Service
public class FavoriteService {

  private final TacoFavoriteRepository favoriteRepo;
  private final TacoRepository tacoRepo;
  private final UserRepository userRepo;

  @Autowired
  public FavoriteService(TacoFavoriteRepository favoriteRepo,
                         TacoRepository tacoRepo,
                         UserRepository userRepo) {
    this.favoriteRepo = favoriteRepo;
    this.tacoRepo = tacoRepo;
    this.userRepo = userRepo;
  }

  public Mono<TacoFavorite> addFavorite(String username, String tacoId) {
    if (username == null || tacoId == null) {
      return Mono.error(new BusinessRuleException("Identidad de usuario o taco no especificada", "BAD_REQUEST"));
    }

    return userRepo.findByUsername(username)
        .switchIfEmpty(Mono.error(new UserNotFoundException(username)))
        .flatMap(user -> tacoRepo.findById(tacoId)
            .switchIfEmpty(Mono.error(new BusinessRuleException("El taco especificado (" + tacoId + ") no existe", "TACO_NOT_FOUND")))
            .flatMap(taco -> favoriteRepo.findByUserIdAndTacoId(user.getId(), taco.getId())
                .switchIfEmpty(Mono.defer(() -> {
                  TacoFavorite newFav = new TacoFavorite(user.getId(), taco.getId());
                  return favoriteRepo.save(newFav)
                      .onErrorResume(DuplicateKeyException.class, e -> favoriteRepo.findByUserIdAndTacoId(user.getId(), taco.getId()));
                }))
            )
        );
  }

  public Mono<Void> removeFavorite(String username, String tacoId) {
    if (username == null || tacoId == null) {
      return Mono.empty();
    }

    return userRepo.findByUsername(username)
        .flatMap(user -> favoriteRepo.deleteByUserIdAndTacoId(user.getId(), tacoId));
  }

  public Mono<PageResponse<Taco>> getFavorites(String username, int page, int size) {
    if (page < 0) page = 0;
    if (size < 1 || size > 50) size = 20;

    int finalPage = page;
    int finalSize = size;
    Pageable pageable = PageRequest.of(page, size);

    return userRepo.findByUsername(username)
        .switchIfEmpty(Mono.error(new UserNotFoundException(username)))
        .flatMap(user -> {
          Mono<Long> countMono = favoriteRepo.countByUserId(user.getId());
          Mono<List<Taco>> tacosMono = favoriteRepo.findByUserId(user.getId(), pageable)
              .map(TacoFavorite::getTacoId)
              .collectList()
              .flatMap(tacoIds -> {
                if (tacoIds.isEmpty()) {
                  return Mono.just(java.util.Collections.<Taco>emptyList());
                }
                return tacoRepo.findAllById(tacoIds)
                    .collectMap(Taco::getId, java.util.function.Function.identity())
                    .map(map -> tacoIds.stream()
                        .map(map::get)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toList())
                    );
              });

          return Mono.zip(tacosMono, countMono)
              .map(tuple -> PageResponse.of(tuple.getT1(), finalPage, finalSize, tuple.getT2()));
        });
  }
}
