package org.bsl.cartonloading.buyers.lululemon.model;

import org.bsl.cartonloading.common.constants.AppConstants;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShippingScheduleHistoryEntry {
    private String action;
    private String userId;
    private String note;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime createdAt;
}
