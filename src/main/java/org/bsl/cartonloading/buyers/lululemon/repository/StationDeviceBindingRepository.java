package org.bsl.cartonloading.buyers.lululemon.repository;
import org.bsl.cartonloading.buyers.lululemon.model.StationDeviceBinding;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.persistence.SharedDeviceConfigurationStore;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class StationDeviceBindingRepository {
    private static final String SOURCE=AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS;
    private final SharedDeviceConfigurationStore store;
    public StationDeviceBindingRepository(SharedDeviceConfigurationStore store){this.store=store;}
    public List<StationDeviceBinding> findAll(){return store.list(SOURCE,StationDeviceBinding.class);}
    public Optional<StationDeviceBinding> findById(String id){return store.find(SOURCE,id,StationDeviceBinding.class);}
    public List<StationDeviceBinding> findByFactoryCodeAndStationCode(String factoryCode,String stationCode){
        return findAll().stream().filter(b -> Objects.equals(b.getFactoryCode(),factoryCode)
                && Objects.equals(b.getStationCode(),stationCode)).toList();
    }
    public StationDeviceBinding save(StationDeviceBinding item){return store.save(SOURCE,item,StationDeviceBinding::getId,StationDeviceBinding::setId,false);}
    public void deleteById(String id){store.delete(SOURCE,id);}
}
