package org.bsl.cartonloading.buyers.es.repository;

import org.bsl.cartonloading.buyers.es.model.ScaleStation;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.persistence.SharedDeviceConfigurationStore;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Adapter keeps the existing ES service interface while storing config in one shared collection. */
@Repository
public class ScaleStationRepository {
    private static final String SOURCE = AppConstants.LegacyCollection.SCALE_STATIONS;
    private final SharedDeviceConfigurationStore store;
    public ScaleStationRepository(SharedDeviceConfigurationStore store) { this.store = store; }
    public List<ScaleStation> findAllByOrderByStationCodeAsc() {
        List<ScaleStation> rows = store.list(SOURCE, ScaleStation.class);
        rows.sort(Comparator.comparing(ScaleStation::getStationCode, Comparator.nullsLast(String::compareTo)));
        return rows;
    }
    public List<ScaleStation> findByActiveTrueOrderByStationCodeAsc() {
        return findAllByOrderByStationCodeAsc().stream().filter(ScaleStation::isActive).toList();
    }
    public Optional<ScaleStation> findByStationCode(String code) {
        return findAllByOrderByStationCodeAsc().stream().filter(s -> Objects.equals(s.getStationCode(),code)).findFirst();
    }
    public boolean existsByStationCode(String code) { return findByStationCode(code).isPresent(); }
    public ScaleStation save(ScaleStation item) { return store.save(SOURCE,item,ScaleStation::getId,ScaleStation::setId,false); }
}
