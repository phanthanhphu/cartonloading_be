package org.bsl.cartonloading.buyers.lululemon.repository;
import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.persistence.SharedDeviceConfigurationStore;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class RfidPdaDeviceRepository {
    private static final String SOURCE=AppConstants.LegacyCollection.RFID_PDA_DEVICES;
    private final SharedDeviceConfigurationStore store;
    public RfidPdaDeviceRepository(SharedDeviceConfigurationStore store){this.store=store;}
    public List<RfidPdaDevice> findAll(){return store.list(SOURCE,RfidPdaDevice.class);}
    public Optional<RfidPdaDevice> findById(String id){return store.find(SOURCE,id,RfidPdaDevice.class);}
    public boolean existsById(String id){return findById(id).isPresent();}
    public RfidPdaDevice save(RfidPdaDevice item){return store.save(SOURCE,item,RfidPdaDevice::getSerial,RfidPdaDevice::setSerial,false);}
    public RfidPdaDevice insert(RfidPdaDevice item){return store.save(SOURCE,item,RfidPdaDevice::getSerial,RfidPdaDevice::setSerial,true);}
    public void deleteById(String id){store.delete(SOURCE,id);}
}
