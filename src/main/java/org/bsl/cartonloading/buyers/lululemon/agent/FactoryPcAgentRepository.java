package org.bsl.cartonloading.buyers.lululemon.agent;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface FactoryPcAgentRepository extends MongoRepository<FactoryPcAgentRegistration, String> {
    List<FactoryPcAgentRegistration> findByFactoryCodeOrderByDisplayNameAsc(String factoryCode);
}
