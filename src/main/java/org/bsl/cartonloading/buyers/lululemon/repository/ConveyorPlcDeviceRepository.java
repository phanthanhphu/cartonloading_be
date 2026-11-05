package org.bsl.cartonloading.buyers.lululemon.repository;
import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.persistence.SharedDeviceConfigurationStore;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class ConveyorPlcDeviceRepository {
    private static final String SOURCE=AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES;
    private final SharedDeviceConfigurationStore store;
    public ConveyorPlcDeviceRepository(SharedDeviceConfigurationStore store){this.store=store;}
    public List<ConveyorPlcDevice> findAll(){return store.list(SOURCE,ConveyorPlcDevice.class);}
    public Optional<ConveyorPlcDevice> findById(String id){return store.find(SOURCE,id,ConveyorPlcDevice.class);}
    public ConveyorPlcDevice save(ConveyorPlcDevice item){return store.save(SOURCE,item,ConveyorPlcDevice::getDeviceCode,ConveyorPlcDevice::setDeviceCode,false);}
    public ConveyorPlcDevice saveIfCurrent(ConveyorPlcDevice item,long expectedRevision){return store.saveConveyorIfCurrent(item,expectedRevision);}
    public void delete(ConveyorPlcDevice item){store.delete(SOURCE,item.getDeviceCode());}
}
