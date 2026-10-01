# Carton Loading System — FE / BE Integration Contract

> Tài liệu dành cho **Frontend Team** tích hợp với backend `cartonloading_be`.
>
> **Người giữ SLA contract**: Backend owner. Mọi thay đổi contract (response field, error format, event name) → update file này + báo FE trước.

---

## 0. Quick Start (FE làm đầu tiên trong 30 phút)

### 0.1 Chạy backend local
```bash
# Yêu cầu: JDK 17 + MongoDB (port 27017) hoặc Docker compose mongo
cd cartonloading_be
./gradlew bootRun           # Windows: gradlew.bat bootRun
# BE chạy ở: http://localhost:8083
```

### 0.2 Mở 3 tools thiết yếu
| Tool | URL | Mục đích |
|---|---|---|
| **Swagger UI (OpenAPI)** | http://localhost:8083/swagger-ui/index.html | Xem tất cả endpoint, test trực tiếp, copy payload mẫu |
| **OpenAPI JSON raw** | http://localhost:8083/v3/api-docs | Dùng để generate TypeScript SDK (xem mục 1.2) |
| **Postman Collection mẫu** | `docs/BSL_Carton_Loading_API.postman_collection.json` | Import vào Postman để chạy flow nhanh (có sample body) |

### 0.3 Tạo tài khoản admin (lần đầu)
Gọi `POST /api/auth/register` hoặc dùng seeding script ở mục 9.

---

## 1. Ngôn ngữ chung: API Contract (không FE đoán)

Toàn bộ BE tự sinh OpenAPI 3.0 spec bằng **springdoc-openapi v2**. FE **không được tự define interface** bằng tay → dùng công cụ auto-generate.

### 1.1 Endpoint / Method / Param lấy từ đâu?
→ **Chỉ lấy từ** [Swagger UI](http://localhost:8083/swagger-ui/index.html).
- Mỗi `@Operation` có summary, description, example request/response.
- Các DTO đã gắn `@Schema(description=...)` cho từng field → hiểu đúng ý nghĩa (vd: `qtyPerCtn` là số PC mỗi thùng, không phải tổng số thùng).

### 1.2 Tự sinh TypeScript SDK (đảm bảo không sai kiểu)
Dùng **openapi-typescript-codegen** hoặc **Orval**:
```bash
# Option 1: openapi-typescript-codegen (nhẹ)
npm install -D openapi-typescript-codegen
openapi --input http://localhost:8083/v3/api-docs --output ./src/api/generated --client axios

# Option 2: Orval (tốt hơn, có React Query hook)
npm install -D orval
# file orval.config.js → đầu vào = /v3/api-docs, đầu ra = ./src/api
```
→ Tự có:
```ts
// Ví dụ output tự sinh - FE không viết phần này
export type CartonScanStatus = 'PLANNED' | 'ASSIGNED' | 'WAITING_WEIGHT' | 'CHECKED' | 'COMPLETED' | 'WEIGHT_WARNING' | 'SHIPPED' | 'CANCELLED';
export interface CartonScanTransaction { id: string; factoryBarcode: string | null; status: CartonScanStatus; ... }
export class CartonLoadingService {
  static generateCartons = (buyer: string, orderId: string) => AxiosPromise<PackingOrderResponse>;
  static assignFactoryBarcode = (buyer, orderId, body: AssignBarcodeRequest) => ...
}
```
→ Đảm bảo **sai kiểu dữ liệu / sai tên field** không xảy ra.

---

## 2. Authentication (Flow chuẩn)

### 2.1 Login Flow (2 bước)
```
FE                                               BE
│                                                │
│  1. POST /api/auth/login                       │
│     { email, password }                        │
│                                                │
│  2a. 200 OK ← { accessToken,                   │
│                 tokenType: "Bearer",           │
│                 expiresIn: 86400,              │
│                 user: User,                    │
│                 buyers: Buyer[],               │
│                 selectedBuyerId?: string       │
│               }                                │
│                                                │
│  2b. 401 / 403 ← ApiError (xem mục 3)         │
│                                                │
│  3. Lưu accessToken vào localStorage           │
│     (FE tự handle expiry - hết 24h → out)      │
│                                                │
│  4. Nếu selectedBuyerId null → FE mở dialog    │
│     "Chọn Buyer làm việc" → gọi:              │
│     POST /api/auth/select-buyer/{buyerId}     │
│     ← trả về JWT mới (đã nhúng buyer quyền)   │
```

### 2.2 Tất cả request sau login → thêm header
```
Authorization: Bearer <accessToken>
X-Buyer-Id: <buyerId hiện tại FE đang chọn>
```
Nếu không chọn buyer → `403 FORBIDDEN` (trừ các endpoint global như `/api/auth/me`).

### 2.3 Token hết hạn / invalid
→ BE trả về **401 UNAUTHORIZED**, FE bắt global interceptor:
```ts
axios.interceptors.response.use(
  r => r,
  err => {
    if (err.response?.status === 401) {
      localStorage.clear();
      router.push('/login');
    }
    // Lỗi khác → chuyển qua error handler chuẩn (mục 3)
    return Promise.reject(err);
  }
);
```

### 2.4 FE đọc quyền user từ token (JWT payload, decode base64)
Decode `accessToken.split('.')[1]` lấy các trường (không cần gọi API):
```ts
interface JwtPayload {
  sub: string;              // email
  role: 'ADMIN' | 'USER';
  tokenVersion: number;
  // Các quyền module → dùng để hide/show nút / chặn route
  accessPermissions: ('PERM_BOM' | 'PERM_SALES' | 'ASSIGN_BARCODE' | 'PERM_SEWING' | 'WEIGHT_CHECK' | 'VIEW_SYSTEM')[];
  buyerPermissions: string[]; // buyer IDs user được xem
  iat: number; exp: number;
}
// Ví dụ: if (!perms.includes('ASSIGN_BARCODE')) => ẩn tab Barcode Assignment
```
→ Đảm bảo FE / BE đồng bộ: user không có quyền → FE ẩn nút + BE chặn thật (`@PreAuthorize`).

---

## 3. Error Response Format (DUY NHẤT CHO TẤT CẢ ENDPOINT)

> ⚠️ QUYỀN LỢI LỚN: Toàn bộ lỗi (validation / auth / business / 500) đều trả về **CÙNG 1 JSON DẠNG**:

```json
{
  "status": 400,
  "message": "Factory barcode FB-20250905-000001 has been already assigned",
  "timestamp": "2026-09-06T10:21:30",
  "errors": null,
  "fieldErrors": null,
  "errorCode": "FACTORY_BARCODE_ALREADY_ASSIGNED"
}
```

| Field | Luôn có? | Dùng FE làm gì? |
|---|---|---|
| `status` | ✅ | Mã HTTP (xanh/đỏ toast tương ứng) |
| `message` | ✅ | Hiển thị cho người đọc (VI/EN, BE giữ ngôn ngữ) |
| `timestamp` | ✅ | Để debug, không cần hiển thị user |
| `errors` | Optional (thường null) | Danh sách lỗi chung (vd: `["Duplicate supplierCode"]`) |
| `fieldErrors` | Optional (validation lỗi) | **Map field → message** để gắn vào Ant Design Form rules: `{poNumber: "PO is required", article: "Max 50 chars"}` |
| `errorCode` | ✅ | **Quan trọng nhất**: mã lỗi bất biến → FE switch-case xử lý đặc thù, không parse `message` |

### 3.1 Danh sách `errorCode` FE nên handle riêng
| errorCode | HTTP | FE xử lý |
|---|---|---|
| `FACTORY_BARCODE_ALREADY_ASSIGNED` | 409 | Toast đỏ + gợi ý scan barcode khác |
| `CARTON_STATION_BUSY` | 409 | Thông báo "Trạm cân đang có job chờ, hoàn thành trước" |
| `INVALID_FACTORY_BARCODE` | 404 | Barcode không tồn tại / không đúng factory code năm |
| `ORDER_PHYSICAL_PLAN_LOCKED` | 400 | "Đã có operational data, không sửa số thùng nữa. Hãy reset các thùng liên quan." |
| `PERMISSION_DENIED` | 403 | Ẩn action tương ứng + toast thông báo |
| `VALIDATION_FAILED` | 400 | Sử dụng `fieldErrors` map đỏ dưới input |
| `BUYER_NOT_SELECTED` | 403 | Mở dialog chọn Buyer |

### 3.2 Exceptions cần thống nhất (BE sẽ fix trong ticket chuẩn hóa):
- [x] Hợp nhất **2 class `ApiError` khác namespace** (`error.ApiError` và `dto.ApiError`) thành 1 class duy nhất có `fieldErrors + errors + errorCode`
- [x] `AuthController` trả về `Map.of("message")` → đổi thành ApiError chuẩn
- [x] Thêm `@Schema` vào ApiError → OpenAPI spec hiển thị rõ format lỗi cho FE

---

## 4. Pagination + Sort + Filter (Chuẩn toàn cục)

Tất cả danh sách (Order, Carton, FactoryBarcode, Report...) đều dùng pattern **Server-Side Paging** như sau:

### 4.1 Query params FE gửi
```
GET /api/...?page=0&size=20&sort=createdAt,desc&keyword=PO-12345

page    = số thứ tự trang (BẮT ĐẦU TỪ 0) - FE nhớ trừ 1 khi user đổi trang
size    = số dòng / trang (mặc định 20, tối đa 500 - cho báo cáo xuất Excel)
sort    = field,direction (vd: `orderDate,asc`) - chấp nhận nhiều sort bằng nhiều param
keyword = search tổng hợp (tùy endpoint mô tả trong Swagger)
```
→ Filter theo field cụ thể → xem Swagger của endpoint đó (vd: `status=COMPLETED&article=ART001`).

### 4.2 Response BE trả về
```json
{
  "content": [ ... ],              // array items
  "totalElements": 153,            // tổng dòng KHÔNG phân trang
  "totalPages": 8,                 // tổng số trang
  "size": 20,
  "number": 0,                     // current page (0-based)
  "first": true,
  "last": false,
  "numberOfElements": 20
}
```
→ FE dùng `totalElements` cho Ant Design Table `pagination={{ total: resp.totalElements }}`.

### 4.3 Ngoại lệ: Endpoints xài `X-Total-Count` header
Một số cũ (vd: list carton assignment) vẫn dùng header `X-Total-Count: 1234`.
→ FE interceptor đọc:
```ts
const total = Number(response.headers['x-total-count'] || response.data?.totalElements || 0);
```

---

## 5. Workflow API theo từng Module (Flow FE goi theo trình tự)

### 5.1 Công đoạn 1: Barcode Management
```
(1) POST /api/factory-barcodes/generate
        body: { year: 2026, factoryCode: "001", quantity: 500 }
        ← { batchId: "FB-20260906-000123", totalGenerated: 500 }

(2) GET  /api/factory-barcodes?batchId=FB-20260906-000123&status=AVAILABLE
        ← Page<FactoryBarcode> → FE làm trang in nhãn

(3) POST /api/factory-barcodes/mark-printed
        body: { codes: [ "26001000000001", "26001000000002", ... ] }
        ← 200 OK → printCount++

(4) POST /api/factory-barcodes/{code}/void  (hủy mã lỗi)
        body: { reason: "In lỗi nhãn" }
```

### 5.2 Công đoạn 2: Sales Import & Shipment Plan
```
(A) Tạo Packing Order
  POST   /api/carton-loading/{buyerCode}/orders
  ← 201 PackingOrderResponse

(B) Import WSP Allocation (Excel)
  POST   /{buyer}/orders/{orderId}/allocation/upload
  form-data: file (xlsx), mode="REPLACE_ALL" (hoặc UPSERT / CREATE_ONLY)
  ← 200 ImportResult { created, updated, deleted, skipped, errorsPerRow[] }
    → errorsPerRow: {row, message} FE tô đỏ row trong Excel preview

(C) Import Packing List (hoặc Generate)
  Option 1: POST /{buyer}/orders/{orderId}/packing-list/upload   (file PackingList ES.xlsx)
  Option 2: POST /{buyer}/orders/{orderId}/packing-list/generate
  ← 200

(D) Tạo Carton Master (1 thùng = 1 dòng physical)
  POST   /{buyer}/orders/{orderId}/cartons/generate
  Query: ?replaceExisting=true (nếu đã có → xóa cũ tạo lại)
  ← 200 { generated: 1234 } → Socket broadcast PLAN_GENERATED

(E) (NEW) Tạo Shipment (lô xuất)
  POST   /api/shipments
  body: { buyerCode, shipmentNo, etd, eta, containerNo, cartonIds: [...] }
  POST   /api/shipments/{id}/complete   → toàn bộ cartonIds → SHIPPED
```

### 5.3 Công đoạn 3: Sewing Line Assignment
```
(A) Xem danh sách UNASSIGNED
  GET /{buyer}/orders/{orderId}/barcode-assignment/cartons?assignment=UNASSIGNED&page=0&size=50

(B) Gán barcode (luồng pre-plan - 90% case)
  POST /{buyer}/orders/{orderId}/barcode-assignment
  body: { factoryBarcode: "26001000000001", cartonId: "..." }
  ← 200 CartonScanTransaction → Socket FACTORY_BARCODE_ASSIGNED
  → Lỗi 409 FACTORY_BARCODE_ALREADY_ASSIGNED → FE toast và focus lại input

(C) (NEW GAP#2) Tạo thùng động (Sewing nhập tay hàng hóa)
  POST /{buyer}/orders/{orderId}/cartons (chế độ create-on-assign)
  body: {
    factoryBarcode: "26001000000001",
    poNumber: "PO-123",
    articleNo: "ART001",
    style: "STYLE-A",
    color: "RED",
    size: "L",
    qtyPerCtn: 50,
    actualPcs: 48,            // thực tế đóng ít hơn
    remark: "Còn lack 2 cái,补 sau"
  }
  ← 201 CartonScanTransaction (status = ASSIGNED)

(D) Unassign (hủy gán)
  DELETE /{buyer}/orders/{orderId}/barcode-assignment/cartons/{cartonId}
  ← 200 (chỉ được khi thùng chưa bắt đầu cân)
```

### 5.4 Công đoạn 4: Packing Check & Weight Station
```
--- Flow chính: Station cân nặng ---
(1) FE (tablet ở trạm) mở trang station, gửi stationCode cấu hình local

(2) User quét mã → FE gọi (tùy loại scanner):
  Option A - Quét FactoryBarcode đã gán:
    POST /{buyer}/orders/{orderId}/factory-barcode/scan
    body: { factoryBarcode, scanId, stationCode, qaCode }

  Option B - Quét QA Code (Zebra scan first):
    POST /{buyer}/orders/{orderId}/scan-next
    body: { qaCode, scanId, stationCode }

  ← 200 CartonScanTransaction (status: WAITING_WEIGHT)
     → Socket WAITING_WEIGHT → flash màn hình "Đang chờ cân..."

(3) PLC giao tiếp BE (không đi qua FE - chỉ hiển thị kết quả):
  - PLC mỗi 200ms poll GET  /plc/stations/{code}/job → nhận job claim
  - PLC cân xong push      POST /plc/weights { jobId, weightKg, unit="KG" }
  - BE tự update status = COMPLETED (pass) / WEIGHT_WARNING (fail)
  - Socket broadcast event với status mới → FE realtime cập nhật

(4) FE Manual fallback (nếu PLC lỗi):
  POST /{buyer}/transactions/{txnId}/manual-weight
  body: { weightKg: 12.3, reason: "PLC disconnected", operatorNote: "..." }
  ← 200 OK → Socket cập nhật

(5) (NEW) Packing Confirm CHECKED → COMPLETED
  POST /{buyer}/cartons/{cartonId}/complete-packing
  body: { confirmBy, remark }
  ← 200 → Socket COMPLETED
```

---

## 6. WebSocket Real-time Contract (Cực kỳ quan trọng cho trang station)

### 6.1 Kết nối
```
WS Endpoint (SockJS - dùng @stomp/stompjs client):
  http://localhost:8083/ws-carton
  Header khi connect: Authorization: Bearer <token>

Subscribe topic:
  /topic/app-events            → toàn bộ event trong hệ thống (filter action phía client)
  /topic/presence/online-count → online staff count (không quan trọng cho FE Packing)
```

### 6.2 Client StompJS mẫu
```ts
import { Client } from '@stomp/stompjs';

const stomp = new Client({
  brokerURL: `ws://${host}/ws-carton`,
  connectHeaders: { Authorization: `Bearer ${token}` },
  debug: console.log,
});
stomp.activate();

stomp.onConnect = () => {
  stomp.subscribe('/topic/app-events', (msg) => {
    const event = JSON.parse(msg.body);
    handleSocketEvent(event);   // FE switch case action
  });
};
```

### 6.3 Event schema nhận được (giống nhau tất cả)
```json
{
  "action": "WAITING_WEIGHT",
  "type": "carton",
  "id": "66d9c82b1f55a2abcdef1234",
  "buyerCode": "ES",
  "payload": { ... CartonScanTransaction (nếu hữu ích) ... }
}
```

### 6.4 Danh sách `action` FE quan tâm
| action | Khi nào xảy ra | FE làm gì? |
|---|---|---|
| `PLAN_GENERATED` | Sales nhấn Generate Carton Master | Trang PackingOrder → refresh progress + toast "Đã tạo 1234 thùng" |
| `PLAN_REFRESHED` | Thay đổi Allocation → sync lại Packing List → carton master đổi | Tương tự, refresh table |
| `FACTORY_BARCODE_ASSIGNED` | Sewing gán barcode xong | Trang Assignment → carton đó chuyển màu xám (đã gán), giảm số count unassigned |
| `FACTORY_BARCODE_UNASSIGNED` | Unassign | Lịch lại trạng thái unassigned |
| `WAITING_WEIGHT` | Scan claim job thành công, chờ cân → **Quan trọng trang station** | Màn hình station: đổi màu vàng "Chờ cân nặng PLC..." |
| `COMPLETED` | Kết quả cân PASS (FE nhận trực tiếp event này khi PLC push) | Biểu thị xanh lá "PASS 12.4kg", sau 3s auto reset về trang scan tiếp |
| `WEIGHT_WARNING` | Kết quả cân FAIL (khỏi tolerance) | Màn hình đỏ "FAIL 12.4kg, expected 11.0 ± 0.3kg" → cho nút Manual / Admin override |
| `CHECKED` | (NEW) Đã cân xong, chờ Packing confirm | List chờ confirm → badge mới |
| `SHIPPED` | (NEW) Đã xuất hàng | Thay icon xanh đỏ trong báo cáo |
| `WEIGHT_RESET` | Admin reset cân thùng đó | Station / Detail → về PLANNED, bỏ các giá trị cũ |

### 6.5 FE Filter event theo ngữ cảnh (để không refresh toàn app)
```ts
function handleSocketEvent(ev) {
  // 1. Filter buyer hiện tại FE đang làm việc
  if (ev.buyerCode !== currentBuyerCode) return;

  // 2. Trang Station hiện tại → chỉ care carton job đang chờ
  if (location.pathname.includes('/station/')) {
    if (ev.id === currentCartonIdOnScreen && ['WAITING_WEIGHT','COMPLETED','WEIGHT_WARNING'].includes(ev.action)) {
      updateStationScreen(ev.action, ev.payload);
    }
    return;
  }

  // 3. Trang Assignment → refresh counter
  if (location.pathname.includes('/assignment') && ['FACTORY_BARCODE_ASSIGNED','FACTORY_BARCODE_UNASSIGNED'].includes(ev.action)) {
    refreshAssignmentStats();
  }
}
```

---

## 7. Data Format (Định dạng chung KHÔNG được tùy tiện)

### 7.1 Ngày tháng
→ FE gửi / BE trả về đều là **ISO-8601 with timezone**:
```
2026-09-06T10:21:30+07:00
```
Không dùng `LocalDate` không có timezone → lỗi múi giờ user máy Nhật/Việt.

### 7.2 Số & Đơn vị
| Field | Đơn vị | Kiểu | Chú thích |
|---|---|---|---|
| `expectedWeightKg` / `weightKg` / `grossWeightKg` | **Kilogram** | number (double) | FE hiển thị 2 chữ số thập phân `12.40 kg` |
| `toleranceKg` | Kilogram | number | `0.3` nghĩa là ± 300 gram |
| `cbm` | m³ | number | 3 số lẻ |
| `quantity` / `actualPcs` / `qtyPerCtn` | Piece (cái quần áo) | integer | KHÔNG có số lẻ |
| `quantitySea` / `quantityAir` | Piece | integer | Tách riêng Sea/Air (có trong Allocation) |

### 7.3 Màu Status (FE dùng chuẩn thống nhất Ant Design Badge)
```ts
export const CARTON_STATUS_TAG: Record<CartonScanStatus, { color: string; text: string }> = {
  PLANNED:          { color: 'default', text: 'Created / Chưa gán' },
  ASSIGNED:         { color: 'processing', text: 'Assigned / Đã gán mã' },
  WAITING_WEIGHT:   { color: 'warning', text: 'Chờ cân nặng' },
  CHECKED:          { color: 'blue', text: 'Checked / Đã cân' },
  COMPLETED:        { color: 'success', text: 'Completed / Đóng gói OK' },
  WEIGHT_WARNING:   { color: 'orange', text: 'FAIL Cân nặng' },
  SHIPPED:          { color: 'magenta', text: 'Shipped / Đã xuất' },
  CANCELLED:        { color: 'red', text: 'Cancelled' },
};
```

---

## 8. Lookup / Report (API TỔNG HỢP mới sẽ thêm)

Sau khi BE bổ sung GAP #4 và #5, FE gọi các endpoint chung:

### 8.1 Global lookup (không cần order trước)
```
GET /api/lookup/barcode/{code}
  ← { barcode, carton: CartonScanTransaction|null, order, shipment: Shipment|null }
GET /api/lookup/po/{poNumber}
  ← { poNumber, orders: PackingOrder[], cartonsSummary: { total, perStatus } }
GET /api/lookup/product?article=&style=&color=&size=
GET /api/lookup/shipment/{shipmentNo}
```

### 8.2 Báo cáo
```
GET /api/reports/cartons?buyer=&from=&to=&status=&po=   → Page<CartonReportRow> + header X-Total-Count
GET /api/reports/summary?buyer=&from=&to=                → Dashboard card tổng
GET /api/reports/shipments/{id}                          → Shipment manifest
POST /api/reports/cartons/export                          → trả về byte[] xlsx (FE save file)
```

→ Export sample download:
```ts
// FE axios call with responseType=blob
const resp = await axios.post('/api/reports/cartons/export', filters, { responseType: 'blob' });
const url = URL.createObjectURL(new Blob([resp.data]));
const a = document.createElement('a'); a.href = url; a.download = 'CartonReport_20260906.xlsx'; a.click();
```

---

## 9. Mock / Seed Data (FE test không cần nhập tay)

### 9.1 1 lệnh seed hệ thống demo (script BE sẽ cung cấp)
```
POST /api/dev/seed-demo
body: { resetAll: true }
```
Tự tạo ra:
- 1 admin user (`admin@bsl.com` / `admin123`)
- 3 buyer (ES, Adidas, Uniqlo)
- 2 PackingOrder đã có Allocation + Packing List + 500 thùng PLANNED, 50 thùng ASSIGNED, 30 COMPLETED
- 1000 FactoryBarcode AVAILABLE để test assign
- 2 station cân `STN-A1` (tolerance ±0.3kg), `STN-A2`

### 9.2 Mock Server (FE test offline không cần BE thật)
Dùng **Prism** + OpenAPI spec:
```bash
npm install -g @stoplight/prism-cli
prism mock http://localhost:8083/v3/api-docs -p 4010
# FE point baseUrl=http://localhost:4010 → Prism tự trả về example theo schema
```

---

## 10. Checklist Integration (FE scan từng màn hình xong tick)

| Màn hình | Endpoint chính cần gọi | Event socket cần sub | Checkbox |
|---|---|---|---|
| Login | `/api/auth/login` + `/select-buyer` | — | ☐ |
| Barcode Generate | `/factory-barcodes/generate` + `/mark-printed` | — | ☐ |
| Barcode List | `/factory-barcodes?page=&size=` | — | ☐ |
| Packing Order List | `GET /{buyer}/orders` + `/progress` | `PLAN_GENERATED` refresh | ☐ |
| Import Allocation | `POST .../allocation/upload` (multipart) | — | ☐ |
| Import Packing List | `POST .../packing-list/upload` (multipart) | — | ☐ |
| Sewing Barcode Assignment | `GET assignment UNASSIGNED` + `POST assign` | `FACTORY_BARCODE_ASSIGNED/UNASSIGNED` | ☐ |
| Sewing Create-Dynamic-Carton (NEW) | `POST .../cartons` | `FACTORY_BARCODE_ASSIGNED` | ☐ |
| **Weight Station (Chính)** | `/factory-barcode/scan` + `/manual-weight` | **WAITING_WEIGHT / COMPLETED / WEIGHT_WARNING / WEIGHT_RESET** | ☐ |
| Packing Confirmation | `GET CHECKED list` + `POST /complete-packing` | `CHECKED / COMPLETED` | ☐ |
| Shipment Manifest (NEW) | `POST /shipments` + `/complete` | `SHIPPED` | ☐ |
| Report Dashboard | `/api/reports/summary` + `/cartons` | — | ☐ |
| Global Lookup | `/api/lookup/barcode/:code` | — | ☐ |
| User/Department Mgmt (ADMIN) | `/api/users` + `/departments` | — | ☐ |

---

## 11. SLA và Quy tắc đổi contract

| Trường hợp | Thời gian báo trước | Phương thức |
|---|---|---|
| Thêm endpoint / field mới (không ảnh hưởng FE cũ) | 0 ngày | Update file này, FE tùy chọn dùng |
| Sửa / Xóa field response FE đang dùng | **3 ngày làm việc** | PR + Meeting với FE lead, confirm FE đã update rồi mới merge BE |
| Đổi tên event socket / giá trị enum status | **5 ngày làm việc** | Phase 2 bên: BE emit event CŨ + MỚI song song 5 ngày → FE chuyển xong → BE xóa cũ |
| Đổi format lỗi ApiError | 3 ngày | Notify FE team + có migration interceptor FE |

→ **Mọi tranh chấp về contract / field nghĩa là gì → mở Swagger UI / file này làm chuẩn cuối cùng.**
