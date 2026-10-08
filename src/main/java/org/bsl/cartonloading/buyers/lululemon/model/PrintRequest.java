package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.PRINT_HANDOFFS)
@CompoundIndexes({
        @CompoundIndex(name = "idx_print_handoff_buyer_status_created", def = "{'buyerCode':1,'status':1,'createdAt':-1}"),
        @CompoundIndex(name = "idx_print_handoff_buyer_order_created", def = "{'buyerCode':1,'orderId':1,'createdAt':-1}"),
        @CompoundIndex(name = "idx_print_handoff_buyer_packing_created", def = "{'buyerCode':1,'packingEmail':1,'createdAt':-1}")
})
public class PrintRequest {
    @Id
    private String id;
    private String requestNo;
    private String buyerCode;
    private String orderId;
    private String orderName;

    /** Packing user who sent the electronic PO handoff. */
    private String packingUser;
    private String packingEmail;
    private String packingDepartment;
    private String packingNote;

    /** One handoff may contain several POs. Factory codes are denormalized for list filtering. */
    private List<String> factoryCodes = new ArrayList<>();
    private List<PrintRequestPo> pos = new ArrayList<>();
    private Integer poCount = 0;

    /** SENT -> PRINTING -> PRINTED_SENT_TO_PACKING. SENT/PENDING may be CANCELLED by the sender/admin. */
    private String status = AppConstants.PrintRequestStatus.SENT;
    private String statusUpdatedBy;
    private String statusNote;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime statusUpdatedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
