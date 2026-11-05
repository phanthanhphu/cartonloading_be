package org.bsl.cartonloading.buyers.lululemon.repository;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.persistence.SharedDeviceConfigurationStore;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class ScalePlcDeviceRepository {
    private static final String SOURCE=AppConstants.LegacyCollection.SCALE_PLC_DEVICES;
    private final SharedDeviceConfigurationStore store;
    public ScalePlcDeviceRepository(SharedDeviceConfigurationStore store){this.store=store;}
    public List<ScalePlcDevice> findAll(){return store.list(SOURCE,ScalePlcDevice.class);}
    public Optional<ScalePlcDevice> findById(String id){return store.find(SOURCE,id,ScalePlcDevice.class);}
    public boolean existsById(String id){return findById(id).isPresent();}
    public ScalePlcDevice save(ScalePlcDevice item){return store.save(SOURCE,item,ScalePlcDevice::getStationCode,ScalePlcDevice::setStationCode,false);}
    public ScalePlcDevice insert(ScalePlcDevice item){return store.save(SOURCE,item,ScalePlcDevice::getStationCode,ScalePlcDevice::setStationCode,true);}
    public void delete(ScalePlcDevice item){store.delete(SOURCE,item.getStationCode());}
}
