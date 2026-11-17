package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/** Buyer-specific Excel filenames and download headers. No changes to exported workbook bytes. */
@Service
public class WorkflowExcelFileService {
    public org.springframework.http.ResponseEntity<byte[]> template(byte[] data) {
        return excelDownload(data, "Lululemon_PO_Import_Template.xlsx");
    }
    public org.springframework.http.ResponseEntity<byte[]> export(byte[] data) {
        String timestamp = java.time.ZonedDateTime.now(java.time.ZoneId.of(AppConstants.Excel.EXPORT_ZONE))
            .format(java.time.format.DateTimeFormatter.ofPattern(AppConstants.Excel.EXPORT_TIMESTAMP_FORMAT));
        return excelDownload(data, AppConstants.Excel.LULULEMON_EXPORT_PREFIX + timestamp + ".xlsx");
    }
    private org.springframework.http.ResponseEntity<byte[]> excelDownload(byte[] data, String filename) {
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }
}
