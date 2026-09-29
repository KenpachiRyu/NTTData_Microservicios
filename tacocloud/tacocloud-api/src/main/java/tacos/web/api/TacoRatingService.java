package tacos.web.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoRating;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Service
public class TacoRatingService {

  private final TacoRatingRepository ratingRepo;
  private final TacoRepository tacoRepo;
  private final UserRepository userRepo;
  private final ReactiveMongoTemplate mongoTemplate;

  @Autowired
  public TacoRatingService(TacoRatingRepository ratingRepo,
                           TacoRepository tacoRepo,
                           UserRepository userRepo,
                           @Autowired(required = false) ReactiveMongoTemplate mongoTemplate) {
    this.ratingRepo = ratingRepo;
    this.tacoRepo = tacoRepo;
    this.userRepo = userRepo;
    this.mongoTemplate = mongoTemplate;
  }

  public TacoRatingService(TacoRatingRepository ratingRepo,
                           TacoRepository tacoRepo,
                           UserRepository userRepo) {
    this(ratingRepo, tacoRepo, userRepo, null);
  }

  public Mono<TacoRating> rateTaco(String username, String tacoId, int score) {
    if (score < 1 || score > 5) {
      return Mono.error(new BusinessRuleException("INVALID_SCORE", "La calificación debe estar entre 1 y 5 estrellas (recibido: " + score + ")"));
    }

    return userRepo.findByUsername(username)
        .switchIfEmpty(Mono.error(new UserNotFoundException(username)))
        .flatMap(user -> tacoRepo.findById(tacoId)
            .switchIfEmpty(Mono.error(new BusinessRuleException("TACO_NOT_FOUND", "El taco especificado (" + tacoId + ") no existe")))
            .flatMap(taco -> ratingRepo.findByUserIdAndTacoId(user.getId(), taco.getId())
                .flatMap(existing -> {
                  existing.setScore(score);
                  existing.setUpdatedAt(new Date());
                  return ratingRepo.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                  TacoRating newRating = new TacoRating(user.getId(), taco.getId(), score);
                  return ratingRepo.save(newRating);
                }))
            )
        );
  }

  public Mono<TacoRatingSummary> getRatingSummary(String tacoId, String optionalUsername) {
    return tacoRepo.findById(tacoId)
        .switchIfEmpty(Mono.error(new BusinessRuleException("TACO_NOT_FOUND", "El taco especificado (" + tacoId + ") no existe")))
        .flatMap(taco -> {
          Mono<RatingAggregateResult> aggMono;
          if (mongoTemplate != null) {
            Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("tacoId").is(tacoId)),
                Aggregation.group("tacoId")
                    .count().as("totalRatings")
                    .avg("score").as("averageScore")
            );
            aggMono = mongoTemplate.aggregate(agg, TacoRating.class, RatingAggregateResult.class)
                .next()
                .defaultIfEmpty(new RatingAggregateResult(tacoId, 0L, 0.0));
          } else {
            aggMono = ratingRepo.findByTacoId(tacoId)
                .collectList()
                .map(list -> {
                  long count = list.size();
                  double sum = list.stream().mapToInt(TacoRating::getScore).sum();
                  double avg = count > 0 ? sum / count : 0.0;
                  return new RatingAggregateResult(tacoId, count, avg);
                });
          }

          Mono<Integer> userRatingMono = (optionalUsername != null && !optionalUsername.trim().isEmpty())
              ? userRepo.findByUsername(optionalUsername)
                  .flatMap(u -> ratingRepo.findByUserIdAndTacoId(u.getId(), tacoId))
                  .map(TacoRating::getScore)
                  .defaultIfEmpty(0)
              : Mono.just(0);

          return Mono.zip(aggMono, userRatingMono)
              .map(tuple -> {
                RatingAggregateResult aggResult = tuple.getT1();
                Integer userScore = tuple.getT2();

                double roundedAvg = BigDecimal.valueOf(aggResult.getAverageScore())
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();

                return new TacoRatingSummary(
                    tacoId,
                    roundedAvg,
                    aggResult.getTotalRatings(),
                    userScore > 0 ? userScore : null
                );
              });
        });
  }

  public Flux<TacoTopRankItem> getTopTacos(int limit, int minVotes) {
    int finalLimit = limit > 0 && limit <= 50 ? limit : 10;
    int finalMinVotes = minVotes >= 0 ? minVotes : 1;

    Mono<List<RatingAggregateResult>> rankResultsMono;

    if (mongoTemplate != null) {
      Aggregation agg = Aggregation.newAggregation(
          Aggregation.group("tacoId")
              .count().as("totalRatings")
              .avg("score").as("averageScore"),
          Aggregation.match(Criteria.where("totalRatings").gte(finalMinVotes)),
          Aggregation.sort(Sort.by(
              Sort.Order.desc("averageScore"),
              Sort.Order.desc("totalRatings"),
              Sort.Order.asc("_id")
          )),
          Aggregation.limit(finalLimit)
      );
      rankResultsMono = mongoTemplate.aggregate(agg, TacoRating.class, RatingAggregateResult.class)
          .collectList();
    } else {
      rankResultsMono = ratingRepo.findAll()
          .collectList()
          .map(allRatings -> {
            Map<String, List<TacoRating>> grouped = allRatings.stream()
                .collect(Collectors.groupingBy(TacoRating::getTacoId));

            return grouped.entrySet().stream()
                .filter(e -> e.getValue().size() >= finalMinVotes)
                .map(e -> {
                  long count = e.getValue().size();
                  double sum = e.getValue().stream().mapToInt(TacoRating::getScore).sum();
                  double avg = count > 0 ? sum / count : 0.0;
                  return new RatingAggregateResult(e.getKey(), count, avg);
                })
                .sorted(Comparator.comparing(RatingAggregateResult::getAverageScore, Comparator.reverseOrder())
                    .thenComparing(RatingAggregateResult::getTotalRatings, Comparator.reverseOrder())
                    .thenComparing(RatingAggregateResult::getId))
                .limit(finalLimit)
                .collect(Collectors.toList());
          });
    }

    return rankResultsMono
        .flatMapMany(rankResults -> {
          if (rankResults.isEmpty()) {
            return Flux.empty();
          }

          List<String> tacoIds = rankResults.stream()
              .map(RatingAggregateResult::getId)
              .collect(Collectors.toList());

          // OBTENCIÓN EFICIENTE EN 1 SOLA CONSULTA BATCH (Cero N+1)
          return tacoRepo.findAllById(tacoIds)
              .collectMap(Taco::getId, Function.identity())
              .flatMapMany(tacoMap -> Flux.fromIterable(rankResults)
                  .map(rank -> {
                    Taco taco = tacoMap.get(rank.getId());
                    if (taco == null) return null;
                    double roundedAvg = BigDecimal.valueOf(rank.getAverageScore())
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();
                    return new TacoTopRankItem(taco, roundedAvg, rank.getTotalRatings());
                  })
                  .filter(Objects::nonNull)
              );
        });
  }

  @Data
  @NoArgsConstructor
  public static class RatingAggregateResult {
    @Id
    private String id;
    private long totalRatings;
    private double averageScore;

    public RatingAggregateResult(String id, long totalRatings, double averageScore) {
      this.id = id;
      this.totalRatings = totalRatings;
      this.averageScore = averageScore;
    }
  }
}
