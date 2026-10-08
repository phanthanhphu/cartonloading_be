package org.bsl.cartonloading.common.constants;

import java.util.List;

/**
 * Single source of truth for reusable application constants.
 * Keep buyer modules focused on business behavior; do not redefine these values in services/models.
 */
public final class AppConstants {
    private AppConstants() { }

    public static final class Buyer {
        public static final String LULULEMON = "LULULEMON";
        public static final String ENGELBERT_STRAUSS = "ENGELBERT_STRAUSS";
        public static final String ENGELBERT_STRAUSS_SHORT_ALIAS = "ES";
        public static final String ENGELBERT_STRAUSS_COMPACT_ALIAS = "ENGELBERTSTRAUSS";
        public static final List<String> BUILT_IN = List.of(LULULEMON, ENGELBERT_STRAUSS);
        public static final java.util.Map<String, String> DEFAULT_LABELS;
        static {
            java.util.LinkedHashMap<String, String> labels = new java.util.LinkedHashMap<>();
            labels.put(LULULEMON, LULULEMON);
            labels.put(ENGELBERT_STRAUSS, "ENGELBERT STRAUSS");
            DEFAULT_LABELS = java.util.Collections.unmodifiableMap(labels);
        }
        private Buyer() { }
    }

    public static final class Collection {
        public static final String USERS = "users";
        public static final String DEPARTMENTS = "departments";
        public static final String BUYERS = "buyers";
        public static final String AUDIT_LOGS = "audit_logs";
        public static final String ORDERS = "orders";
        public static final String PACKING_ALLOCATION_LINES = "packing_allocation_lines";
        public static final String PACKING_LIST_LINES = "packing_list_lines";
        public static final String CARTON_OPERATIONS = "carton_operations";
        public static final String FACTORY_BARCODES = "factory_barcodes";
        public static final String SCALE_STATIONS = "scale_stations";
        public static final String SHIPMENTS = "shipments";
        public static final String PURCHASE_ORDERS = "purchase_orders";
        public static final String CARTONS = "cartons";
        public static final String CARTON_ITEMS = "carton_items";
        public static final String PRINT_HANDOFFS = "print_handoffs";
        public static final String SCAN_EVENTS = "scan_events";
        public static final String WEIGHING_PROFILES = "weighing_profiles";
        public static final String WEIGHING_ORDERS = "weighing_orders";
        public static final String WEIGHING_RESULTS = "weighing_results";
        public static final String SHIPPING_SCHEDULES = "shipping_schedules";
        public static final String LULULEMON_CARTON_CHECKING = "lululemon_carton_checking_history";
        private Collection() { }
    }

    public static final class Role {
        public static final String ADMIN = "ADMIN";
        public static final String USER = "USER";
        private Role() { }
    }

    public static final class Permission {
        public static final String SALES = "SALES";
        public static final String VIEW_SYSTEM = "VIEW_SYSTEM";
        public static final String ASSIGN_BARCODE = "ASSIGN_BARCODE";
        public static final String WEIGHT_CHECK = "WEIGHT_CHECK";
        public static final String BARCODE_OPERATOR = "BARCODE_OPERATOR";
        public static final String PRINT_ROOM = "PRINT_ROOM";
        private Permission() { }
    }

    public static final class Filter {
        public static final String ALL = "ALL";
        public static final String ASSIGNED = "ASSIGNED";
        public static final String UNASSIGNED = "UNASSIGNED";
        public static final String UNPLANNED = "UNPLANNED";
        private Filter() { }
    }

    public static final class ProgressStatus {
        public static final String NOT_STARTED = "NOT_STARTED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String COMPLETED = "COMPLETED";
        public static final String PLANNED = "PLANNED";
        public static final String WAITING_WEIGHT = "WAITING_WEIGHT";
        public static final String PLAN_MISMATCH = "PLAN_MISMATCH";
        private ProgressStatus() { }
    }

    public static final class CheckingResult {
        public static final String PASS = "PASS";
        public static final String FAIL = "FAIL";
        private CheckingResult() { }
    }

    public static final class RequestDefault {
        public static final String PAGE = "0";
        public static final String PAGE_SIZE = "25";
        public static final String LARGE_PAGE_SIZE = "50";
        public static final String TRUE = "true";
        public static final String CREATE_ONLY = "CREATE_ONLY";
        private RequestDefault() { }
    }

    public static final class Validation {
        public static final String FACTORY_CODE_REGEX = "(?i)F[1-7]";
        private Validation() { }
    }

    public static final class MimeType {
        public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        private MimeType() { }
    }


    public static final class SecurityExpression {
        public static final String BARCODE_BUYER_ACCESS = "@accessControl.canAccessBuyer('" + Buyer.ENGELBERT_STRAUSS + "')";
        public static final String BARCODE_BUYER_MANAGE_BARCODES = BARCODE_BUYER_ACCESS + " and (@accessControl.canManageBarcodes())";
        public static final String BARCODE_BUYER_ADMIN = BARCODE_BUYER_ACCESS + " and (@accessControl.isAdmin())";
        public static final String BARCODE_BUYER_SALES_OR_WEIGHT = BARCODE_BUYER_ACCESS + " and (@accessControl.canManageSales() or @accessControl.canWeightCheck())";
        private SecurityExpression() { }
    }

    public static final class WorkflowStage {
        public static final String SALES_MASTER = "1. Sales - Master Data";
        public static final String PACKING_ASSIGN = "2. Packing - Assign Barcode for Carton";
        public static final String SALES_PLAN = "3. Sales - Shipment Planning";
        public static final String PACKING_WEIGHT = "4. Packing - Weight Check and Dispatch";
        private WorkflowStage() { }
    }

    public static final class PurchaseOrderStatus {
        public static final String NOT_STARTED = "NOT_STARTED";
        public static final String PACKING = "PACKING";
        public static final String WAITING_EX_FTY = "WAITING_EX_FTY";
        public static final String WAITING_LABEL = "WAITING_LABEL";
        public static final String WAITING_SSCC = "WAITING_SSCC";
        public static final String READY_TO_SHIP = "READY_TO_SHIP";
        private PurchaseOrderStatus() { }
    }

    public static final class CartonStatus {
        public static final String READY_TO_PACK = "READY_TO_PACK";
        public static final String PACKING = "PACKING";
        public static final String FINISHED = "FINISHED";
        public static final String LABEL_CONFIRMED = "LABEL_CONFIRMED";
        public static final String READY_TO_SHIP = "READY_TO_SHIP";
        private CartonStatus() { }
    }

    public static final class ItemStatus {
        public static final String PENDING = "PENDING";
        public static final String IDENTIFIED = "IDENTIFIED";
        public static final String PASS = "PASS";
        private ItemStatus() { }
    }

    /** RFID verification is intentionally separate from the logical item status. */
    public static final class RfidStatus {
        public static final String WAITING_RFID = "WAITING_RFID";
        public static final String VERIFIED = "VERIFIED";
        public static final String FAILED = "FAILED";
        private RfidStatus() { }
    }

    public static final class PrintRequestStatus {
        public static final String SENT = "SENT";
        public static final String CANCELLED = "CANCELLED";
        public static final String PENDING = "PENDING";
        public static final String PRINTING = "PRINTING";
        public static final String PRINTED_SENT_TO_PACKING = "PRINTED_SENT_TO_PACKING";
        private PrintRequestStatus() { }
    }

    public static final class WeightStatus {
        public static final String PASS = "PASS";
        public static final String FAILED = "FAILED";
        private WeightStatus() { }
    }

    public static final class ScaleWeightStatus {
        public static final String UNDER = "UNDER";
        public static final String OVER = "OVER";
        private ScaleWeightStatus() { }
    }

    public static final class WeighingOrderStatus {
        public static final String WAITING_FOR_WEIGHING = "WAITING_FOR_WEIGHING";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String FAILED = "FAILED";
        public static final String COMPLETED = "COMPLETED";
        private WeighingOrderStatus() { }
    }

    public static final class WeighingSessionStatus {
        public static final String STOPPED = "STOPPED";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        private WeighingSessionStatus() { }
    }

    public static final class ScanResult {
        public static final String PASS = "PASS";
        public static final String REJECT = "REJECT";
        private ScanResult() { }
    }

    public static final class WeightAction {
        public static final String WEIGH = "WEIGH";
        public static final String REOPEN = "REOPEN";
        public static final String OVERRIDE = "OVERRIDE";
        private WeightAction() { }
    }

    public static final class WeightSource {
        public static final String PLC = "PLC";
        public static final String SCALE = "SCALE";
        public static final String MANUAL = "MANUAL";
        public static final java.util.Set<String> ALLOWED = java.util.Set.of(PLC, SCALE, MANUAL);
        private WeightSource() { }
    }


    /**
     * Public API paths. Values intentionally preserve the existing contract even when
     * the implementation classes use domain-only names.
     */
    public static final class ApiPath {
        public static final String SSCC_WORKFLOW_ORDER = "/api/buyers/{buyer}/lululemon/orders/{orderId}";
        public static final String SSCC_WORKFLOW_PRINT_REQUESTS = "/api/buyers/{buyer}/lululemon/print-requests";
        public static final String SSCC_WORKFLOW_WEIGHING = "/api/buyers/{buyer}/lululemon/orders/{orderId}/weighing";
        public static final String BARCODE_WORKFLOW_ORDERS = "/api/buyers/{buyer}/orders";
        public static final String BARCODE_WORKFLOW_PACKING_ORDERS = "/api/buyers/{buyer}/packing-orders";
        public static final String BARCODE_WORKFLOW_SALES_SHIPMENTS = "/api/buyers/{buyer}/sales/shipments";
        public static final String BARCODE_WORKFLOW_PACKING_ASSIGNMENT = "/api/buyers/{buyer}/packing/orders/{orderId}/barcode-assignment";
        public static final String BARCODE_WORKFLOW_SCALE_STATIONS = "/api/buyers/engelbert-strauss/scale-stations";
        public static final String BARCODE_WORKFLOW_SHIPMENT_MANAGEMENT = "/api/buyers/{buyer}/shipment-management";
        public static final String CARTON_LOADING = "/api/carton-loading";
        public static final String BARCODE_WORKFLOW_PACKING_SHIPMENTS = "/api/buyers/{buyer}/packing/shipments";
        public static final String BARCODE_WORKFLOW_FACTORY_BARCODES = "/api/buyers/engelbert-strauss/factory-barcodes";
        private ApiPath() { }
    }

    public static final class AuditAction {
        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String DELETE = "DELETE";
        public static final String RESET_PASSWORD = "RESET_PASSWORD";
        public static final String GENERATE_RESET_PASSWORD = "GENERATE_RESET_PASSWORD";
        public static final String ALL_BP_IMPORT = "LULULEMON_ALL_BP_IMPORT";
        public static final String PO_UPDATE = "LULULEMON_PO_UPDATE";
        public static final String PO_DELETE = "LULULEMON_PO_DELETE";
        public static final String UNDO_ITEM = "LULULEMON_UNDO_ITEM";
        public static final String FINISH_CARTON = "LULULEMON_FINISH_CARTON";
        public static final String SHIPPING_IMPORT = "LULULEMON_SHIPPING_IMPORT";
        public static final String SHIPPING_SCHEDULE_CREATE = "LULULEMON_SHIPPING_SCHEDULE_CREATE";
        public static final String CHECKING_PASS = "LULULEMON_CARTON_QC_PASS";
        public static final String CHECKING_FAIL = "LULULEMON_CARTON_QC_FAIL";
        public static final String SHIPPING_SCHEDULE_REMOVE = "LULULEMON_SHIPPING_SCHEDULE_REMOVE";
        public static final String SHIPPING_SCHEDULE_CHECK = "LULULEMON_SHIPPING_SCHEDULE_CHECK";
        public static final String SHIPPING_SCHEDULE_SEND_CW = "LULULEMON_SHIPPING_SCHEDULE_SEND_CW";
        public static final String SHIPPING_SCHEDULE_COMPLETE = "LULULEMON_SHIPPING_SCHEDULE_COMPLETE";
        public static final String CONFIRM_CARTON_LABEL = "LULULEMON_CONFIRM_CARTON_LABEL";
        public static final String CONFIRM_CARTON_QTY = "LULULEMON_CONFIRM_CARTON_QTY";
        public static final String ASSIGN_SSCC = "LULULEMON_ASSIGN_SSCC";
        public static final String RESET_PO_SKU = "LULULEMON_RESET_PO_SKU";
        public static final String REOPEN_CARTON = "LULULEMON_REOPEN_CARTON";
        public static final String UNASSIGN_SSCC = "LULULEMON_UNASSIGN_SSCC";
        public static final String WEIGHT_PROFILE_SAVE = "LULULEMON_WEIGHT_PROFILE_SAVE";
        public static final String WEIGHING_ORDER_CREATE = "LULULEMON_WEIGHING_ORDER_CREATE";
        public static final String WEIGHT_CAPTURE = "LULULEMON_WEIGHT_CAPTURE";
        public static final String REWEIGH_OPEN = "LULULEMON_REWEIGH_OPEN";
        public static final String WEIGHT_OVERRIDE = "LULULEMON_WEIGHT_OVERRIDE";
        public static final String WEIGHING_SESSION_START = "LULULEMON_WEIGHING_SESSION_START";
        public static final String WEIGHING_SESSION_STOP = "LULULEMON_WEIGHING_SESSION_STOP";
        public static final String WEIGHING_SESSION_COMPLETE = "LULULEMON_WEIGHING_SESSION_COMPLETE";
        public static final String SEND_PO_HANDOFF = "SEND_PO_HANDOFF";
        public static final String CANCEL_PO_HANDOFF = "CANCEL_PO_HANDOFF";
        public static final String DELETE_PO_HANDOFF = "DELETE_PO_HANDOFF";
        public static final String UPDATE_PO_HANDOFF_STATUS = "UPDATE_PO_HANDOFF_STATUS";
        public static final String MANUAL_INSPECTION = "MANUAL_INSPECTION";
        public static final String CARTON_COMPLETED = "CARTON_COMPLETED";
        public static final String SHIPMENT_PLANNED = "SHIPMENT_PLANNED";
        public static final String SHIPMENT_SHIPPED = "SHIPMENT_SHIPPED";
        public static final String SHIPMENT_CANCELLED = "SHIPMENT_CANCELLED";
        public static final String RESET_CARTON_WEIGHT = "RESET_CARTON_WEIGHT";
        private AuditAction() { }
    }

    public static final class AuditResource {
        public static final String USER = "USER";
        public static final String BUYER = "BUYER";
        public static final String DEPARTMENT = "DEPARTMENT";
        public static final String ORDER = "ORDER";
        public static final String CARTON_LOADING = "LULULEMON_CARTON_LOADING";
        public static final String WEIGHING = "LULULEMON_WEIGHING";
        public static final String SHIPPING_SCHEDULE = "LULULEMON_SHIPPING_SCHEDULE";
        public static final String PO_HANDOFF = "LULULEMON_PO_HANDOFF";
        public static final String SHIPMENT_CARTON = "SHIPMENT_CARTON";
        public static final String CARTON = "CARTON";
        private AuditResource() { }
    }

    public static final class SocketEvent {
        public static final String ALL_BP_IMPORTED = "LULULEMON_ALL_BP_IMPORTED";
        public static final String PO_UPDATED = "LULULEMON_PO_UPDATED";
        public static final String PO_DELETED = "LULULEMON_PO_DELETED";
        public static final String ITEM_SCANNED = "LULULEMON_ITEM_SCANNED";
        public static final String ITEM_UNDONE = "LULULEMON_ITEM_UNDONE";
        public static final String CARTON_FINISHED = "LULULEMON_CARTON_FINISHED";
        public static final String EX_FTY_UPDATED = "LULULEMON_EX_FTY_UPDATED";
        public static final String LABEL_CONFIRMED = "LULULEMON_LABEL_CONFIRMED";
        public static final String CARTON_QTY_CONFIRMED = "LULULEMON_CARTON_QTY_CONFIRMED";
        public static final String SSCC_ASSIGNED = "LULULEMON_SSCC_ASSIGNED";
        public static final String PO_SKU_RESET = "LULULEMON_PO_SKU_RESET";
        public static final String CARTON_REOPENED = "LULULEMON_CARTON_REOPENED";
        public static final String SSCC_UNASSIGNED = "LULULEMON_SSCC_UNASSIGNED";
        public static final String WEIGHING_ORDER_CREATED = "LULULEMON_WEIGHING_ORDER_CREATED";
        public static final String WEIGHT_RESULT_PREFIX = "LULULEMON_WEIGHT_";
        public static final String WEIGHT_REOPENED = "LULULEMON_WEIGHT_REOPENED";
        public static final String WEIGHT_OVERRIDDEN = "LULULEMON_WEIGHT_OVERRIDDEN";
        public static final String WEIGHING_SESSION_STARTED = "LULULEMON_WEIGHING_SESSION_STARTED";
        public static final String WEIGHING_SESSION_STOPPED = "LULULEMON_WEIGHING_SESSION_STOPPED";
        public static final String WEIGHING_SESSION_COMPLETED = "LULULEMON_WEIGHING_SESSION_COMPLETED";
        public static final String PO_HANDOFF_SENT = "PO_HANDOFF_SENT";
        public static final String PO_HANDOFF_CANCELLED = "PO_HANDOFF_CANCELLED";
        public static final String PO_HANDOFF_DELETED = "PO_HANDOFF_DELETED";
        public static final String PO_HANDOFF_STATUS_CHANGED = "PO_HANDOFF_STATUS_CHANGED";
        public static final String PLAN_GENERATED = "PLAN_GENERATED";
        public static final String FACTORY_BARCODE_ASSIGNED = "FACTORY_BARCODE_ASSIGNED";
        public static final String FACTORY_BARCODE_UNASSIGNED = "FACTORY_BARCODE_UNASSIGNED";
        public static final String WEIGHT_RESET = "WEIGHT_RESET";
        public static final String WAITING_WEIGHT = "WAITING_WEIGHT";
        private SocketEvent() { }
    }

    public static final class PackingImport {
        public static final String PACKING_LIST_DATASET = "ENGELBERT_STRAUSS_PACKING_LIST";
        public static final int PACKING_LIST_FIRST_DATA_ROW = 11;
        public static final int PACKING_LIST_LAST_SIZE_COLUMN = 36;
        public static final String ALLOCATION_DATASET = "PACKING_ALLOCATION";
        public static final int ALLOCATION_BUSINESS_COLUMN_COUNT = 29;
        private PackingImport() { }
    }

    public static final class FactoryBarcode {
        public static final int RUNNING_LENGTH = 9;
        public static final long MAX_RUNNING = 999_999_999L;
        private FactoryBarcode() { }
    }

    public static final class Scale {
        public static final java.math.BigDecimal DEFAULT_MINIMUM_WEIGHT = new java.math.BigDecimal("0.50");
        public static final java.math.BigDecimal DEFAULT_TOLERANCE = new java.math.BigDecimal("0.02");
        private Scale() { }
    }

    public static final class Csv {
        public static final String SHIPMENT_REPORT_HEADER = "\uFEFFBarcode,Order,PO,Product,Style,Color,Size,Planned quantity,Assigned quantity,Production line,Lifecycle,Weight kg,Weight result,Manual result,Inspection note,Inspected by,Completed by,Shipment,Shipped at\r\n";
        private Csv() { }
    }

    public static final class SearchText {
        public static final String YES_COMPLETED = "YES COMPLETED";
        public static final String NO_NOT_COMPLETED = "NO NOT COMPLETED";
        public static final String ASSIGN_PREFIX = "ASSIGN ";
        public static final String WEIGHT_PREFIX = "WEIGHT ";
        public static final String PASS_TOKEN = " PASS ";
        public static final String FAIL_TOKEN = " FAIL ";
        private SearchText() { }
    }

    public static final class ExcelExport {
        public static final List<String> MASTER_HEADERS = List.of(
                "ACTION", "KEY", "Supplier name", "e.s. Supplier #", "Production facility", "Container #", "Mode of shipment",
                "ETD", "ETA", "e.s. PO #", "e.s. Article #", "STYLE#", "STYLE", "Color", "Size",
                "Qty Per Ctn", "Invoice #", "Total pcs", "Total ctns", "Pcs AIR", "Ctns AIR", "Pcs SEA",
                "Ctns SEA", "CBM AIR", "KG AIR", "STATUS", "Open PO QTY / Overdel", "Remarks", "YO Lot#",
                "H CTN", "CBM CTN"
        );
        public static final List<String> PACKING_HEADERS = List.of(
                "C/T From", "C/T To", "CTNS Qty", "P.O. #", "Style #", "Style", "Art.no.", "Color", "Size",
                "Qty/CTN", "Total PCS", "Ctn Meas", "CBM", "Gross Weight (kg)", "Net Weight (kg)",
                "Actual Weight (kg)", "Remarks"
        );
        private ExcelExport() { }
    }

    public static final class FieldLabel {
        public static final String WEIGHT_PROFILE = "Weight profile";
        public static final String TOLERANCE = "Tolerance";
        public static final String UNIT_WEIGHT = "Unit Weight";
        public static final String TARE_WEIGHT = "Tare Weight";
        public static final String WEIGHT_DATA = "Weight data";
        public static final String STATION_DEVICE_CODE = "Station / Device code";
        public static final String FACTORY = "Factory";
        public static final String TOTAL_QTY = "Total Qty";
        public static final String PCS_PER_CARTON = "Pcs / Ctn";
        public static final String NET_WEIGHT = "N.W.";
        public static final String GROSS_WEIGHT = "G.W.";
        private FieldLabel() { }
    }

    public static final class ImportSource {
        public static final String ALL_BP = "All_BP";
        public static final String SHIPPING_LIST = "Shipping List";
        private ImportSource() { }
    }

    public static final class ExcelAction {
        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String DELETE = "DELETE";
        public static final String LEGACY = "LEGACY";
        private ExcelAction() { }
    }

    public static final class SystemActor {
        public static final String PLC_GATEWAY = "PLC_GATEWAY";
        private SystemActor() { }
    }

    public static final class ScanType {
        public static final String ITEM = "ITEM";
        public static final String CARTON_LABEL_SKU = "CARTON_LABEL_SKU";
        public static final String SSCC = "SSCC";
        private ScanType() { }
    }

    public static final class AllBpKey {
        public static final String PO = "PO";
        public static final String STYLE = "STYLE";
        public static final String QTY = "QTY";
        public static final String PCS_CTN = "PCS_CTN";
        public static final String DC_CODE = "DC_CODE";
        public static final String DESTINATION = "DESTINATION";
        public static final String CHANNEL = "CHANNEL";
        public static final String MASTER_PO = "MASTER_PO";
        public static final String PACKING_PLAN = "PACKING_PLAN";
        public static final String SALES_ORDER_PTS = "SALES_ORDER_PTS";
        public static final String DESCRIPTION = "DESCRIPTION";
        public static final String COLOR = "COLOR";
        public static final String SIZE = "SIZE";
        public static final String SHIP_MODE = "SHIP_MODE";
        public static final String SEASON = "SEASON";
        public static final String FWD = "FWD";
        public static final String CARTON_BOX_SIZE = "CARTON_BOX_SIZE";
        private AllBpKey() { }
    }

    public static final class ExportSubject {
        public static final String ORDER_ITEMS = "Order Items";
        public static final String PACKING_LIST = "Packing List";
        private ExportSubject() { }
    }

    public static final class Excel {
        public static final String ALL_BP_SHEET = "All_BP";
        public static final String ALLOCATION_SHEET = "ALLOCATION";
        public static final String PACKING_LIST_SHEET = "PACKING LIST";
        private Excel() { }
    }
    public static final class Importing {
        public static final long MAX_EXCEL_SIZE_BYTES = 20L * 1024L * 1024L;
        public static final String EXCEL_EXTENSION_XLSX = ".xlsx";
        public static final String EXCEL_EXTENSION_XLS = ".xls";
        public static final List<String> DATE_PATTERNS = List.of(
                "dd/MM/uuuu",
                "d/M/uuuu",
                "dd-MM-uuuu",
                "d-M-uuuu",
                "MM/dd/uuuu",
                "M/d/uuuu"
        );
        private Importing() { }
    }

    public static final class Security {
        public static final String TEMP_PASSWORD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        public static final String TEMP_PASSWORD_LOWER = "abcdefghijkmnopqrstuvwxyz";
        public static final String TEMP_PASSWORD_DIGITS = "23456789";
        public static final String TEMP_PASSWORD_ALPHABET =
                TEMP_PASSWORD_UPPER + TEMP_PASSWORD_LOWER + TEMP_PASSWORD_DIGITS;
        private Security() { }
    }

    public static final class OpenApi {
        public static final String SECURITY_SCHEME_NAME = "bearerAuth";
        public static final String TITLE = "Carton Loading API";
        public static final String VERSION = "1.0";
        public static final String DESCRIPTION = "Carton Loading API with JWT Bearer Authentication";
        public static final String BEARER_SCHEME = "bearer";
        public static final String BEARER_FORMAT = "JWT";
        private OpenApi() { }
    }

    public static final class AllBp {
        public static final java.util.Map<String, java.util.Set<String>> ALIASES;
        public static final java.util.Set<String> FORMULA_KEYS = java.util.Set.of(
                "ODD_RATIO", "CTNS", "REMAINDER", "FOB_AMOUNT",
                "TOTAL_FOB_PRICE_TAG", "FOB_FTY_PRICE", "FOB_FTY_AMOUNT"
        );
        public static final java.util.Set<String> NUMERIC_SOURCE_KEYS = java.util.Set.of(
                "QTY", "PCS_CTN", "ODD_RATIO", "CTNS", "REMAINDER",
                "FOB_PRICE", "CARE_CONTENT_LABEL", "CHINA_INSPECTION_TAG", "FOB_AMOUNT",
                "TOTAL_FOB_PRICE_TAG", "FOB_FTY_PRICE", "FOB_FTY_AMOUNT"
        );

        static {
            java.util.Map<String, java.util.Set<String>> aliases = new java.util.LinkedHashMap<>();
            putAliases(aliases, "HOD", "HOD", "HANDOVERDATE", "HODHANDOVERDATE");
            putAliases(aliases, "SGS_TESTING", "SGSTESTING", "SGSTEST");
            putAliases(aliases, "GB_TESTING", "GBTESTING", "GBTEST");
            putAliases(aliases, "BV_INSPECTION", "BVINSPECTION", "BVINSPECT");
            putAliases(aliases, "DC_CODE", "DCCODE", "DC");
            putAliases(aliases, "DESTINATION", "DESTINATION", "DEST");
            putAliases(aliases, "CHANNEL", "CHANEL", "CHANNEL");
            putAliases(aliases, "MASTER_PO", "MASTERPO");
            putAliases(aliases, "PO", "PO", "PONO", "PONUMBER", "PURCHASEORDER");
            putAliases(aliases, "PACKING_PLAN", "PACKINGPLAN", "PACKINGPLANNO");
            putAliases(aliases, "SALES_ORDER_PTS", "SOPTS", "SO", "SALESORDERPTS", "SALESORDER");
            putAliases(aliases, "STYLE", "STYLE", "STYLENO", "STYLENUMBER");
            putAliases(aliases, "DESCRIPTION", "DESCRIPTION", "DESC");
            putAliases(aliases, "COLOR", "COLORDESCRIPTION", "COLOR", "COLOURDESCRIPTION", "COLOUR");
            putAliases(aliases, "SIZE", "SIZE", "SIZES", "GARMENTSIZE", "APPARELSIZE", "PRODUCTSIZE");
            putAliases(aliases, "QTY", "QTY", "QTYTOTAL", "TOTALQTY", "QUANTITY", "ORDERQTY");
            putAliases(aliases, "PCS_CTN", "PCSCTN", "PCSPERCTN", "PCSPERCARTON", "QTYPERCTN", "PCSCTNS", "UNITQTY");
            putAliases(aliases, "ODD_RATIO", "LE");
            putAliases(aliases, "CTNS", "CTNS", "CARTONS", "CARTONQTY", "TOTALCARTONS");
            putAliases(aliases, "REMAINDER", "DU", "REMAINDER", "REMAINDERQTY", "BALANCEQTY", "ODDPCS");
            putAliases(aliases, "REMARK", "REMARK");
            putAliases(aliases, "FOB_PRICE", "FOBPRICE");
            putAliases(aliases, "CARE_CONTENT_LABEL", "CARECONTENTLABEL", "CAREANDCONTENTLABEL");
            putAliases(aliases, "CHINA_INSPECTION_TAG", "CHINAINSPECTIONTAG");
            putAliases(aliases, "FOB_AMOUNT", "FOBAMOUNT");
            putAliases(aliases, "TOTAL_FOB_PRICE_TAG", "TOTALFOBPRCIETAG", "TOTALFOBPRICETAG");
            putAliases(aliases, "FOB_FTY_PRICE", "FOBFTYPRICE", "FOBFACTORYPRICE");
            putAliases(aliases, "FOB_FTY_AMOUNT", "FOBFTYAMOUNT", "FOBFACTORYAMOUNT");
            putAliases(aliases, "SHIP_MODE", "SHIPMODE", "SHIPPINGMODE");
            putAliases(aliases, "SEASON", "SEASON");
            putAliases(aliases, "DP_PERCENT", "DP", "DPPERCENT");
            putAliases(aliases, "MPR_NUMBER", "MPRNUMBER", "MPRNO", "MPR");
            putAliases(aliases, "FWD", "FWD", "FORWARDER");
            putAliases(aliases, "CARTON_BOX_SIZE", "CARTONBOXSIZE", "CARTONSIZE", "BOXSIZE");
            putAliases(aliases, "NW", "NW", "NETWEIGHT", "NETWEIGHTKG");
            putAliases(aliases, "GW", "GW", "GROSSWEIGHT", "GROSSWEIGHTKG");
            putAliases(aliases, "REMARK_2", "REMARK2", "REMARK02");
            putAliases(aliases, "FACTORY", "FACTORY", "FACTORYCODE", "PACKINGFACTORY", "PRODUCTIONFACILITY", "FTY");
            ALIASES = java.util.Collections.unmodifiableMap(aliases);
        }

        private static void putAliases(
                java.util.Map<String, java.util.Set<String>> target,
                String canonical,
                String... aliases
        ) {
            target.put(canonical, java.util.Collections.unmodifiableSet(
                    new java.util.LinkedHashSet<>(java.util.List.of(aliases))
            ));
        }

        private AllBp() { }
    }

}
