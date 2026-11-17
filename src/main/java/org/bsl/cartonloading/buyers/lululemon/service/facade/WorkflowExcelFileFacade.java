package org.bsl.cartonloading.buyers.lululemon.service.facade;
import org.bsl.cartonloading.buyers.lululemon.service.WorkflowExcelFileService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.service.facade.BuyerBoundService;
import org.springframework.stereotype.Service;
@Service
public class WorkflowExcelFileFacade extends BuyerBoundService<WorkflowExcelFileService> {
    public WorkflowExcelFileFacade(BuyerRequestScope scope, WorkflowExcelFileService impl) {
        super(scope, java.util.Map.of(AppConstants.Buyer.LULULEMON, impl));
    }
}
