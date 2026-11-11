package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.es.repository.CartonScanTransactionRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingAllocationLineRepository;
import org.bsl.cartonloading.buyers.es.repository.PackingListLineRepository;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonItemRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonOrderCascadeDeleteService;
import org.bsl.cartonloading.buyers.lululemon.service.WorkflowService;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderManagementChannelFilterTest {
    private static final String BUYER = "LULULEMON";
    private static final String ORDER = "order-1";

    private final PackingOrderService orders = mock(PackingOrderService.class);
    private final PurchaseOrderRepository poRepository = mock(PurchaseOrderRepository.class);
    private final CartonRepository cartonRepository = mock(CartonRepository.class);
    private final BuyerRepository buyerRepository = mock(BuyerRepository.class);
    private final SelectedFactoryScope factoryScope = mock(SelectedFactoryScope.class);
    private final OrderManagementService service = new OrderManagementService(
            orders, mock(PackingListLineRepository.class), mock(PackingAllocationLineRepository.class),
            mock(CartonScanTransactionRepository.class), poRepository, cartonRepository,
            mock(CartonItemRepository.class), mock(WorkflowService.class), mock(AuditLogService.class),
            buyerRepository, mock(MongoTemplate.class), factoryScope,
            mock(LululemonOrderCascadeDeleteService.class));

    private static PurchaseOrder po(String id, String number, String channel) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id);
        po.setOrderId(ORDER);
        po.setBuyerCode(BUYER);
        po.setFactoryCode("F3");
        po.setPoNumber(number);
        po.setChannel(channel);
        po.setCartonCount(1);
        po.setTotalQty(30);
        po.setQtyPerCarton(30);
        return po;
    }

    private void seed() {
        Buyer buyer = new Buyer();
        buyer.setBuyerKey(BUYER);
        buyer.setActive(true);
        when(buyerRepository.findByBuyerKeyIgnoreCase(BUYER)).thenReturn(Optional.of(buyer));
        when(factoryScope.constrain(BUYER, "F3")).thenReturn("F3");
        when(factoryScope.matches("F3", "F3")).thenReturn(true);
        when(poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER)).thenReturn(List.of(
                po("p1", "001", "Retail"), po("p2", "002", "Ecommerce"), po("p3", "003", "RETAIL ONLINE")));
        when(cartonRepository.findByPoIdIn(anyList())).thenReturn(List.of());
    }

    private Page<PoManagementRow> list(String channel, int page, int size) {
        return service.listPos(BUYER, ORDER, null, "F3", null, null, null, null,
                null, null, null, null, null, null, null, null, null, channel, page, size);
    }

    @Test
    void channelFilterMatchesCaseInsensitiveAndCountsBeforePagination() {
        seed();
        Page<PoManagementRow> first = list("ret", 0, 1);
        assertEquals(2, first.getTotalElements());
        assertEquals("001", first.getContent().get(0).poNumber());
        Page<PoManagementRow> second = list("ret", 1, 1);
        assertEquals(2, second.getTotalElements());
        assertEquals("003", second.getContent().get(0).poNumber());
    }

    @Test
    void blankChannelKeepsExistingResultsAndUnknownChannelReturnsNoRows() {
        seed();
        assertEquals(3, list("  ", 0, 10).getTotalElements());
        assertEquals(0, list("Wholesale", 0, 10).getTotalElements());
    }
}
