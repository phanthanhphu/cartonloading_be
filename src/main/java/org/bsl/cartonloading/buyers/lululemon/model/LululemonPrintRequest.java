package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "lululemon_print_requests")
@CompoundIndexes({
        @CompoundIndex(name = "ix_lulu_print_buyer_status_created", def = "{'buyerCode':1,'status':1,'createdAt':-1}"),
        @CompoundIndex(name = "ix_lulu_print_order_created", def = "{'buyerCode':1,'orderId':1,'createdAt':-1}"),
        @CompoundIndex(name = "ix_lulu_print_packing_created", def = "{'buyerCode':1,'packingEmail':1,'createdAt':-1}")
})
public class LululemonPrintRequest {
    @Id
    private String id;
    private String requestNo;
    private String buyerCode = "LULULEMON";
    private String orderId;
    private String orderName;

    /** Packing user who sent the electronic PO handoff. */
    private String packingUser;
    private String packingEmail;
    private String packingDepartment;
    private String packingNote;

    /** One handoff may contain several POs. Factory codes are denormalized for list filtering. */
    private List<String> factoryCodes = new ArrayList<>();
    private List<LululemonPrintRequestPo> pos = new ArrayList<>();
    private Integer poCount = 0;

    /** SENT when Packing hands the PO list to Print Room. SENT may be CANCELLED by the sender/admin. */
    private String status = "SENT";

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
