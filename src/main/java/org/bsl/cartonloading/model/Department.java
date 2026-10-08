package org.bsl.cartonloading.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.DEPARTMENTS)
public class Department {
    @Id private String id;
    private String factory;
    private String division;
    private String departmentName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
