package tacos.data;

import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.mongodb.client.result.UpdateResult;

import reactor.core.publisher.Mono;

@Component
public class LegacyPaymentDataMigration {

  private final ReactiveMongoTemplate mongoTemplate;

  public LegacyPaymentDataMigration(ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public Mono<UpdateResult> purgeLegacyFieldsFromOrders() {
    Update unsetUpdate = new Update()
        .unset("ccNumber")
        .unset("ccCVV")
        .unset("ccExpiration");

    return mongoTemplate.updateMulti(new Query(), unsetUpdate, "tacoOrder");
  }

  public Mono<UpdateResult> purgeLegacyFieldsFromPaymentMethods() {
    Update unsetUpdate = new Update()
        .unset("ccNumber")
        .unset("ccCVV")
        .unset("ccExpiration");

    return mongoTemplate.updateMulti(new Query(), unsetUpdate, "paymentMethod");
  }

  public Mono<Void> purgeAllLegacyPaymentData() {
    return Mono.zip(purgeLegacyFieldsFromOrders(), purgeLegacyFieldsFromPaymentMethods()).then();
  }
}
