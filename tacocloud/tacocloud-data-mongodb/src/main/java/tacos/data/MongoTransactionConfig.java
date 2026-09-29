package tacos.data;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;

import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Configuración transaccional reactiva para MongoDB.
 *
 * ADVERTENCIA ARQUITECTÓNICA DE DESPLIEGUE:
 * La activación de `tacocloud.mongo.transactions.enabled=true` registra el TransactionManager y
 * TransactionalOperator reactivo, habilitando transacciones multi-documento ACID.
 * Esto REQUIERE OBLIGATORIAMENTE que MongoDB esté desplegado en topología Replica Set (o Mongos en Sharding).
 *
 * En instalaciones MongoDB standalone (ej. un nodo local por defecto o tests embebidos), MongoDB
 * rechaza transacciones multi-documento con 'Command failed with error 20 (IllegalOperation)'.
 * Cuando no se dispone de Replica Set, `tacocloud.mongo.transactions.enabled` debe ser `false` (valor por defecto),
 * y los servicios de mensajería (OutboxService) aplican compensación reactiva garantizada para evitar pedidos huérfanos.
 */
@Configuration
public class MongoTransactionConfig {

  @Bean
  @ConditionalOnProperty(name = "tacocloud.mongo.transactions.enabled", havingValue = "true", matchIfMissing = false)
  public ReactiveMongoTransactionManager transactionManager(ReactiveMongoDatabaseFactory dbFactory) {
    return new ReactiveMongoTransactionManager(dbFactory);
  }

  @Bean
  @ConditionalOnProperty(name = "tacocloud.mongo.transactions.enabled", havingValue = "true", matchIfMissing = false)
  public TransactionalOperator transactionalOperator(ReactiveMongoTransactionManager txManager) {
    return TransactionalOperator.create(txManager);
  }

}
