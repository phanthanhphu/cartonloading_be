package org.bsl.cartonloading.common.constants;

import java.util.Collection;

/**
 * Centralized user-facing validation/error messages for backend workflows.
 * Dynamic messages are exposed as small helpers so services do not assemble text inline.
 */
public final class AppMessages {
    private AppMessages() { }


    public static final String INVALID_CREDENTIALS = "Email or password is incorrect";
    public static final String ACCOUNT_DISABLED = "Your account has been disabled. Please contact the administrator.";
    public static final String SELECT_VALID_BUYER = "Please select a valid Buyer";
    public static final String SELECTED_BUYER_INACTIVE = "The selected Buyer is inactive or no longer exists";
    public static final String SELECTED_BUYER_ACCESS_DENIED = "Your account does not have permission to access the selected Buyer";
    public static final String AUTHENTICATED_USER_NOT_FOUND = "Authenticated user no longer exists";
    public static final String USER_NOT_FOUND = "User not found";
    public static final String VALIDATION_FAILED = "Validation failed";
    public static final String UPLOADED_EXCEL_TOO_LARGE = "Uploaded Excel file is too large";
    public static final String IO_ERROR_OCCURRED = "I/O error occurred";
    public static final String UNEXPECTED_ERROR_OCCURRED = "Unexpected error occurred";
    public static final String EXCEL_FILE_REQUIRED = "Excel file is required";
    public static final String EXCEL_FILE_TOO_LARGE = "Excel file must not exceed 20 MB";
    public static final String EXCEL_FILE_TYPE_UNSUPPORTED = "Only .xlsx or .xls files are supported";
    public static final String PACKING_LIST_SHEET_REQUIRED = "Excel does not contain a Packing List sheet";
    public static final String PACKING_LIST_HEADER_NOT_FOUND = "Packing List header row was not found";
    public static final String ALLOCATION_HEADER_NOT_FOUND = "Sheet ALLOCATION does not have a header row";
    public static final String ALLOCATION_IMPORT_EMPTY = "Sheet ALLOCATION does not contain actionable data rows";
    public static final String PACKING_LIST_IMPORT_EMPTY = "The Packing List sheet does not contain data rows";
    public static final String CARTON_MASTER_REQUIRES_ORDER_ITEMS = "Upload the WSP ALLOCATION / Order Items before generating Carton Master data.";
    public static final String CARTON_MASTER_ALREADY_EXISTS = "Carton Master data already exists for this Order.";
    public static final String DIVISION_REQUIRED = "Division is required";
    public static final String DEPARTMENT_NAME_REQUIRED = "Department name is required";
    public static final String FACTORY_REQUIRED = "Factory is required";
    public static final String USERNAME_REQUIRED = "Username is required";
    public static final String EMAIL_REQUIRED = "Email is required";
    public static final String REQUEST_FAILED = "Request failed";
    public static final String AUTHENTICATION_REQUIRED_LOGIN_AGAIN = "Authentication is required. Please login again.";
    public static final String ACTION_PERMISSION_DENIED = "You do not have permission to perform this action.";
    public static final String CARTON_ALREADY_FINISHED_SCAN_REASON = "Carton is already finished";
    public static final String CARTON_PLANNED_QUANTITY_REACHED_SCAN_REASON = "Carton already contains the planned quantity";
    public static final String CARTON_LABEL_NOT_CONFIRMED_SCAN_REASON = "Carton label has not been confirmed";
    public static final String SSCC_ALREADY_ASSIGNED_SCAN_REASON = "SSCC is already assigned";
    public static final String FIRST_SKU_ASSIGNED = "First SKU assigned to PO. All remaining products in this PO must use the same SKU.";
    public static final String SKU_ACCEPTED = "SKU accepted.";
    public static final String CARTON_LABEL_SKU_MISMATCH_SCAN_REASON = "Carton label SKU does not match PO SKU";
    public static final String SSCC_ASSIGNED_SUCCESSFULLY = "SSCC assigned successfully";
    public static final String WEIGHING_PASS_COMPLETED = "PASS - carton completed weighing.";
    public static final String WEIGHING_FAILED = "FAILED - weight is outside the allowed tolerance.";
    public static final String PACKING_ALLOCATION_ROW_NOT_FOUND = "Packing allocation row not found";
    public static final String ORDER_NOT_FOUND = "Order not found";
    public static final String ORDER_NAME_REQUIRED = "Order Name is required";
    public static final String ORDER_NAME_MAX_200 = "Order Name must not exceed 200 characters";
    public static final String SUPPLIER_NAME_MAX_200 = "Supplier Name must not exceed 200 characters";
    public static final String SUPPLIER_NUMBER_MAX_80 = "e.s. Supplier # must not exceed 80 characters";
    public static final String PRODUCTION_FACILITY_MAX_120 = "Production Facility must not exceed 120 characters";
    public static final String CTNS_QTY_REQUIRED = "CTNS Qty is required";
    public static final String PO_HASH_REQUIRED = "P.O. # is required";
    public static final String STYLE_HASH_REQUIRED = "Style # is required";
    public static final String ARTICLE_NO_REQUIRED = "Art.no. is required";
    public static final String COLOR_REQUIRED = "Color is required";
    public static final String SIZE_REQUIRED = "Size is required";
    public static final String QTY_CTN_REQUIRED = "Qty/CTN is required";
    public static final String TOTAL_PCS_REQUIRED = "Total PCS is required";
    public static final String ES_PO_REQUIRED = "e.s. PO # is required";
    public static final String ES_ARTICLE_REQUIRED = "e.s. Article # is required";
    public static final String STYLE_UPPER_HASH_REQUIRED = "STYLE# is required";
    public static final String STYLE_UPPER_REQUIRED = "STYLE is required";
    public static final String QTY_PER_CTN_REQUIRED = "Qty Per Ctn is required";
    public static final String TOTAL_PCS_LOWER_REQUIRED = "Total pcs is required";
    public static final String TOTAL_CTNS_REQUIRED = "Total ctns is required";
    public static final String WSP_ITEM_REQUIRED = "WSP item is required";
    public static final String CORRECTION_REASON_REQUIRED = "Correction reason is required";
    public static final String BARCODE_REQUIRED = "Barcode is required";
    public static final String QA_CODE_REQUIRED = "QA Code is required";
    public static final String MANUAL_INPUT_REASON_REQUIRED = "Manual input reason is required";
    public static final String PACKING_LIST_ROW_REQUIRED = "Packing List row is required";
    public static final String FACTORY_CODE_REQUIRED = "Factory Code is required";
    public static final String STATION_NAME_REQUIRED = "Station name is required";
    public static final String INVALID_DATA = "Invalid data";
    public static final String CARTON_MASTER_NOT_GENERATED_NO_REFRESH = "Carton Master has not been generated yet; no physical-carton refresh was required.";
    public static final String QA_BARCODE_NO_MASTER_MATCH = "QR/barcode does not match any generated Master Data item in the selected Order.";
    public static final String QA_BARCODE_AMBIGUOUS_MASTER = "More than one item uses this PO/Article. Select the correct Size/Color item.";
    public static final String BARCODE_NO_PACKING_LIST_MATCH = "Barcode does not match any Packing List row in the selected Order.";
    public static final String BARCODE_AMBIGUOUS_PACKING_LIST = "More than one Packing List row uses this Article. Select the correct Size/Color row.";
    public static final String PACKING_COMPLETION_CONFIRMED = "Packing completion confirmed";
    public static final String INVALID_ACTION_TITLE = "Invalid ACTION";
    public static final String INVALID_ACTION_HELP = "Use CREATE, UPDATE or DELETE only.";
    public static final String SSCC_ACCEPTED_FOR_WEIGHING = "SSCC accepted. Load this carton on the scale and wait for a stable weight.";
    public static final String CARTON_LABEL_FIFO_INSTRUCTION = "Select the carton number shown on the physical carton label, scan SSCC-18 first, confirm the system Unit Qty, then scan SKU.";
    public static final String ITEM_MATCHED_AUTO_CHILD = "Item matched. The first unfinished child will be selected automatically when scanned.";

    public static final String SELECT_PO_FOR_WEIGHING = "Select at least one completed PO for the Weighing Order.";
    public static final String WEIGHING_SINGLE_FACTORY = "One Weighing Order can contain POs from only one Factory.";
    public static final String VALID_SSCC_REQUIRED = "Scan a valid 18-digit SSCC.";
    public static final String SCALE_NOT_STABLE = "Scale signal is not Stable. Weight was not accepted.";
    public static final String REWEIGH_REASON_REQUIRED = "Reason is required for Re-weigh.";
    public static final String OVERRIDE_REASON_REQUIRED = "Reason is required to Override FAILED to PASS.";
    public static final String CARTON_NOT_FAILED = "Only a carton currently FAILED can be overridden to PASS.";
    public static final String CARTON_NOT_WEIGHED = "This carton has not been weighed yet.";
    public static final String CARTON_NOT_IN_WEIGHING_ORDER = "Carton Not in This Weighing Order.";
    public static final String SSCC_NOT_IN_OPEN_WEIGHING_ORDER = "This SSCC is not assigned to any Weighing Order in the active Order. Send the completed PO to Carton Weight first.";
    public static final String SSCC_NOT_IN_ASSIGNED_SHIPPING = "This SSCC-18 is not in a Shipping List assigned to Carton Weight.";
    public static final String CARTON_NOT_READY_FOR_WEIGHING = "Not Ready for Weighing. Carton must finish Carton Loading and have SSCC assigned.";
    public static final String WEIGHING_PO_NOT_IN_QUEUE = "This PO has not been sent to Carton Weight.";
    public static final String WEIGHING_SESSION_NOT_RUNNING = "Press START for the selected PO before scanning or accepting weight.";
    public static final String WEIGHING_SSCC_WRONG_PO = "This SSCC belongs to a different logical PO. Select the correct PO before weighing.";
    public static final String WEIGHING_PO_HAS_REMAINING = "Cannot COMPLETE: some cartons have not been weighed yet.";
    public static final String WEIGHING_PO_HAS_FAILED = "Cannot COMPLETE: resolve all FAILED cartons first.";
    public static final String WEIGHING_SESSION_STARTED = "Carton Weighing started.";
    public static final String WEIGHING_SESSION_STOPPED = "Carton Weighing stopped.";
    public static final String WEIGHING_SESSION_COMPLETED = "PO Carton Weighing completed.";

    public static final String ALL_BP_EMPTY = "Sheet All_BP does not contain any PO rows.";
    public static final String PO_DATA_REQUIRED = "PO data is required.";
    public static final String PO_NUMBER_REQUIRED = "PO No. is required.";
    public static final String STYLE_REQUIRED = "Style is required.";
    public static final String SKU_BARCODE_REQUIRED = "SKU barcode is required.";
    public static final String CARTON_FINISHED_SCAN_BLOCKED = "Carton is already finished. Reopen/correct it before scanning more products.";
    public static final String FINISHED_CARTON_UNDO_BLOCKED = "Finished cartons cannot be changed with Undo.";
    public static final String NO_ACCEPTED_SCAN_TO_UNDO = "This carton has no accepted scan to undo.";
    public static final String PO_SKU_NOT_ASSIGNED = "PO SKU has not been assigned yet.";
    public static final String PO_AND_EX_FTY_REQUIRED = "PO number and Ex-fty Date are required.";
    public static final String CARTON_LABEL_SKU_REQUIRED = "Scan the SKU barcode on the Carton Loading label.";
    public static final String CARTON_LABEL_PO_REQUIRED = "Scan or enter the PO number on the Carton Loading label.";
    public static final String CARTON_IDENTITY_STATUS_INVALID = "This carton cannot be identified in its current status.";
    public static final String FINISHED_CARTON_REQUIRED_FOR_LABEL = "Only an unstarted or finished carton can be confirmed against a Carton Loading label.";
    public static final String CARTON_QUANTITY_INCOMPLETE = "System Unit Qty is missing or invalid for this carton.";
    public static final String SSCC_REQUIRED = "SSCC-18 barcode is required.";
    public static final String LABEL_CONFIRMATION_REQUIRED_BEFORE_SSCC = "Confirm SKU and physical carton information before scanning SSCC-18.";
    public static final String SSCC_REQUIRED_BEFORE_QUANTITY_CONFIRMATION = "Scan and assign SSCC-18 before confirming Unit Qty.";
    public static final String QUANTITY_CONFIRMATION_REQUIRED_BEFORE_SKU = "Confirm the system Unit Qty before scanning SKU.";
    public static final String RESET_SKU_REQUIRES_REOPEN = "Re-open finished cartons and unassign SSCC before resetting the PO SKU.";
    public static final String UNASSIGN_SSCC_BEFORE_REOPEN = "Unassign SSCC before re-opening this carton.";
    public static final String WEIGHING_RESULT_BLOCKS_CARTON_REOPEN = "This carton already has a weighing result. Re-open/resolve Weighing before re-opening the carton.";
    public static final String CARTON_REOPEN_STATUS_INVALID = "Only a Finished or Label Confirmed carton can be re-opened.";
    public static final String CARTON_HAS_NO_SSCC = "This carton does not have an assigned SSCC.";
    public static final String WEIGHING_RESULT_BLOCKS_SSCC_UNASSIGN = "This carton already has a weighing result. Re-open/resolve Weighing before unassigning SSCC.";
    public static final String TRACE_KEY_REQUIRED = "PO, SKU, Carton ID or SSCC is required.";
    public static final String PO_OPERATION_LOCKED = "PO cannot be edited or deleted after Packing/Carton Loading has started.";
    public static final String PO_HANDOFF_LOCKED = "PO is already in an active Print Room handoff. Cancel the handoff before editing or deleting this PO.";
    public static final String ALL_BP_REPLACE_LOCKED = "All_BP cannot be replaced after Packing has started. Create a new Order or correct the operational data first.";
    public static final String ALL_BP_HEADER_NOT_FOUND = "Cannot find the All_BP header row. Required headers include PO, Style, Q'ty and Pcs/ctn.";
    public static final String SHIPPING_HEADERS_NOT_FOUND = "Cannot find Shipping List headers PO and Ex-fty Date.";
    public static final String ALL_BP_ROW_COUNT_IMMUTABLE = "ALL_BP row count cannot be changed while editing a PO. Re-import the workbook to add/remove source rows.";
    public static final String ALL_BP_PO_NUMBER_MUST_MATCH = "All ALL_BP source rows for one imported PO must keep the same PO number.";
    public static final String ALL_BP_STYLE_MUST_MATCH = "All ALL_BP source rows for one imported PO must keep the same Style#.";
    public static final String ALL_BP_PCS_CTN_MUST_MATCH = "All ALL_BP source rows for one imported PO must keep the same Pcs/ctn.";
    public static final String EDITED_PO_CARTON_PLAN_REQUIRED = "Edited PO does not contain any carton quantity plan.";
    public static final String EXCEPTION_REASON_REQUIRED = "Reason is required for Supervisor/Admin exception actions.";
    public static final String SSCC_LENGTH_INVALID = "SSCC-18 must contain exactly 18 digits.";

    public static final String HANDOFF_SELECT_PO = "Select at least one PO to send";
    public static final String HANDOFF_MAX_PO = "A PO handoff can contain at most 200 POs";
    public static final String HANDOFF_SINGLE_FACTORY = "Select POs from one Factory per handoff";
    public static final String HANDOFF_CANCEL_STATUS_INVALID = "Only a sent PO handoff can be cancelled";
    public static final String HANDOFF_CANCEL_PERMISSION = "Only the Packing sender, Sales, or Admin can cancel this request";
    public static final String HANDOFF_DELETE_STATUS_INVALID = "Only a SENT or PENDING Print Request can be deleted. PRINTING or completed requests must be kept for history.";
    public static final String HANDOFF_DELETE_PERMISSION = "Only the Packing sender, Sales, or Admin can delete this Print Request.";
    public static final String HANDOFF_ACCESS_DENIED = "You do not have permission to access this PO handoff";
    public static final String HANDOFF_NOT_FOUND = "PO handoff not found";
    public static final String HANDOFF_STATUS_INVALID = "Print Request status transition is not allowed.";
    public static final String HANDOFF_STATUS_PERMISSION = "Only Print Room or Admin can update Print Request processing status.";
    public static final String SHIPPING_SCHEDULE_REVIEW_REQUIRED = "Packing must CHECK the Shipping Schedule before sending it to Carton Weight.";
    public static final String SHIPPING_SCHEDULE_REMOVE_BLOCKED = "Shipping Schedule can only be removed before Packing confirms transfer to the Carton Weight area.";
    public static final String WEIGHING_REQUIRES_SHIPPING_SCHEDULE = "LULULEMON Carton Weight must be created from a reviewed Shipping Schedule.";


    // Shared ES/carton-loading workflow messages.
    public static final String BULK_IMPORT_LOCKED_AFTER_CARTON_MASTER = "Bulk import is locked after Carton Master generation. Edit individual rows before operations start, or create a new Order.";
    public static final String REPORT_TOO_LARGE = "Report exceeds 50,000 cartons. Filter by Order or Shipment first.";
    public static final String ASSIGN_FACTORY_BARCODE_FIRST = "Assign a Factory Barcode first.";
    public static final String FINISH_PENDING_WEIGHT_JOB_FIRST = "Finish the pending weight job first.";
    public static final String INSPECTION_RESULT_PASS_OR_FAIL = "Result must be PASS or FAIL.";
    public static final String WEIGHT_FAILURE_OVERRIDE_BLOCKED = "Weight failure cannot be overridden. Ask Admin to reset and recheck this carton.";
    public static final String ASSIGNED_PASS_REQUIRED_FOR_COMPLETION = "Only an assigned carton with a PASS inspection can be completed. NO_STANDARD alone is not a pass.";
    public static final String SHIPMENT_REQUEST_REQUIRED = "Shipment request is required.";
    public static final String PLANNED_SHIPMENT_DATE_REQUIRED = "Planned shipment date is required.";
    public static final String SELECT_PHYSICAL_CARTONS_RANGE = "Select between 1 and 5,000 physical cartons.";
    public static final String PHYSICAL_CARTON_NOT_FOUND_ORDER = "Physical carton not found in the selected Order.";
    public static final String SHIPMENT_CARTON_SELECTION_INVALID = "Select assigned, unweighed cartons with a confirmed quantity from the same Order. A carton may belong to only one shipment plan.";
    public static final String SHIPMENT_NOT_READY_DISPATCH = "Shipment is not ready for dispatch.";
    public static final String SHIPMENT_CARTONS_MUST_PASS = "Every planned carton must pass inspection and be explicitly completed before shipment.";
    public static final String SHIPMENT_NOT_FOUND_BUYER = "Shipment not found for this Buyer.";
    public static final String LOCKED_CARTON_CHANGE = "Completed, shipped or cancelled cartons cannot be changed.";
    public static final String FACTORY_BARCODE_RANGE_EXCEEDED = "Factory barcode running number exceeded the supported 9-digit range.";
    public static final String FACTORY_BARCODE_EXHAUSTED = "Factory barcode running number is exhausted for this year/factory.";
    public static final String SELECT_BARCODE_PRINT = "Select at least one Factory Barcode to print.";
    public static final String MAX_BARCODE_PRINT_LABELS = "A maximum of 500 labels can be marked printed in one request.";
    public static final String ASSIGNED_BARCODE_VOID_BLOCKED = "Assigned Factory Barcode cannot be VOID. Unassign it from the carton first.";
    public static final String FACTORY_BARCODE_REQUIRED = "Factory Barcode is required";
    public static final String CARTON_REQUIRED = "Carton is required";
    public static final String FACTORY_BARCODE_DIFFERENT_CARTON = "Factory Barcode is assigned to a different carton.";
    public static final String UNABLE_ALLOCATE_FACTORY_BARCODE = "Unable to allocate Factory Barcode running number.";
    public static final String FACTORY_BARCODE_YEAR_RANGE = "Year must be between 2000 and 2099.";
    public static final String FACTORY_CODE_FORMAT = "Factory Code must contain exactly 3 digits, for example 002.";
    public static final String FACTORY_BARCODE_QUANTITY_RANGE = "Quantity must be between 1 and 1000.";
    public static final String STATION_CODE_REQUIRED = "Station code is required";
    public static final String DELETE_ORDER_ITEMS_AFTER_CARTON_MASTER_BLOCKED = "Cannot delete Order Items after Carton Master generation.";
    public static final String ACTION_REQUIRED = "ACTION is required: CREATE, UPDATE or DELETE";
    public static final String DELETE_ORDER_WITH_CARTONS_BLOCKED = "Cannot delete an Order with physical cartons. Preserve carton and shipment traceability.";
    public static final String START_ORDER_REQUIRED = "Start Order is required";
    public static final String END_ORDER_REQUIRED = "End Order is required";
    public static final String END_ORDER_BEFORE_START = "End Order cannot be earlier than Start Order";
    public static final String CARTON_MASTER_REGEN_LOCKED = "Carton Master data cannot be regenerated because Factory Barcode assignment, scanning, or weighing has already started. Unassign unused barcodes first or create a new Order.";
    public static final String CARTON_QUANTITY_CHANGE_LOCKED = "Carton quantity cannot be changed after Barcode Assignment or Weight Check has started. Unassign/reset the affected cartons first, then edit the quantity again.";
    public static final String ASSIGNMENT_REQUEST_REQUIRED = "Assignment request is required";
    public static final String CARTON_SHIPMENT_PLAN_LOCKED = "This carton already belongs to a shipment plan.";
    public static final String CARTON_CHANGED_BEFORE_ASSIGNMENT = "Carton changed before assignment. Refresh the Carton Master list and scan again.";
    public static final String COMPLETED_SHIPPED_CHANGE_LOCKED = "Completed or shipped cartons cannot be changed.";
    public static final String WEIGHT_CHECK_STARTED_BARCODE_CHANGE_BLOCKED = "This carton has already started Weight Check. Reset Weight as Admin before changing its Factory Barcode.";
    public static final String CANCEL_SHIPMENT_BEFORE_UNASSIGN = "Ask Sales to cancel the shipment plan before unassigning this carton.";
    public static final String CARTON_CHANGED_UNASSIGN = "Carton changed. Refresh before unassigning its barcode.";
    public static final String COMPLETED_SHIPPED_RESET_LOCKED = "Completed or shipped cartons are locked and cannot be reset.";
    public static final String NO_WEIGHT_RESET_REQUIRED = "This carton has not started Weight Check, so no weight reset is required.";
    public static final String CANCELLED_CARTON_CORRECTION_BLOCKED = "Cancelled carton cannot be corrected.";
    public static final String CARTON_CONCURRENT_COMPLETION = "Carton was completed concurrently and cannot be reset.";
    public static final String FACTORY_BARCODE_SCAN_REQUEST_REQUIRED = "Factory Barcode scan request is required";
    public static final String SCAN_ID_ALREADY_USED = "Scan ID is already used by another station or Order.";
    public static final String FACTORY_BARCODE_MAPPING_INCONSISTENT = "Factory Barcode mapping is inconsistent. Please contact IT before weighing this carton.";
    public static final String CARTON_TAKEN_BY_OTHER_STATION = "This carton was already taken by another station. Scan again after refreshing.";
    public static final String FACTORY_BARCODE_SCAN_DUPLICATE_OR_BUSY = "Factory Barcode scan was submitted more than once or the station is busy.";
    public static final String QA_SELECTED_ITEM_MISMATCH = "The selected item does not match this QA Code in the selected Order.";
    public static final String QA_NO_MASTER_ITEM = "QA Code does not match any generated Master Data item in the selected Order.";
    public static final String QA_ALL_CARTONS_SCANNED = "All cartons matching this QA Code have already been scanned.";
    public static final String NEXT_CARTON_TAKEN = "The next carton was taken by another scanner. Scan the QA Code again.";
    public static final String QA_SCAN_DUPLICATE = "The QA scan was submitted more than once. Search again when the current child is ready.";
    public static final String ANOTHER_ITEM_JOB_CREATED = "Another item Job was created at this station at the same time. Reload and try again.";
    public static final String ANOTHER_CARTON_JOB_CREATED = "Another carton Job was created at this station at the same time. Reload the station and try again.";
    public static final String SCALE_VALUE_NOT_STABLE = "Scale value is not stable yet";
    public static final String PLC_JOB_NOT_WAITING = "PLC Job is no longer waiting for weight";
    public static final String TRANSACTION_NOT_WAITING = "Transaction is not waiting for weight";
    public static final String WEIGHT_JOB_CHANGED = "Weight job changed or was already completed. Refresh before retrying.";
    public static final String SHIPMENT_PLAN_CHANGED = "Shipment plan changed. Refresh the Packing inbox.";
    public static final String NO_PHYSICAL_CARTONS = "No physical cartons are available. Generate Carton Master before Weight Check.";
    public static final String WEIGHT_GREATER_THAN_ZERO = "Weight must be greater than 0";
    public static final String UNABLE_ALLOCATE_CARTON_JOB = "Unable to allocate carton Job ID";
    public static final String LEGACY_QA_RETIRED = "Legacy QA scanning is retired. Scan the assigned Factory Barcode using factory-barcode/scan.";
    public static final String MANUAL_WEIGHT_WORKFLOW_REQUIRED = "Use assigned Factory Barcode scanning and manual-weight, or the manual inspection workflow.";
    public static final String SCAN_ASSIGNED_FACTORY_BARCODE = "Scan the assigned Factory Barcode using factory-barcode/scan.";
    public static final String SELECT_PLC_AND_SCAN = "Select a PLC station and scan the assigned Factory Barcode.";
    public static final String BARCODE_NOT_IN_SHIPMENT = "This barcode is not a carton in the selected shipment plan.";
    public static final String CARTON_NOT_IN_SHIPMENT = "Carton does not belong to the selected shipment.";
    public static final String PLANNED_QTY_MISSING = "Planned quantity is missing for this physical carton. Ask Sales to correct the Master Data before packing.";
    public static final String ACTUAL_QTY_REQUIRED = "Actual quantity is required before the Factory Barcode can be assigned.";
    public static final String SCAN_ASSIGNED_CARTON_ACTIVE_SHIPMENT = "Scan an assigned carton in an active shipment plan from Sales.";
    public static final String SHIPMENT_CANCEL_LOCKED = "Only a shipment that has not started packing/weight execution can be cancelled.";
    public static final String DEPARTMENT_ALREADY_EXISTS = "Department already exists for this Factory and Division";
    public static final String DEPARTMENT_NOT_FOUND = "Department not found";
    public static final String DEPARTMENT_ASSIGNED_TO_USERS = "Department is assigned to one or more users";
    public static final String FACTORY_RANGE_F1_F7 = "Factory must be F1 to F7";
    public static final String PASSWORD_REQUIRED = "Password is required";
    public static final String EMAIL_ALREADY_EXISTS = "Email already exists";
    public static final String PASSWORD_ENCODING_FAILED = "Password encoding verification failed";
    public static final String PASSWORD_NOT_PERSISTED = "Password was not persisted correctly";
    public static final String PASSWORD_LENGTH = "Password must be between 8 and 200 characters";
    public static final String PASSWORD_NOT_BLANK = "Password cannot be blank";
    public static final String LAST_ADMIN_DELETE_BLOCKED = "Cannot delete the last Admin account";
    public static final String BUYER_PERMISSION_REQUIRED = "At least one Buyer permission is required";
    public static final String BUYER_KEY_INVALID = "Buyer Key is invalid";
    public static final String BUYER_KEY_EXISTS = "Buyer Key already exists";
    public static final String BUYER_KEY_IMMUTABLE = "Buyer Key cannot be changed";
    public static final String BUYER_ASSIGNED_TO_USERS = "Buyer is assigned to one or more users. Remove the Buyer permission from those users first.";
    public static final String BUYER_NAME_REQUIRED = "Buyer Name is required";
    public static final String AUTHENTICATION_REQUIRED = "Authentication is required";
    public static final String AUTHENTICATED_USER_REQUIRED = "Authenticated user is required";
    public static final String ORDER_WITH_PO_DATA_DELETE_BLOCKED = "Cannot delete an Order that already contains PO data.";


    public static final String WSP_ITEM_NOT_FOUND_SELECTED_ORDER = "WSP item not found in the selected Order";
    public static final String CARTON_MASTER_NOT_FOUND_SELECTED_ORDER = "Carton Master record not found in the selected Order";
    public static final String CHILD_ITEM_NOT_FOUND = "Child item record not found";
    public static final String PACKING_LIST_ROW_NOT_FOUND = "Packing List row not found";
    public static final String ITEM_TRANSACTION_NOT_FOUND = "Item transaction not found";
    public static final String KEY_NOT_SELECTED_BUYER_ORDER = "KEY does not belong to the selected Buyer/Order";
    public static final String DATA_CHANGED_REFRESH = "Data changed. Refresh before retrying.";
    public static final String BUYER_NOT_FOUND = "Buyer not found";
    public static final String CURRENT_USER_UNAVAILABLE = "Current User is not available";
    public static final String PO_NOT_FOUND = "PO not found";
    public static final String CARTON_NOT_FOUND = "Carton not found";

    public static String required(String label) {
        return label + " is required.";
    }

    public static String mustBeGreaterThanZero(String label) {
        return label + " must be greater than 0.";
    }

    public static String cannotBeNegative(String label) {
        return label + " cannot be negative.";
    }

    public static String workflowOnlyFor(String buyer) {
        return "This workflow is available only for " + buyer + ".";
    }

    public static String entityNotFound(String entity) {
        return entity + " not found.";
    }

    public static String factoryRequiredForPo(String poNumber) {
        return "Factory is required for PO " + poNumber
                + ". Add a Factory/Production Facility column to All_BP or set Production Facility on the Order.";
    }

    public static String duplicateAllBpPo(String poNumber, int firstRow, int secondRow) {
        return "PO " + poNumber
                + " appears more than once in All_BP with conflicting Factory/Style/Pcs per carton or shipping attributes (rows "
                + firstRow + " and " + secondRow + ").";
    }

    public static String cannotImport(String source, String detail) {
        return "Cannot import " + source + ": " + detail;
    }

    public static String buyerDataAlreadyExists(String buyer) {
        return buyer + " data already exists for this Order. Use replace=true before Packing starts.";
    }

    public static String poAlreadyExists(String poNumber) {
        return "PO " + poNumber + " already exists in this Order.";
    }

    public static String allBpBusinessKeyAlreadyExists(String poNumber) {
        return "PO " + poNumber
                + " already has an ALL_BP record with the same DC Code, Destination, Chanel, Master PO, PO, Packing Plan, SO (PTS), Style#, Description and Color description.";
    }

    public static String cartonAlreadyFinished(Integer cartonNo) {
        return "Carton " + cartonNo + " is already finished and cannot be started again.";
    }

    public static String wrongSku(String poNumber, String sku) {
        return "Wrong SKU. PO " + poNumber + " uses SKU " + sku + ".";
    }

    public static String cartonScanLimit(Integer plannedQty) {
        return "Carton already has " + plannedQty + " accepted product scans.";
    }

    public static String cartonFinishQuantity(int scanned, Integer planned) {
        return "Carton cannot be finished. Scanned " + scanned + " / " + planned + " item(s).";
    }

    public static String poNotInAllBp(String poNumber) {
        return "PO " + poNumber + " does not exist in All_BP.";
    }

    public static String noCartonWaitingForSscc(String sku) {
        return "No carton for SKU " + sku + " is waiting for carton identification / SSCC assignment.";
    }

    public static String skuMatchesMultiplePos(String sku, String poNumbers) {
        return "SKU " + sku + " matches more than one released PO (" + poNumbers + "). Resolve the duplicate before assigning SSCC.";
    }

    public static String cartonLabelSkuMismatch(String poNumber) {
        return "Carton Loading label SKU does not match PO " + poNumber + ".";
    }

    public static String cartonAlreadyHasSscc(String sscc) {
        return "This carton already has SSCC " + sscc + ".";
    }

    public static String ssccAlreadyAssigned(String sscc) {
        return "SSCC " + sscc + " has already been assigned to another carton.";
    }

    public static String poPlanningInvalid(String poNumber) {
        return "PO " + poNumber + " does not contain valid Total Qty / Pcs per Carton planning data.";
    }

    public static String duplicateAllBpColumn(String key, int firstColumn, int secondColumn) {
        return "ALL_BP contains more than one column matching key " + key
                + " (columns " + firstColumn + " and " + secondColumn + "). Rename one of the headers.";
    }

    public static String missingAllBpColumn(String key) {
        return "Missing required ALL_BP column key: " + key;
    }

    public static String missingExcelColumn(Collection<String> aliases) {
        return "Missing required Excel column: " + String.join(" / ", aliases);
    }

    public static String missingExcelColumn(String... aliases) {
        return "Missing required Excel column: " + String.join(" / ", aliases);
    }

    public static String allBpFieldMustMatch(String key) {
        return "ALL_BP field " + key + " must be the same across rows belonging to one PO.";
    }

    public static String allBpRowRequired(String label, int rowNo) {
        return label + " is required in ALL_BP row " + rowNo + ".";
    }

    public static String allBpRowGreaterThanZero(String label, int rowNo) {
        return label + " must be greater than 0 in ALL_BP row " + rowNo + ".";
    }

    public static String allBpRowWholeNumber(String label, int rowNo) {
        return label + " must be a whole number in ALL_BP row " + rowNo + ".";
    }

    public static String allBpRowNumeric(String label, int rowNo, String raw) {
        return label + " must be numeric in ALL_BP row " + rowNo + ", received '" + raw + "'.";
    }

    public static String numeric(String label, String raw) {
        return label + " must be numeric, received '" + raw + "'.";
    }

    public static String excelRowRequired(String field, int rowNo) {
        return field + " is required at Excel row " + rowNo;
    }

    public static String excelRowGreaterThanZero(String field, int rowNo) {
        return field + " must be greater than 0 at Excel row " + rowNo;
    }

    public static String excelRowWholeNumber(String field, int rowNo, Object value) {
        return field + " must be a whole number at Excel row " + rowNo + ", received " + value;
    }

    public static String cartonAutoFinished(Integer cartonNo, int qty) {
        return "Carton " + cartonNo + " reached its target of " + qty + " item(s) and was finished automatically.";
    }

    public static String grossWeightMissing(String poNumber) {
        return "PO " + poNumber + " does not have a valid GW from ALL_BP. GW is required to calculate Target Weight and tolerance.";
    }

    public static String skuAlreadyAssigned(String sku, String poNumber) {
        return "SKU " + sku + " is already assigned to PO " + poNumber
                + ". A SKU can belong to only one LULULEMON PO record in the system. "
                + "If the PO number is duplicated with different ALL_BP business-key data, each record must use a different SKU.";
    }

    public static String configureWeightProfile(String poNumber) {
        return "Configure Unit Weight / Tare / Tolerance for PO " + poNumber + " before creating the Weighing Order.";
    }

    public static String poNotReadyForWeighing(String poNumber) {
        return "PO " + poNumber + " is not fully Ready to Ship with SSCC assigned for every carton.";
    }

    public static String cartonAlreadyInWeighingOrder(String orderName) {
        return "A selected carton already belongs to open Weighing Order " + orderName + ".";
    }

    public static String unknownSscc(String sscc) {
        return "Unknown SSCC " + sscc + ".";
    }

    public static String cartonAlreadyWeighed(Integer cartonNo, String result) {
        return "Carton " + cartonNo + " already has weighing result " + result
                + ". Supervisor/Admin must Re-weigh to open it again.";
    }

    public static String weightProfileMissing(String poNumber) {
        return "Weight profile is missing for PO " + poNumber + ".";
    }

    public static String weightProfileIncomplete(String poNumber) {
        return "Weight profile for PO " + poNumber + " is incomplete.";
    }

    public static String handoffPoNotInOrder(String poId) {
        return "PO is not part of the selected Order: " + poId;
    }

    public static String handoffPoAlreadyQueued(Collection<String> poNumbers) {
        return "PO already exists in an active handoff: " + String.join(", ", poNumbers);
    }

    public static String handoffOnlyFor(String buyer) {
        return "PO handoff is only available for " + buyer;
    }

    public static String tooLong(String field) {
        return field + " is too long.";
    }

    public static String unsupportedLifecycle(Object lifecycle) {
        return "Unsupported lifecycle: " + lifecycle;
    }

    public static String unsupportedBarcodeStatus(Object status) {
        return "Unsupported barcode status: " + status;
    }

    public static String voidBarcodeCannotPrint(String barcode) {
        return "VOID barcode cannot be printed: " + barcode;
    }

    public static String factoryBarcodeAlreadyAssigned(String barcode) {
        return "Factory Barcode " + barcode + " is already assigned to another carton.";
    }

    public static String factoryBarcodeVoidAssignment(String barcode) {
        return "Factory Barcode " + barcode + " is VOID and cannot be assigned.";
    }

    public static String factoryBarcodeVoid(String barcode) {
        return "Factory Barcode is VOID: " + barcode;
    }

    public static String factoryBarcodeAssignedToCarton(Object carton) {
        return "Factory Barcode is already assigned to carton " + carton + ".";
    }

    public static String factoryBarcodeNotAssigned(String barcode) {
        return "Factory Barcode has not been assigned to a carton: " + barcode;
    }

    public static String factoryBarcodeAssignmentIncomplete(String barcode) {
        return "Factory Barcode assignment is incomplete: " + barcode;
    }

    public static String scaleStationInactive(String code) {
        return "Scale station is inactive: " + code;
    }

    public static String scaleStationCodeExists(String code) {
        return "Scale station code already exists: " + code;
    }

    public static String cannotExport(String subject, String detail) {
        return "Cannot export " + subject + ": " + detail;
    }

    public static String keyRequiredFor(String action) {
        return "KEY is required for " + action;
    }

    public static String duplicateKeyFirstUsedAtRow(Object row) {
        return "Duplicate KEY in file; first used at row " + row;
    }

    public static String unsupportedAction(Object action) {
        return "Unsupported ACTION " + action;
    }

    public static String invalidAction(Object value) {
        return "Invalid ACTION '" + value + "'. Use CREATE, UPDATE or DELETE";
    }

    public static String unsupportedBuyer(Object value) {
        return "Unsupported Buyer: " + value;
    }

    public static String factoryBarcodeStatusBlocksAssignment(Object status) {
        return "Factory Barcode can only be assigned before weighing starts. Carton is " + status + ".";
    }

    public static String cartonAlreadyHasFactoryBarcode(Object cartonCode, String barcode) {
        return "Carton " + cartonCode + " already has Factory Barcode " + barcode + ".";
    }

    public static String factoryBarcodeAssignedByAnotherOperator(String barcode) {
        return "Factory Barcode " + barcode + " was assigned by another operator.";
    }

    public static String factoryBarcodeBelongsToAnotherBuyerOrder(String barcode) {
        return "Factory Barcode belongs to another Buyer/Order: " + barcode;
    }

    public static String cartonAlreadyStatus(Object cartonCode, Object status) {
        return "Carton " + cartonCode + " is already " + status + ".";
    }

    public static String itemAlreadyStatus(Object itemCode, Object status) {
        return "Item " + itemCode + " is already " + status + ".";
    }

    public static String stationJobWaiting(Object stationCode, Object jobId) {
        return "Station " + stationCode + " already has Job " + jobId + " waiting for weight.";
    }

    public static String stationJobWaitingForPlc(Object stationCode, Object jobId) {
        return "Station " + stationCode + " already has Job " + jobId + " waiting for PLC weight.";
    }

    public static String qaCodeAmbiguous(Object choices) {
        return "QA Code matches more than one unfinished Master Data row (" + choices + "). Select the correct Size/Color item first.";
    }

    public static String scannedQrMismatch(Object poNumber, Object articleNumber) {
        return "Scanned QR does not match PO " + poNumber + " / Article " + articleNumber;
    }

    public static String scannedBarcodeArticleMismatch(Object articleNumber) {
        return "Scanned barcode does not match Article " + articleNumber;
    }

    public static String packingListRowComplete(Object actual, Object planned) {
        return "This Packing List row is already complete: " + actual + "/" + planned + " cartons.";
    }

    public static String packingListNoRemainingSlot(Object actual, Object planned) {
        return "This Packing List row has no remaining carton slot: " + actual + "/" + planned + ".";
    }

    public static String plcJobWrongStation(Object stationCode) {
        return "PLC Job does not belong to station " + stationCode;
    }

    public static String unsupportedAssignmentFilter(Object value) {
        return "Unsupported assignment filter: " + value;
    }

    public static String weightCheckRequiresBarcodeAssignment(Object assigned, Object total) {
        return "Weight Check can start after Barcode Assignment is completed (" + assigned + "/" + total + "). Assign all physical cartons first.";
    }

    public static String unsupportedItemStatus(Object value) {
        return "Unsupported item status: " + value;
    }

    public static String weightBelowStationMinimum(Object minimum) {
        return "Weight is below station minimum " + minimum + " kg";
    }

    public static String quantityMismatch(String planned, String actual) {
        return "Quantity Mismatch. Planned: " + planned + " pcs, Actual: " + actual + " pcs. Correct the carton quantity before continuing.";
    }

    public static String buyerInactive(String buyerCode) {
        return "Buyer is inactive: " + buyerCode;
    }

    public static String invalidBuyer(Object buyer) {
        return "Invalid Buyer: " + buyer;
    }

    public static String buyerInactiveOrMissing(String buyerCode) {
        return "Buyer is inactive or does not exist: " + buyerCode;
    }

    public static String buyerWorkflowNotConfigured(String buyerCode) {
        return "No PO/Carton workflow module is configured for Buyer " + buyerCode;
    }

    public static String factoryAccessDenied(Object factoryCode) {
        return "You do not have permission to access factory " + factoryCode;
    }

    public static String cannotImportForBuyer(String buyer, String source, String detail) {
        return cannotImport(buyer + " " + source, detail);
    }

    public static String cannotReadExcel(String detail) {
        return "Cannot read Excel file: " + detail;
    }

    public static String requiredExcelSheet(String sheetName) {
        return "Excel does not contain required sheet: " + sheetName;
    }

    public static String sheetHeaderRequired(String sheetName) {
        return "Sheet " + sheetName + " does not have a header row";
    }

    public static String invalidExcelHeader(String column, String sheetName, String expected, String received) {
        return "Invalid header at column " + column
                + " in sheet " + sheetName
                + ". Expected " + expected
                + ", received " + received;
    }

    public static String invalidExcelDate(String value, String column) {
        return "Invalid date '" + value + "' in column " + column;
    }

    public static String invalidExcelNumber(String value, String column) {
        return "Invalid number '" + value + "' in column " + column;
    }

    public static String invalidPackingListHeader(String column, String received) {
        return "Invalid Packing List header at column " + column
                + ". Received '" + received + "'";
    }

    public static String invalidAllocationHeader(String column, String received) {
        return "Invalid ALLOCATION header at column " + column
                + ". Received '" + received + "'";
    }

    public static String assignedCartonNotFoundForBarcode(String barcode) {
        return "Assigned carton record was not found for Factory Barcode " + barcode;
    }

    public static String plcJobNotFound(Object jobId) {
        return "PLC Job not found: " + jobId;
    }

    public static String noItemWaitingAtStation(Object stationCode) {
        return "No item is waiting at station " + stationCode;
    }

    public static String scaleStationNotFound(Object stationCode) {
        return "Scale station not found: " + stationCode;
    }

    public static String factoryBarcodeDoesNotExist(String barcode) {
        return "Factory Barcode does not exist: " + barcode;
    }

    public static String assignedCartonNoLongerExists(String barcode) {
        return "Assigned carton record no longer exists for Factory Barcode " + barcode;
    }

    public static String buyerDoesNotExist(String buyerCode) {
        return "Buyer does not exist: " + buyerCode;
    }


    public static String wrongSkuLocked(String sku) {
        return "Wrong SKU. This PO is locked to SKU " + (sku == null ? "" : sku);
    }

    public static String acceptedItem(int itemNo, int plannedQty) {
        return "Accepted item " + itemNo + "/" + plannedQty;
    }

    public static String shippingPoMissingExFty(String poNumber, int rowNumber) {
        return "PO " + poNumber + " has no Ex-fty Date at row " + rowNumber;
    }

    public static String shippingPoNotInAllBp(String poNumber) {
        return "PO " + poNumber + " does not exist in All_BP and was skipped.";
    }

    public static String noReleasedFinishedCartonForSku() {
        return "No finished carton is waiting for this SKU";
    }

    public static String skuMatchesMultipleActivePos(String pos) {
        return "SKU matches more than one active PO: " + (pos == null ? "" : pos);
    }

    public static String locatedCartonLabelCandidate(String cartonText) {
        return "Located " + (cartonText == null ? "" : cartonText);
    }

    public static String reweighOpenedAudit(String poNumber, Integer cartonNo, String reason) {
        return "Re-opened weighing for PO " + poNumber + " carton " + cartonNo + ". Reason: " + reason;
    }

    public static String undoLastItemAudit(Integer cartonNo) {
        return "Undid the last accepted product scan for carton " + cartonNo;
    }

    public static String finishCartonAudit(String poNumber, Integer cartonNo, int scanned) {
        return "Finished PO " + poNumber + " carton " + cartonNo + " with " + scanned + " item(s)";
    }

    public static String assignSsccAudit(String sscc, String poNumber, Integer cartonNo) {
        return "Assigned SSCC " + sscc + " to PO " + poNumber + " carton " + cartonNo;
    }

    public static String resetPoSkuAudit(String oldSku, String poNumber, String reason) {
        return "Reset PO SKU from " + (oldSku == null ? "" : oldSku) + " for PO " + poNumber + ". Reason: " + reason;
    }

    public static String reopenCartonAudit(String poNumber, Integer cartonNo, String oldStatus, String reason) {
        return "Re-opened PO " + poNumber + " carton " + cartonNo + " from " + oldStatus + ". Reason: " + reason;
    }

    public static String unassignSsccAudit(String sscc, String poNumber, Integer cartonNo, String reason) {
        return "Unassigned SSCC " + sscc + " from PO " + poNumber + " carton " + cartonNo + ". Reason: " + reason;
    }

    public static String resetCartonWeightAudit(
            String orderName,
            String cartonLabel,
            String barcode,
            Object previousWeight,
            String previousStatus,
            String reason
    ) {
        String carton = cartonLabel == null || cartonLabel.isBlank() ? "N/A" : cartonLabel;
        String factoryBarcode = barcode == null || barcode.isBlank() ? "N/A" : barcode;
        String status = previousStatus == null || previousStatus.isBlank() ? "N/A" : previousStatus;
        return "Order " + orderName
                + ", carton " + carton
                + ", barcode " + factoryBarcode
                + ", previous weight " + previousWeight
                + ", previous status " + status
                + ", reason: " + reason;
    }

    public static String cannotImportAllocation(String detail) {
        return "Cannot import ALLOCATION: " + (detail == null ? "" : detail);
    }

    public static String cannotImportPackingList(String detail) {
        return "Cannot import Packing List: " + (detail == null ? "" : detail);
    }

    public static String orderCreatedAudit(String orderName, String buyerCode) {
        return "Created Order " + orderName + " for " + buyerCode;
    }

    public static String orderUpdatedAudit(String orderName, String buyerCode) {
        return "Updated Order " + orderName + " for " + buyerCode;
    }

    public static String orderDeletedAudit(String buyerCode) {
        return "Deleted Order for " + buyerCode;
    }


    public static String userCreatedAudit(String email) { return "Created user " + email; }
    public static String userUpdatedAudit(String email) { return "Updated user " + email; }
    public static String userDeletedAudit(String email) { return "Deleted user " + email; }
    public static String userPasswordResetAudit(String email) { return "Reset password for " + email; }
    public static String buyerCreatedAudit(String buyerCode) { return "Created Buyer " + buyerCode; }
    public static String buyerUpdatedAudit(String buyerCode) { return "Updated Buyer " + buyerCode; }
    public static String buyerDeletedAudit(String buyerCode) { return "Deleted Buyer " + buyerCode; }
    public static String departmentAuditLabel(String factory, String division, String departmentName) {
        return (factory == null ? "—" : factory) + " / " + division + " / " + departmentName;
    }


}
