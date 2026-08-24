# AR Measure trên Android với ARCore — Báo cáo triển khai (bản gộp toàn bộ)

**Mục tiêu**: tính năng đo kích thước qua camera — đo khoảng cách 2 điểm + đo vật thể Dài × Rộng × Cao.
**Nền tảng**: Android native (Kotlin + ARCore SDK).
**Ngày biên soạn**: 2026-08-24.

> Đây là bản **gộp tất cả 7 phần vào một file** để đọc/in/tìm kiếm liền mạch.
> Nếu muốn đọc theo từng phần riêng, dùng các file `00-` … `07-` trong cùng thư mục.

---

## Mục lục

- **Phần 0 — Bản đồ tài liệu & kết luận nhanh** — `00-README.md`
- **Phần 1 — Tổng quan ARCore chắt lọc theo hướng đo kích thước** — `01-tong-quan-arcore.md`
- **Phần 2 — Hạn chế phần cứng, độ phủ thiết bị & fallback** — `02-han-che-phan-cung-va-thiet-bi.md`
- **Phần 3 — Thiết kế tính năng: kiến trúc, UX, thuật toán** — `03-thiet-ke-tinh-nang-ar-measure.md`
- **Phần 4 — Code mẫu Kotlin** — `04-code-mau-kotlin.md`
- **Phần 5 — Độ chính xác & kế hoạch kiểm thử** — `05-do-chinh-xac-va-kiem-thu.md`
- **Phần 6 — Roadmap, effort & rủi ro** — `06-roadmap-va-rui-ro.md`
- **Phần 7 — Nguồn tham khảo** — `07-nguon-tham-khao.md`

---



# Phần 0 — Bản đồ tài liệu & kết luận nhanh

*(nguồn: `00-README.md`)*

> **Mục tiêu**: xây dựng tính năng đo kích thước thực tế qua camera (đo khoảng cách 2 điểm + đo vật thể Dài × Rộng × Cao) trên Android, dùng **ARCore**.
> **Nền tảng đã chọn**: Android native (Kotlin + ARCore SDK, có tuỳ chọn NDK cho phần xử lý point cloud).
> **Nguồn gốc dữ liệu**: chắt lọc từ tài liệu chính thức ARCore của Google (xem `07-nguon-tham-khao.md`).
> **Ngày biên soạn**: 2026-08-24.

---

### 1. Kết luận nhanh cho quyết định triển khai

| Câu hỏi | Trả lời ngắn |
|---|---|
| ARCore làm được tính năng đo không? | **Được.** Google đưa "Measurement" vào danh sách use case chính thức của Depth API. |
| Cần phần cứng đặc biệt (LiDAR/ToF) không? | **Không bắt buộc.** ARCore dựng chiều sâu bằng thuật toán *depth-from-motion* trên camera RGB thường + IMU. |
| Đo khoảng cách 2 điểm — độ khó | **Thấp.** Chỉ cần hit-test + Anchor + khoảng cách Euclid. ~1 sprint cho bản dùng được. |
| Đo vật thể D×R×C — độ khó | **Trung bình → Cao.** Bản "3 lần chạm thủ công" khả thi nhanh; bản "tự động dựng bounding box từ point cloud" cần R&D. |
| Rào cản lớn nhất | **Không phải API — mà là độ chính xác & độ phủ thiết bị.** Xem `02` và `05`. |
| Khoảng đo tin cậy | **0.5 m – 5 m** (Google công bố là dải tối ưu). Tối đa lý thuyết 65 m nhưng không dùng cho đo. |
| Rủi ro chặn tính năng | Bề mặt không hoạ tiết (tường trắng, kính, gương), ánh sáng yếu, người dùng không di chuyển máy. |

**Khuyến nghị**: chia 2 pha. Pha 1 giao *đo khoảng cách/chiều dài* (giá trị người dùng cao, rủi ro thấp). Pha 2 mới làm *bounding box vật thể*, và bắt buộc có bench test đối chiếu thước laser trước khi lên production.

---

### 2. Cấu trúc bộ tài liệu

| File | Nội dung | Dành cho |
|---|---|---|
| `00-README.md` | Bản đồ tài liệu + kết luận nhanh | Tất cả |
| `01-tong-quan-arcore.md` | ARCore hoạt động thế nào, các API dùng cho đo, API nào bỏ qua | Dev / Tech Lead |
| `02-han-che-phan-cung-va-thiet-bi.md` | Ma trận yêu cầu phần cứng, độ phủ thiết bị, chiến lược fallback theo tier | Tech Lead / PO |
| `03-thiet-ke-tinh-nang-ar-measure.md` | Kiến trúc module, luồng UX, thuật toán đo 2 điểm & bounding box | Dev / Designer |
| `04-code-mau-kotlin.md` | Code mẫu chạy được: session config, hit-test, đo, unproject depth → point cloud | Dev |
| `05-do-chinh-xac-va-kiem-thu.md` | Nguồn sai số, kỹ thuật giảm sai số, kế hoạch bench test, tiêu chí chấp nhận | QA / Dev |
| `06-roadmap-va-rui-ro.md` | Chia pha, ước tính effort, đăng ký rủi ro, quyết định kiến trúc cần chốt | PO / Tech Lead |
| `07-nguon-tham-khao.md` | Toàn bộ link nguồn + ghi chú độ tin cậy | Tất cả |

---

### 3. Cảnh báo trước khi đọc tiếp

1. **Số phiên bản SDK trong tài liệu này cần verify lại** tại thời điểm bắt đầu code. Trang tải SDK của Google yêu cầu chấp nhận ToS nên không đọc được version hiện hành qua crawler; bản mới nhất quan sát được trên GitHub là `1.54.0`.
2. **Không có con số độ chính xác chính thức nào từ Google.** Mọi ngưỡng sai số trong `05` là *đề xuất tiêu chí chấp nhận* của bộ tài liệu này, không phải cam kết của ARCore. Phải tự đo.
3. **Sceneform đã bị Google khai tử.** Đừng dựa vào nó. Xem quyết định kiến trúc trong `06`.

---


# Phần 1 — Tổng quan ARCore chắt lọc theo hướng đo kích thước

*(nguồn: `01-tong-quan-arcore.md`)*

### 1. ARCore là gì, làm được gì (nền tảng)

ARCore là nền tảng AR của Google. Nó làm đúng **2 việc lõi**:

1. **Motion tracking (SLAM)** — theo dõi vị trí + hướng của thiết bị trong không gian. Cơ chế: camera nhặt ra các *feature point* (điểm đặc trưng thị giác) trên ảnh, theo dõi chúng dịch chuyển qua các frame, kết hợp với số đọc từ cảm biến quán tính (accelerometer + gyroscope) để suy ra pose của máy.
2. **Environmental understanding** — dựng hiểu biết về thế giới thật: phát hiện mặt phẳng, sinh bản đồ chiều sâu, ước lượng ánh sáng.

Ba khả năng Google nêu ở trang tổng quan: *motion tracking*, *environmental understanding*, *light estimation*. Với tính năng đo, ta chỉ cần **2 cái đầu**. Light estimation không liên quan (chỉ dùng để render vật ảo trông thật).

### Điểm quan trọng nhất cho bài toán đo

> **Pose trong ARCore có đơn vị là MÉT, trong hệ toạ độ thuận tay phải (right-handed, quy ước OpenGL).**

Đây chính là lý do tính năng đo khả thi: ARCore đã trả về toạ độ *thang đo thực* (metric scale), không phải toạ độ tương đối. Đo = lấy 2 điểm world-space rồi tính khoảng cách Euclid. Không cần calibrate, không cần vật tham chiếu.

Nguồn scale này đến từ việc fusion camera + IMU. Cũng chính vì vậy mà **sai số đo phụ thuộc chất lượng tracking**, chứ không phụ thuộc "độ phân giải camera" như trực giác thường nghĩ.

---

### 2. Các khối API của ARCore — cái nào dùng, cái nào bỏ

| API / khái niệm | Dùng cho AR Measure? | Ghi chú |
|---|---|---|
| **Session / Frame / Camera** | ✅ Bắt buộc | Vòng đời AR, mỗi frame lấy pose camera. |
| **Pose** | ✅ Bắt buộc | Toạ độ mét. `getTranslation()`, `tx/ty/tz`, `transformPoint()`, `compose()`, `inverse()`, `toMatrix()`. |
| **Anchor** | ✅ Bắt buộc | Neo điểm đo vào thế giới. Pose của anchor được ARCore *tinh chỉnh dần* khi hiểu biết về scene tốt lên → chính xác hơn nhiều so với lưu pose tĩnh. |
| **Trackable / Plane** | ✅ Bắt buộc | Mặt phẳng ngang/dọc. Dùng làm mặt nền để đo vật thể và làm fallback khi không có Depth. |
| **Hit-test** (`Frame.hitTest`) | ✅ Bắt buộc | Bắn tia từ điểm chạm trên màn hình vào scene → trả điểm 3D. Đây là cơ chế "chọn điểm đo". |
| **Depth API** (`DepthMode.AUTOMATIC`) | ✅ Rất nên | Cho phép hit-test trên bề mặt bất kỳ (không cần mặt phẳng), và cho phép dựng point cloud để đo vật thể. |
| **Raw Depth API** | ✅ Cho pha 2 | Chiều sâu thô + ảnh độ tin cậy (confidence). Chính xác hơn theo từng pixel, chi phí tính toán ~½ full depth. Đây là API để dựng bounding box vật thể. |
| **Instant Placement** | ⚠️ Chỉ dùng cho UI preview | Đặt vật tức thì bằng *khoảng cách phỏng đoán* → **scale sai** cho tới khi có FULL_TRACKING. **Tuyệt đối không lấy số đo từ InstantPlacementPoint.** |
| **Recording & Playback** | ✅ Rất nên (cho QA) | Ghi session ra MP4 (video VGA 640×480 + IMU + depth map) rồi phát lại như session thật → **test hồi quy độ chính xác trên nhiều máy với cùng một dataset**. Tiết kiệm rất nhiều công QA. |
| **Camera Intrinsics** | ✅ Cho pha 2 | `getFocalLength()` → `{fx, fy}` (pixel), `getPrincipalPoint()` → `{cx, cy}`, `getImageDimensions()` → `{w, h}`. Cần để unproject pixel depth → điểm 3D. |
| Cloud Anchors | ❌ Bỏ | Chỉ dùng khi cần chia sẻ AR giữa nhiều máy. |
| Geospatial API | ❌ Bỏ | Định vị theo toạ độ Trái Đất, không liên quan đo vật thể. |
| Augmented Faces | ❌ Bỏ | — |
| Augmented Images | ❌ Bỏ | (Trừ khi muốn dùng marker in sẵn làm vật tham chiếu để hiệu chuẩn — xem `05`, mục "hiệu chuẩn tuỳ chọn".) |
| Light Estimation | ❌ Bỏ | Chỉ phục vụ render. |
| Scene Semantics | ❌ Bỏ | Phân loại vùng ngoài trời, không phục vụ đo. |

---

### 3. Depth API — phần cốt tử, đọc kỹ

### 3.1. Chiều sâu đến từ đâu

Có **2 nguồn**, ARCore tự merge:

1. **Depth-from-motion (mặc định, hầu hết máy)**
   Thuật toán phân tích nhiều frame camera từ các góc khác nhau khi người dùng *di chuyển máy*. Có dùng machine learning để cải thiện khi chuyển động ít.
   → **Hệ quả UX bắt buộc**: phải yêu cầu người dùng di chuyển/lia máy trước khi cho đo. Nếu máy đứng yên hoàn toàn thì không có depth.

2. **Cảm biến chiều sâu phần cứng (ToF), nếu có**
   ARCore tự động merge. Cho chiều sâu tốt hơn ở bề mặt ít/không hoạ tiết (tường trắng) và ở scene động (người đang đi lại).
   → **Nhưng đây là thiểu số cực nhỏ trên Android.** Xem `02`. **Không được thiết kế phụ thuộc ToF.**

### 3.2. Định nghĩa giá trị depth (dễ nhầm)

Giá trị trong depth image **không phải khoảng cách theo tia** từ camera tới điểm. Nó là **độ dài hình chiếu của vector camera→điểm lên trục quang chính (principal axis)** — tức thành phần `z` trong hệ toạ độ camera.

Nếu bỏ qua chi tiết này, mọi điểm ở rìa khung hình sẽ bị đo sai (càng lệch tâm càng sai). Công thức unproject đúng nằm ở `04`, mục 5.

### 3.3. Full Depth vs Raw Depth

| Tiêu chí | Full Depth (`acquireDepthImage16Bits`) | Raw Depth (`acquireRawDepthImage16Bits`) |
|---|---|---|
| Độ phủ pixel | Mọi pixel đều có giá trị | **Không phủ hết pixel** — chỗ nào không chắc thì bỏ trống |
| Độ chính xác từng pixel | Thấp hơn (đã làm mượt + nội suy) | **Cao hơn** |
| Ảnh confidence | Không có | **Có** (`acquireRawDepthConfidenceImage`, format Y8, 0 = không tin cậy … 255 = tin cậy nhất) |
| Chi phí tính toán | Baseline | ~**½** so với full depth |
| Dùng khi | Occlusion, hit-test, hiệu ứng hình ảnh | **ĐO ĐẠC, dựng point cloud, 3D reconstruction** |

**Kết luận kiến trúc**: dùng **Full Depth cho hit-test (chọn điểm)** và **Raw Depth + Confidence cho dựng bounding box vật thể**. Đừng dùng full depth để đo point cloud — dữ liệu đã bị làm mượt, kích thước vật sẽ bị "phình/bo góc".

### 3.4. Đặc tính confidence (dùng để lọc)

- Vùng có hoạ tiết → confidence cao.
- **Bề mặt không hoạ tiết thường cho confidence = 0.**
- Máy có ToF: vùng gần camera sẽ có confidence cao hơn kể cả khi không hoạ tiết.

→ Ngưỡng lọc đề xuất khởi điểm: **confidence ≥ 100/255** cho đo đạc (điều chỉnh sau bench test).

### 3.5. Dải hoạt động

| Thông số | Giá trị (Google công bố) |
|---|---|
| Dải tối ưu | **0.5 m → ~5 m** |
| Dải tối đa | 65 m |
| Điều kiện cần | Có chuyển động thiết bị (với depth-from-motion) |

→ Trong UI **phải chặn/cảnh báo** khi điểm đo nằm ngoài 0.3–5 m.

---

### 4. Hit-test — cơ chế chọn điểm đo

`frame.hitTest(x, y)` bắn tia từ điểm chạm trên màn hình vào scene, trả về `List<HitResult>` **đã sắp xếp theo khoảng cách tăng dần từ camera**. Phần tử đầu tiên thường là thứ người dùng thực sự nhìn thấy.

Có **4 loại trackable** có thể trả về:

| Loại | Bản chất | Dùng để đo? |
|---|---|---|
| `DepthPoint` | Dùng chiều sâu của toàn scene → đặt được lên **bề mặt bất kỳ**, không cần mặt phẳng | ✅ **Ưu tiên số 1**. Yêu cầu bật Depth mode + máy hỗ trợ Depth. |
| `Plane` | Mặt phẳng ngang/dọc đã phát hiện | ✅ **Ưu tiên số 2 / fallback**. Google nói dùng Plane khi cần *scale chính xác ngay lập tức* trên sàn/tường. Rất phù hợp làm mặt nền đo vật thể. |
| `Point` | Feature point đơn lẻ | ⚠️ Ưu tiên 3. Nhiễu hơn, nhưng chấp nhận được khi không có gì khác. |
| `InstantPlacementPoint` | Vị trí screen-space + **chiều sâu phỏng đoán** | ❌ **KHÔNG dùng để lấy số đo.** Pose/scale chỉ đúng sau khi chuyển sang `FULL_TRACKING`; lúc chuyển, vật "phình ra hoặc co lại". |

**Quan trọng**: máy **không hỗ trợ Depth thì ARCore tự động fallback về plane hit-test** — nghĩa là code không crash, nhưng **kết quả sẽ chỉ nằm trên mặt phẳng**, không đo được điểm giữa không gian. Đây là lý do phải có chiến lược tier ở `02`.

Ngoài ra còn `frame.hitTest(origin3, offset, direction3, offset)` — bắn tia tuỳ ý trong world space. Dùng cho reticle ở giữa màn hình hoặc cho việc dò chiều cao vật thể theo trục thẳng đứng.

---

### 5. Những gì trang tổng quan ARCore *nói rõ* là hạn chế

Trích ý từ tài liệu chính thức:

- *"Bề mặt phẳng không có hoạ tiết, ví dụ tường trắng, có thể không được phát hiện đúng."*
- Depth: *"bề mặt ít hoạ tiết cho kết quả đo không chính xác"*, và *"depth chỉ khả dụng khi có chuyển động của người dùng"*.
- Depth mode **mặc định TẮT**, phải bật thủ công.
- Depth API **cần thiết bị có đủ năng lực xử lý** → không phải mọi máy chạy ARCore đều có Depth.

---

### 6. SDK & môi trường phát triển

Google cung cấp SDK cho: **Android (Kotlin/Java)**, **Android NDK (C)**, **Unity (AR Foundation)**, **iOS**, **Unreal**, **Web**.
Đã chọn: **Android (Kotlin/Java)**, cân nhắc NDK cho vòng lặp xử lý point cloud nếu profiling cho thấy JNI/Kotlin quá chậm.

| Hạng mục | Giá trị |
|---|---|
| `minSdkVersion` | **24** (Android 7.0) |
| Dependency | `implementation 'com.google.ar:core:<version>'` — verify version mới nhất trước khi code (bản quan sát được: `1.54.0`) |
| Runtime prerequisite | **Google Play Services for AR** phải có trên máy (`ArCoreApk.checkAvailability()` / `requestInstall()`) |
| Emulator | Có hỗ trợ nhưng giới hạn — **không dùng emulator để đánh giá độ chính xác** |

---


# Phần 2 — Hạn chế phần cứng, độ phủ thiết bị & fallback

*(nguồn: `02-han-che-phan-cung-va-thiet-bi.md`)*

### 1. Ba tầng điều kiện (device gating)

Một chiếc máy Android phải vượt **3 cửa** mới dùng được tính năng đo đầy đủ:

```
Cửa 1: Android 7.0+ (API 24)              → nếu fail: không có AR
   ↓
Cửa 2: Thiết bị được ARCore chứng nhận     → nếu fail: không có AR
        + có Google Play Services for AR
   ↓
Cửa 3: Hỗ trợ Depth API                    → nếu fail: chỉ đo được trên mặt phẳng
   ↓
(Tuỳ chọn) Có cảm biến ToF phần cứng       → cực hiếm, coi như bonus
```

### Cửa 2 — chứng nhận ARCore

Google chỉ chứng nhận máy sau khi kiểm tra **camera, cảm biến chuyển động và kiến trúc thiết kế** để đảm bảo motion tracking hoạt động đủ tốt. Danh sách có hàng trăm model.

Cần biết:
- Đa số máy trong danh sách yêu cầu Android 7.0.
- **Một số model bị nâng ngưỡng**: ví dụ Nexus 5X/6P yêu cầu Android 8.0+; Nokia G50, Nokia X10 yêu cầu **Android 13+**.
- Không thể suy ra từ cấu hình máy — **phải tra danh sách hoặc kiểm tra runtime**.

### Cửa 3 — Depth API: đây là chỗ mất thiết bị nhiều nhất

Depth API **không có trên mọi máy được ARCore chứng nhận**. Nó cần năng lực xử lý cao hơn.

Quan sát từ danh sách thiết bị chính thức:

| Nhóm | Trạng thái Depth API |
|---|---|
| Google Pixel từ **Pixel 2** trở lên (2, 2XL, 3, 3XL, 3a, 3aXL, 4, 4XL, 4a, 5, 6, 6a, 6 Pro, 7, 7a, 7 Pro, 8, 8 Pro, Fold) | ✅ Có |
| Samsung Galaxy dòng S và dòng A (**nhiều — không phải tất cả** model) | ✅ Có (phải tra từng model) |
| Asus ROG Phone III, 5, 6, 7, 8, 9 | ✅ Có |
| Motorola moto g 5G, g⁹ plus, g⁹ power, g power (2021) | ✅ Có |
| **Pixel 1 / Pixel XL và phần lớn máy trước 2019** | ❌ Không |
| **Huawei P20 Pro** | ❌ Không |

> ⚠️ **Không hardcode danh sách này vào app.** Danh sách của Google thay đổi liên tục. **Luôn kiểm tra runtime** bằng `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`.

### Cảm biến ToF phần cứng — đừng trông vào

Trong toàn bộ danh sách thiết bị, số model được Google ghi rõ có **cảm biến chiều sâu time-of-flight** đếm trên đầu ngón tay (ví dụ **LG V60 ThinQ / V60 ThinQ 5G**).

**Kết luận thẳng**: Android **không có** tương đương LiDAR của iPhone Pro ở quy mô đại chúng. Toàn bộ tính năng đo phải được thiết kế để chạy tốt trên **depth-from-motion bằng camera RGB đơn**. Nếu spec sản phẩm đang được viết dựa trên trải nghiệm app Measure của iPhone Pro (có LiDAR), **cần điều chỉnh kỳ vọng ngay từ giai đoạn thiết kế**.

---

### 2. Ma trận hạn chế phần cứng đầy đủ

| # | Hạn chế | Mức tác động | Biểu hiện với người dùng | Cách xử lý trong app |
|---|---|---|---|---|
| H1 | Không có ToF/LiDAR đại chúng trên Android | 🔴 Cao | Cần lia máy vài giây mới đo được; không đo được "ngay lập tức" | Onboarding hướng dẫn lia máy; progress indicator "đang quét môi trường" |
| H2 | Depth cần chuyển động thiết bị | 🔴 Cao | Đứng yên chỉa máy → không có depth | Gate nút "Đo" đến khi có đủ depth/plane; animation hướng dẫn di chuyển |
| H3 | Bề mặt không hoạ tiết (tường trắng, kính, gương, vật bóng, vật đen tuyền) | 🔴 Cao | Confidence = 0, đo lệch hoặc không bắt được điểm | Đọc confidence → hiện cảnh báo "bề mặt khó nhận diện"; gợi ý đặt vật tham chiếu có hoạ tiết |
| H4 | Dải tin cậy chỉ 0.5–5 m | 🟠 Trung bình | Đo vật rất nhỏ (< 10 cm ở cự ly gần) hoặc rất xa đều sai nhiều | Chặn/cảnh báo ngoài 0.3–5 m; hiện khoảng cách hiện tại tới điểm ngắm |
| H5 | Ánh sáng yếu → mất tracking | 🟠 Trung bình | Số đo nhảy, mất anchor | Bắt `TrackingFailureReason.INSUFFICIENT_LIGHT` → overlay hướng dẫn |
| H6 | Chuyển động máy quá nhanh | 🟠 Trung bình | Mất tracking, drift | Bắt `EXCESSIVE_MOTION`; làm mượt reticle |
| H7 | Scene động (người/vật di chuyển) | 🟠 Trung bình | Depth-from-motion giả định scene tĩnh → depth sai | Cảnh báo trong UX; máy có ToF thì đỡ hơn |
| H8 | Nhiệt & pin: camera + depth + render liên tục | 🟠 Trung bình | Máy nóng, giảm hiệu năng, sập fps sau ~5–10 phút | Chỉ bật depth khi đang ở màn hình đo; dùng **Raw Depth** (½ chi phí); giảm tần số xử lý point cloud (ví dụ 5 Hz thay vì 30 Hz); auto-pause session khi idle |
| H9 | Độ phân giải/fps camera khác nhau theo máy (1080p/720p/480p; 60 fps chỉ một số máy) | 🟡 Thấp | Trải nghiệm không đồng nhất | Chọn `CameraConfig` theo tier máy; không giả định 60 fps |
| H10 | Depth image **không** cùng kích thước/aspect với ảnh camera, và ở **native sensor orientation** | 🟡 Thấp nhưng dễ gây bug | Điểm đo lệch hẳn khi xoay máy | **Luôn** dùng `Frame.transformCoordinates2d()` để đổi hệ toạ độ; scale intrinsics theo kích thước depth image |
| H11 | Emulator hỗ trợ giới hạn | 🟡 Thấp | — | Không dùng emulator để đo độ chính xác; dùng **Recording & Playback** thay thế |
| H12 | Máy ở thị trường Trung Quốc không có Google Play Services for AR | 🟡 Tuỳ thị trường | Không chạy được | Nếu có kế hoạch phát hành TQ: cần tra nhánh "Android (China)" trong danh sách thiết bị và kênh phân phối riêng của hãng |

---

### 3. Chiến lược fallback theo tier — bắt buộc implement

Phân loại máy **tại runtime**, không theo whitelist.

```kotlin
enum class MeasureTier { FULL, PLANE_ONLY, UNSUPPORTED }
```

| Tier | Điều kiện phát hiện | Tính năng bật | Tính năng khoá |
|---|---|---|---|
| **FULL** | `ArCoreApk` availability = SUPPORTED_INSTALLED **và** `isDepthModeSupported(AUTOMATIC) == true` | Đo 2 điểm trên bề mặt bất kỳ (DepthPoint); đo bounding box vật thể (Raw Depth point cloud); đo diện tích; occlusion cho vạch đo | — |
| **PLANE_ONLY** | ARCore OK nhưng **không** hỗ trợ Depth | Đo 2 điểm **trên cùng một mặt phẳng đã phát hiện**; đo diện tích mặt phẳng; đo vật thể **thủ công 3 lần chạm** (2 điểm đáy + 1 điểm đỉnh) | Bounding box tự động; đo điểm lơ lửng giữa không gian; occlusion |
| **UNSUPPORTED** | ARCore không khả dụng / thiết bị không được chứng nhận | Ẩn/disable toàn bộ tab AR, hiện màn hình giải thích + cho **nhập số đo thủ công** | Toàn bộ AR |

**Nguyên tắc UX**: không bao giờ hiện tính năng rồi để nó fail. Kiểm tra tier ở màn hình vào, và ghi nhận `tier` vào analytics để biết phân bố thực tế của tập người dùng.

### Cấu hình phát hành

| Trường hợp | Manifest |
|---|---|
| App **chỉ** để đo (AR là bắt buộc) | `<uses-feature android:name="android.hardware.camera.ar" />` + `<meta-data android:name="com.google.ar.core" android:value="required" />` → Play Store chỉ phát hành cho máy hỗ trợ và tự cài Play Services for AR |
| AR là **một tính năng** trong app lớn (khuyến nghị cho hầu hết trường hợp) | `<meta-data android:name="com.google.ar.core" android:value="optional" />` → app cài được trên mọi máy, tự gọi `requestInstall()` khi user vào tính năng đo |

Nếu muốn Play Store chỉ phát hành cho máy có Depth (chỉ dùng khi app hoàn toàn phụ thuộc depth):
```xml
<uses-feature android:name="com.google.ar.depth" />
```
→ Cẩn trọng: cắt rất nhiều thiết bị. Với đa số sản phẩm, **fallback tier tốt hơn là cắt thiết bị**.

---

### 4. Câu hỏi cần dữ liệu nội bộ trước khi chốt scope

Bộ tài liệu này không thay được số liệu thị trường của bạn. Cần lấy từ analytics/Play Console:

1. **Phân bố Android version** của tập user hiện tại → bao nhiêu % ≥ API 24?
2. **Top 20 model** theo lượng user → tra từng model trong danh sách ARCore, đánh dấu có/không Depth API → ra được **% user ở tier FULL**.
3. Nếu **% tier FULL < 50%**, cần bàn lại: có nên đầu tư bounding box tự động, hay chỉ làm bản thủ công chạy được ở cả 2 tier?

---


# Phần 3 — Thiết kế tính năng: kiến trúc, UX, thuật toán

*(nguồn: `03-thiet-ke-tinh-nang-ar-measure.md`)*

### 1. Phạm vi tính năng

| Mã | Tính năng | Tier tối thiểu | Pha |
|---|---|---|---|
| M1 | **Đo khoảng cách 2 điểm** (chiều dài, chiều rộng, đường chéo) | PLANE_ONLY | 1 |
| M2 | **Đo chuỗi điểm** (polyline: chu vi, đường gấp khúc) | PLANE_ONLY | 1 |
| M3 | **Đo diện tích** mặt phẳng (đa giác trên sàn/tường/mặt bàn) | PLANE_ONLY | 1 |
| M4 | **Đo vật thể D×R×C — thủ công** (người dùng chạm để dựng khối hộp) | PLANE_ONLY | 2 |
| M5 | **Đo vật thể D×R×C — bán tự động** (khoanh vùng → point cloud → oriented bounding box) | FULL | 3 |
| M6 | Xuất kết quả (ảnh chụp có annotation, JSON số đo, lịch sử phiên đo) | — | 2 |

---

### 2. Kiến trúc module

```
app/
└── feature-armeasure/
    ├── ArCoreLifecycle.kt        # ArCoreApk check, requestInstall, permission, session create/resume/pause
    ├── ArSessionConfigurator.kt  # DepthMode, PlaneFindingMode, FocusMode, LightEstimation, CameraConfig
    ├── DeviceCapability.kt       # phát hiện MeasureTier (FULL / PLANE_ONLY / UNSUPPORTED)
    │
    ├── render/                   # Lớp render — xem "Quyết định kiến trúc" bên dưới
    │   ├── ArRenderer.kt         # background camera texture + depth occlusion shader
    │   ├── ReticleRenderer.kt    # vòng ngắm giữa màn hình, xoay theo pháp tuyến bề mặt
    │   ├── LineRenderer.kt       # đoạn thẳng + nhãn số đo
    │   └── BoxRenderer.kt        # khối hộp wireframe
    │
    ├── measure/                  # LÕI LOGIC — không phụ thuộc Android/render, unit-test được
    │   ├── MeasurePoint.kt       # wrapper của Anchor + metadata (nguồn hit, confidence, timestamp)
    │   ├── DistanceCalculator.kt # khoảng cách Euclid, polyline, chiếu lên mặt phẳng
    │   ├── AreaCalculator.kt     # shoelace formula trên toạ độ 2D của mặt phẳng
    │   ├── PlaneFrame.kt         # hệ toạ độ cục bộ của mặt phẳng (origin + basis vectors)
    │   ├── BoundingBoxBuilder.kt # dựng OBB: PCA + chiếu + percentile chiều cao
    │   └── UnitFormatter.kt      # m/cm/mm ↔ ft/in, làm tròn có ý nghĩa
    │
    ├── depth/                    # Pha 3
    │   ├── DepthReader.kt        # acquireRawDepthImage16Bits + confidence, đọc giá trị mm
    │   ├── PointCloudExtractor.kt# unproject pixel+depth → world point, lọc theo confidence/range
    │   └── PointCloudFilter.kt   # loại nền theo mặt phẳng, statistical outlier removal, voxel downsample
    │
    ├── quality/                  # Cổng chất lượng
    │   ├── TrackingQualityMonitor.kt  # TrackingState, TrackingFailureReason → thông điệp hướng dẫn
    │   └── MeasurementConfidence.kt   # gán mức tin cậy cho từng số đo (HIGH/MEDIUM/LOW)
    │
    └── ui/
        ├── MeasureScreen.kt
        ├── GuidanceOverlay.kt    # "hãy lia máy", "thiếu sáng", "quá xa"
        └── ResultSheet.kt
```

### Quyết định kiến trúc — lớp render (cần chốt trước khi code)

| Phương án | Ưu | Nhược | Khuyến nghị |
|---|---|---|---|
| **OpenGL ES thuần** (theo mẫu `hello_ar_kotlin` của Google) | Kiểm soát tuyệt đối; không phụ thuộc lib bên thứ ba; hỗ trợ chính thức | Phải tự viết shader, text rendering, occlusion | ✅ **Chọn cái này** nếu team có người biết GL. Đáng công vì tính năng đo cần kiểm soát chính xác. |
| **SceneView / ARSceneView** (`io.github.sceneview`, dựa trên Google Filament) | API cao cấp, hỗ trợ Jetpack Compose, đỡ code render rất nhiều | Thư viện cộng đồng — rủi ro bảo trì, phải pin version | ⚠️ Chấp nhận được nếu cần ra nhanh, nhưng phải khoá version và có phương án thoát. |
| **Sceneform** (`com.google.ar.sceneform`) | — | **Google đã archive.** | ❌ **Không dùng.** Rất nhiều tutorial trên mạng còn dạy Sceneform — đừng copy. |
| Unity AR Foundation | Render 3D dễ nhất | Nhúng Unity vào app Android native rất nặng (dung lượng, thời gian khởi động, cầu nối) | ❌ Không, vì đã chọn Android native. |

---

### 3. Luồng UX (rất quan trọng — đây là nơi tính năng đo thường thất bại)

```
[Vào tính năng]
     │
     ├─► Kiểm tra tier  ──► UNSUPPORTED ──► Màn hình giải thích + nhập tay
     │
     ├─► Xin quyền CAMERA
     │
     ├─► requestInstall() Google Play Services for AR nếu cần
     │
[Giai đoạn KHỞI TẠO — không cho đo]
     │   Overlay: "Chậm rãi lia máy qua bề mặt cần đo"
     │   Điều kiện mở khoá (AND):
     │     • camera.trackingState == TRACKING
     │     • đã có ≥ 1 Plane (tier PLANE_ONLY) HOẶC depth image khả dụng (tier FULL)
     │     • trong 1.5 s gần nhất không có TrackingFailureReason
     │
[Giai đoạn NGẮM]
     │   • Reticle ở giữa màn hình, mỗi frame hit-test tại tâm màn hình
     │   • Reticle xoay theo pháp tuyến bề mặt → người dùng thấy được là đã "bắt" được mặt
     │   • Hiển thị realtime: khoảng cách tới điểm ngắm (để tự kiểm dải 0.3–5 m)
     │   • Reticle đổi màu: xanh = tin cậy, vàng = confidence thấp / quá xa, đỏ = không bắt được
     │
[Giai đoạn ĐO]
     │   Chạm/nhấn nút → tạo Anchor tại điểm → vẽ điểm
     │   Điểm thứ 2 → vẽ đoạn thẳng + nhãn số đo, cập nhật mỗi frame theo pose anchor
     │   Cho phép: undo, xoá điểm, kéo điểm để tinh chỉnh, đổi đơn vị
     │
[Giai đoạn KẾT QUẢ]
     └─► Chụp ảnh có annotation + số đo + mức tin cậy → lưu/chia sẻ
```

### Quy tắc UX bắt buộc (rút ra từ hạn chế phần cứng)

1. **Không bao giờ hiện số đo mà không kèm mức tin cậy.** Người dùng phải biết đây là *ước lượng*, không phải thước cặp.
2. **Reticle giữa màn hình, không phải chạm tuỳ ý.** Người dùng ngắm bằng cách di chuyển máy → chính xác hơn nhiều so với chạm bằng ngón tay lên vùng nhỏ trên màn hình.
3. **Hiển thị khoảng cách camera→điểm liên tục.** Đây là cách rẻ nhất để dạy người dùng đứng ở cự ly đúng.
4. **Cảnh báo chủ động khi bề mặt xấu.** Confidence thấp → gợi ý "đặt tờ giấy có chữ / vật có hoạ tiết lên bề mặt".
5. **Onboarding 3 màn hình, chỉ hiện lần đầu.** Lia máy → ngắm → chạm.

---

### 4. Thuật toán M1 — đo khoảng cách 2 điểm

### 4.1. Ưu tiên chọn trackable (chốt cứng)

```
Với mỗi hitTest, chọn kết quả đầu tiên thoả (theo thứ tự ưu tiên):
  1. DepthPoint                                    (chỉ tier FULL)
  2. Plane  — VÀ  plane.isPoseInPolygon(hitPose)    (loại hit ngoài biên mặt phẳng)
  3. Point  — VÀ  point.orientationMode == ESTIMATED_SURFACE_NORMAL
  4. Point  (bất kỳ)                                → gán confidence = LOW
  KHÔNG BAO GIỜ: InstantPlacementPoint
Nếu không có gì → reticle đỏ, không cho chạm.
```

### 4.2. Tính khoảng cách

```
d = ‖ anchorB.pose.translation − anchorA.pose.translation ‖₂       (đơn vị: mét)
```

Chi tiết bắt buộc:
- **Lấy pose từ `Anchor`, không phải từ `HitResult` đã lưu.** Anchor được ARCore tinh chỉnh dần → số đo *tự tốt lên* khi người dùng lia thêm. Đây là "mẹo miễn phí" quan trọng nhất cho độ chính xác.
- Cập nhật lại số đo **mỗi frame** (hoặc mỗi 100 ms) từ pose anchor hiện tại.
- Chỉ tính khi cả 2 anchor có `trackingState == TRACKING`. Nếu `PAUSED` → hiện số đo cuối cùng dạng mờ + icon cảnh báo.

### 4.3. Biến thể "đo trên mặt phẳng" (khuyến nghị bật mặc định)

Khi cả 2 điểm nằm trên cùng một `Plane`, **chiếu cả 2 xuống mặt phẳng đó trước khi tính**. Việc này loại bỏ thành phần sai số theo pháp tuyến (thường là nguồn sai số lớn nhất khi đo cạnh bàn/sàn):

```
n = plane.centerPose.yAxis            // pháp tuyến
p' = p − n · dot(p − planeOrigin, n)
d  = ‖ pB' − pA' ‖₂
```

---

### 5. Thuật toán M3 — đo diện tích mặt phẳng

1. Người dùng đặt ≥ 3 điểm trên cùng một `Plane`.
2. Dựng hệ toạ độ 2D cục bộ của mặt phẳng: `origin = plane.centerPose.translation`, `u = plane.centerPose.xAxis`, `v = plane.centerPose.zAxis`.
3. Chiếu từng điểm: `(x_i, y_i) = ( dot(p_i − origin, u), dot(p_i − origin, v) )`.
4. Diện tích bằng công thức shoelace:
   `A = ½ · | Σ (x_i · y_{i+1} − x_{i+1} · y_i) |`  (m²)
5. Chu vi = tổng độ dài các cạnh.

Cảnh báo nếu đa giác tự cắt (self-intersecting) — kết quả shoelace sẽ vô nghĩa.

---

### 6. Thuật toán M4 — đo vật thể D×R×C, phương án THỦ CÔNG (làm trước)

Đây là phương án **nên giao ở pha 2**: chạy được cả tier PLANE_ONLY, người dùng hiểu được, dễ debug.

```
Bước 1 — Xác định mặt nền
   Người dùng ngắm vào sàn/bàn dưới chân vật thể → app chốt một Plane làm mặt nền.
   Lấy: origin O, pháp tuyến n (= plane.centerPose.yAxis)

Bước 2 — Cạnh thứ nhất (chiều dài)
   Người dùng chạm 2 điểm A, B tại 2 góc đáy vật thể, dọc theo một cạnh.
   Chiếu A, B xuống mặt nền → A', B'
   L = ‖B' − A'‖
   Trục chính:  e1 = normalize(B' − A')

Bước 3 — Cạnh thứ hai (chiều rộng), tự động vuông góc
   Trục phụ:    e2 = normalize( cross(n, e1) )     // vuông góc với e1, nằm trong mặt nền
   Người dùng chạm điểm C tại góc đáy còn lại (hoặc kéo tay cầm trên khối hộp trực quan)
   W = | dot(C' − A', e2) |

Bước 4 — Chiều cao
   Người dùng ngắm lên mặt trên vật thể → hit-test (DepthPoint nếu có, nếu không thì
   bắn tia dọc trục n từ tâm đáy) → điểm T
   H = dot(T − A', n)          // luôn dùng dot với n, KHÔNG dùng hiệu chiều cao Y thô

Bước 5 — Kết quả
   Thể tích V = L × W × H
   Vẽ khối hộp wireframe từ (A', e1, e2, n, L, W, H) để người dùng xác nhận trực quan
   → CHO PHÉP KÉO TAY CẦM để tinh chỉnh (đây là điều làm nên độ chính xác thực tế)
```

**Vì sao "vuông góc bắt buộc"** (bước 3 dùng `cross` thay vì để user chạm tự do): người dùng gần như không bao giờ chạm được 2 cạnh thật vuông góc. Ép vuông góc cho khối hộp *hợp lý về hình học* và giảm sai số tích luỹ. Đây cũng là cách các app đo thương mại hoạt động.

---

### 7. Thuật toán M5 — bounding box BÁN TỰ ĐỘNG từ Raw Depth (pha 3, cần R&D)

Chỉ chạy ở tier FULL. Đây là phần có rủi ro kỹ thuật cao nhất — **phải làm prototype trước khi commit deadline.**

```
Bước 1 — Thu thập
   Bật DepthMode.AUTOMATIC.
   Người dùng khoanh vùng ROI (hình chữ nhật hoặc lasso) quanh vật thể trên màn hình.
   Trong 2–4 giây, người dùng đi vòng/lia quanh vật; app thu N frame (đề xuất N = 15–40).
   Mỗi frame: acquireRawDepthImage16Bits() + acquireRawDepthConfidenceImage()
              + camera.pose + camera.imageIntrinsics

Bước 2 — Unproject → point cloud world-space
   Với mỗi pixel (u,v) trong ROI (đã map sang hệ toạ độ depth image
   bằng Frame.transformCoordinates2d):
       nếu confidence(u,v) < CONF_MIN (đề xuất 100)        → bỏ
       z = depth_mm(u,v) / 1000                             (mét)
       nếu z < 0.3 hoặc z > 5.0                             → bỏ
       x_cam = (u − cx) · z / fx
       y_cam = (v − cy) · z / fy
       p_cam = ( x_cam, −y_cam, −z )         // đổi sang hệ camera ARCore (Y lên, nhìn theo −Z)
       p_world = camera.pose.transformPoint(p_cam)
   Gộp point cloud của tất cả frame (đều đã ở world-space nên gộp trực tiếp được).

Bước 3 — Downsample
   Voxel grid 5 mm → giảm số điểm, đồng thời làm mượt nhiễu.
   Mục tiêu: 5k–50k điểm. Trên nhiều hơn thế thì chậm mà không lợi.

Bước 4 — Loại mặt nền
   Dùng Plane mà ARCore đã phát hiện (ưu tiên — chính xác và rẻ)
   HOẶC RANSAC plane fit nếu không có plane.
   Bỏ mọi điểm có | dot(p − O, n) | < 15 mm  → đó là sàn/mặt bàn, không phải vật.
   Bỏ mọi điểm có dot(p − O, n) < 0          → nằm dưới mặt nền, chắc chắn là nhiễu.

Bước 5 — Loại nhiễu thống kê
   Statistical Outlier Removal: với mỗi điểm, tính khoảng cách trung bình tới k = 20
   điểm gần nhất; bỏ điểm có khoảng cách > mean + 1.5·σ.
   (Bước này quyết định chất lượng — bỏ qua thì kích thước sẽ bị phóng đại.)

Bước 6 — Oriented Bounding Box
   Chiếu toàn bộ điểm xuống mặt nền → tập điểm 2D.
   Tìm hướng chính bằng PCA trên tập 2D (eigenvector của ma trận hiệp phương sai)
   → e1, e2 (vuông góc nhau, nằm trong mặt nền).
   L = percentile(dot(p, e1), 98) − percentile(dot(p, e1), 2)
   W = percentile(dot(p, e2), 98) − percentile(dot(p, e2), 2)
   H = percentile(dot(p − O, n), 98)
   → DÙNG PERCENTILE 2/98, KHÔNG DÙNG min/max. min/max cực kỳ nhạy với 1 điểm nhiễu.
   (Nếu chất lượng PCA không đạt: thay bằng rotating calipers trên convex hull 2D
    để tìm hình chữ nhật bao nhỏ nhất — chính xác hơn, đắt hơn.)

Bước 7 — Xác nhận của người dùng
   Vẽ khối hộp lên vật thật, CHO PHÉP kéo tay cầm để chỉnh.
   Không bao giờ chốt số đo tự động mà không cho người dùng sửa.
```

### Ghi chú R&D
- Phần từ bước 3 đến 6 là tính toán số học nặng → nếu Kotlin chậm, chuyển sang **C++ qua NDK** (ARCore có SDK NDK, và có `ArCoreNativeInterop` để chia sẻ đối tượng ARCore giữa Java và C++).
- Tài liệu Raw Depth của Google **không cung cấp công thức unproject** — công thức ở bước 2 suy ra từ `CameraIntrinsics`. **Dấu của thành phần y/z phải được xác minh bằng thực nghiệm** trên frame đầu tiên (xem `04`, mục 5, có test kiểm chứng).
- Tham khảo có sẵn: **ARCore Depth Lab** (Google, dành cho Unity) — có scene *Oriented 3D Reticle* (raycast + pháp tuyến bề mặt từ depth) và *Point Cloud* (dựng point cloud từ raw depth map). Không có sample "Measure" chính thức, nhưng 2 scene này là nguồn tham khảo thuật toán tốt nhất.

---

### 8. Cấu hình Session đề xuất

| Thiết lập | Giá trị | Lý do |
|---|---|---|
| `depthMode` | `AUTOMATIC` nếu `isDepthModeSupported`, ngược lại `DISABLED` | Mặc định là tắt, phải bật |
| `planeFindingMode` | `HORIZONTAL_AND_VERTICAL` | Cần cả sàn/bàn và tường |
| `focusMode` | `AUTO` | Ảnh nét hơn → nhiều feature point hơn → tracking tốt hơn. (Mặc định của một số cấu hình là FIXED.) |
| `updateMode` | `LATEST_CAMERA_IMAGE` | Vòng lặp render không bị block |
| `instantPlacementMode` | `DISABLED` | Tránh nguy cơ vô tình lấy số đo từ pose phỏng đoán |
| `lightEstimationMode` | `DISABLED` | Không cần, tiết kiệm CPU/nhiệt |
| `cloudAnchorMode`, `geospatialMode` | `DISABLED` | Không dùng |

---


# Phần 4 — Code mẫu Kotlin

*(nguồn: `04-code-mau-kotlin.md`)*

> Toàn bộ code dưới đây dùng đúng tên class/method theo tài liệu tham chiếu chính thức của ARCore.
> Phần nào là *suy luận/đề xuất* (không có trong docs) đều được đánh dấu `// ⚠️ CẦN VERIFY`.

---

### 0. Thiết lập project

`build.gradle` (module):
```gradle
android {
    defaultConfig {
        minSdk = 24          // Android 7.0 — yêu cầu của ARCore
    }
}

dependencies {
    // ⚠️ CẦN VERIFY version mới nhất trước khi code.
    // Bản quan sát được trên GitHub google-ar/arcore-android-sdk: 1.54.0
    implementation("com.google.ar:core:1.54.0")
}
```

`AndroidManifest.xml` — **AR Optional** (khuyến nghị: AR là một tính năng trong app lớn):
```xml
<uses-permission android:name="android.permission.CAMERA" />

<application ...>
    <meta-data android:name="com.google.ar.core" android:value="optional" />
</application>
```

**AR Required** (app chỉ để đo):
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />

<application ...>
    <meta-data android:name="com.google.ar.core" android:value="required" />
</application>
```

Chỉ phát hành cho máy có Depth (dùng thận trọng — cắt rất nhiều thiết bị):
```xml
<uses-feature android:name="com.google.ar.depth" />
```

---

### 1. Phát hiện năng lực thiết bị & phân tier

```kotlin
enum class MeasureTier { FULL, PLANE_ONLY, UNSUPPORTED }

object DeviceCapability {

    /** Gọi trong onResume(). Trả về null nghĩa là đang chờ user cài Play Services for AR. */
    fun resolveTier(activity: Activity, userRequestedInstall: Boolean): MeasureTier? {
        val availability = ArCoreApk.getInstance().checkAvailability(activity)

        if (availability.isTransient) {
            // ARCore đang truy vấn — thử lại ở frame sau (~200ms)
            return null
        }
        if (!availability.isSupported) {
            return MeasureTier.UNSUPPORTED
        }

        // Đảm bảo Google Play Services for AR đã cài & đủ mới
        when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return null // sẽ quay lại onResume
            ArCoreApk.InstallStatus.INSTALLED -> Unit
        }

        // Tạo session tạm để hỏi năng lực Depth.
        // LƯU Ý: Session KHÔNG implement Closeable → không dùng được `use {}`.
        // Phải gọi close() thủ công trong finally, nếu không sẽ rò một lượng lớn native heap.
        var probe: Session? = null
        return try {
            probe = Session(activity)
            if (probe.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) MeasureTier.FULL
            else                                                        MeasureTier.PLANE_ONLY
        } finally {
            probe?.close()
        }
    }
}
```

> ⚠️ `Session` **không** implement `Closeable` — bắt buộc `try/finally { probe.close() }`. `Session.close()` giải phóng "một lượng đáng kể native heap memory"; quên gọi là rò bộ nhớ nặng.
> Trên thực tế nên **tái sử dụng session chính** thay vì tạo session probe, để tránh chi phí khởi tạo camera hai lần: tạo session một lần, gọi `isDepthModeSupported()` trên nó rồi `configure()` luôn.

---

### 2. Cấu hình Session

```kotlin
fun configureForMeasurement(session: Session): Boolean {
    val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

    val config = session.config          // hoặc Config(session)
    config
        .setDepthMode(
            if (depthSupported) Config.DepthMode.AUTOMATIC
            else                Config.DepthMode.DISABLED
        )
        .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
        .setFocusMode(Config.FocusMode.AUTO)
        .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
        .setInstantPlacementMode(Config.InstantPlacementMode.DISABLED)
        .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        .setCloudAnchorMode(Config.CloudAnchorMode.DISABLED)

    session.configure(config)
    return depthSupported
}
```

> ⚠️ **Bẫy cú pháp Kotlin**: các setter của `Config` theo kiểu **fluent builder** — chúng trả về `Config`, không trả về `void`. Vì vậy Kotlin **không** sinh ra property khả biến: `config.depthMode = ...` sẽ **không compile**. Phải gọi `config.setDepthMode(...)` (và chain được như trên). Getter thì vẫn dùng được dạng property: `config.depthMode` đọc bình thường.

---

### 3. Hit-test có ưu tiên trackable — trái tim của việc chọn điểm đo

```kotlin
enum class HitQuality { HIGH, MEDIUM, LOW }

data class MeasureHit(
    val hitResult: HitResult,
    val quality: HitQuality,
    val distanceMeters: Float,
    val source: String
)

/**
 * Hit-test theo thứ tự ưu tiên: DepthPoint > Plane (trong biên) > Point (có pháp tuyến) > Point.
 * KHÔNG BAO GIỜ trả về InstantPlacementPoint — pose/scale của nó chỉ là phỏng đoán.
 */
fun pickMeasurePoint(frame: Frame, screenX: Float, screenY: Float): MeasureHit? {
    if (frame.camera.trackingState != TrackingState.TRACKING) return null

    // Danh sách đã được ARCore sắp xếp theo khoảng cách tăng dần từ camera
    val hits: List<HitResult> = frame.hitTest(screenX, screenY)

    var planeHit: MeasureHit? = null
    var orientedPointHit: MeasureHit? = null
    var anyPointHit: MeasureHit? = null

    for (hit in hits) {
        val trackable = hit.trackable
        val dist = hit.distance   // mét, tính từ camera

        when {
            trackable is DepthPoint -> {
                // Ưu tiên cao nhất: dùng chiều sâu toàn scene, đặt được lên bề mặt bất kỳ.
                return MeasureHit(hit, HitQuality.HIGH, dist, "DepthPoint")
            }

            trackable is Plane -> {
                val inside = trackable.isPoseInPolygon(hit.hitPose)
                if (inside && trackable.trackingState == TrackingState.TRACKING && planeHit == null) {
                    planeHit = MeasureHit(hit, HitQuality.HIGH, dist, "Plane")
                }
            }

            trackable is Point -> {
                if (trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                    if (orientedPointHit == null)
                        orientedPointHit = MeasureHit(hit, HitQuality.MEDIUM, dist, "Point(oriented)")
                } else {
                    if (anyPointHit == null)
                        anyPointHit = MeasureHit(hit, HitQuality.LOW, dist, "Point")
                }
            }

            // trackable is InstantPlacementPoint -> bỏ qua có chủ ý
        }
    }

    return planeHit ?: orientedPointHit ?: anyPointHit
}
```

### Cổng chất lượng theo khoảng cách

```kotlin
private const val MIN_RANGE_M = 0.30f
private const val MAX_RANGE_M = 5.00f   // giới hạn trên của dải tối ưu Depth API

fun gate(hit: MeasureHit): HitQuality = when {
    hit.distanceMeters !in MIN_RANGE_M..MAX_RANGE_M -> HitQuality.LOW
    hit.distanceMeters > 3.0f && hit.quality == HitQuality.HIGH -> HitQuality.MEDIUM
    else -> hit.quality
}
```

---

### 4. Đo khoảng cách 2 điểm

```kotlin
class MeasurePoint(val anchor: Anchor, val quality: HitQuality) {
    val isValid: Boolean get() = anchor.trackingState == TrackingState.TRACKING
    val position: FloatArray get() = anchor.pose.translation   // {x, y, z} mét, world-space
}

class TwoPointMeasurement {
    private var a: MeasurePoint? = null
    private var b: MeasurePoint? = null

    fun addPoint(hit: MeasureHit) {
        // createAnchor() — ARCore sẽ TINH CHỈNH pose của anchor theo thời gian.
        // Đây là lý do phải dùng Anchor thay vì lưu hitPose tĩnh.
        val point = MeasurePoint(hit.hitResult.createAnchor(), gate(hit))
        when {
            a == null -> a = point
            b == null -> b = point
            else -> { a?.anchor?.detach(); a = b; b = point }   // giải phóng anchor cũ
        }
    }

    /** Gọi mỗi frame. Trả về mét, hoặc null nếu chưa đủ điểm / mất tracking. */
    fun currentDistanceMeters(): Float? {
        val pa = a ?: return null
        val pb = b ?: return null
        if (!pa.isValid || !pb.isValid) return null
        return distance(pa.position, pb.position)
    }

    fun clear() {
        a?.anchor?.detach(); b?.anchor?.detach()
        a = null; b = null
    }
}

fun distance(p: FloatArray, q: FloatArray): Float {
    val dx = p[0] - q[0]; val dy = p[1] - q[1]; val dz = p[2] - q[2]
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}
```

> **Nhớ `detach()`.** Tài liệu ARCore nói rõ: *"Để giảm chi phí CPU, hãy tái sử dụng anchor khi có thể và detach anchor không còn dùng."* Một phiên đo dài mà rò anchor sẽ làm sập fps.

### Biến thể: chiếu lên mặt phẳng để giảm sai số

```kotlin
/** Chiếu điểm p lên mặt phẳng của `plane` — loại bỏ sai số theo pháp tuyến. */
fun projectOntoPlane(p: FloatArray, plane: Plane): FloatArray {
    val c = plane.centerPose
    val origin = c.translation
    val n = c.yAxis                    // pháp tuyến của Plane trong ARCore là trục Y
    val d = (p[0]-origin[0])*n[0] + (p[1]-origin[1])*n[1] + (p[2]-origin[2])*n[2]
    return floatArrayOf(p[0] - n[0]*d, p[1] - n[1]*d, p[2] - n[2]*d)
}
```

---

### 5. Đọc Depth & unproject pixel → điểm 3D world-space

### 5.1. Lấy depth image + confidence

```kotlin
/** Trả về Pair(depthImage, confidenceImage). Caller PHẢI close cả hai. */
fun acquireRawDepth(frame: Frame): Pair<Image, Image>? {
    return try {
        val depth = frame.acquireRawDepthImage16Bits()
        val conf  = frame.acquireRawDepthConfidenceImage()
        depth to conf
    } catch (e: NotYetAvailableException) {
        // Chưa có depth: người dùng cần di chuyển máy thêm, hoặc scene chưa đủ feature point
        null
    }
}
```

### 5.2. Đọc giá trị millimet tại một pixel (snippet chính thức của Google)

```kotlin
fun getMillimetersDepth(depthImage: Image, x: Int, y: Int): Int {
    val plane = depthImage.planes[0]
    val byteIndex = x * plane.pixelStride + y * plane.rowStride
    val buffer = plane.buffer.order(ByteOrder.nativeOrder())
    return java.lang.Short.toUnsignedInt(buffer.getShort(byteIndex))
}
```

Đọc confidence (format Y8, 0..255):
```kotlin
fun getConfidence(confImage: Image, x: Int, y: Int): Int {
    val plane = confImage.planes[0]
    val byteIndex = x * plane.pixelStride + y * plane.rowStride
    return plane.buffer.get(byteIndex).toInt() and 0xFF
}
```

> ⚠️ Giá trị depth của ARCore là **uint16 millimet thuần** (dùng `Short.toUnsignedInt`).
> **Đừng** áp dụng mặt nạ `0x1FFF` như khi xử lý format `DEPTH16` thô của Android Camera2 — đó là chuyện khác.

### 5.3. Đổi hệ toạ độ màn hình → hệ toạ độ depth image

Depth image **không cùng kích thước và không cùng orientation** với ảnh camera. Không được tự tính tay.

```kotlin
fun screenToDepthPixel(
    frame: Frame,
    screenX: Float, screenY: Float,
    viewWidth: Int, viewHeight: Int,
    depthWidth: Int, depthHeight: Int
): Pair<Int, Int>? {
    val viewNorm = floatArrayOf(screenX / viewWidth, screenY / viewHeight)
    val texNorm  = FloatArray(2)
    frame.transformCoordinates2d(
        Coordinates2d.VIEW_NORMALIZED, viewNorm,
        Coordinates2d.TEXTURE_NORMALIZED, texNorm
    )
    // Toạ độ ngoài vùng hợp lệ sẽ trả về giá trị âm
    if (texNorm[0] < 0f || texNorm[1] < 0f) return null
    if (texNorm[0] > 1f || texNorm[1] > 1f) return null

    val u = (texNorm[0] * depthWidth).toInt().coerceIn(0, depthWidth - 1)
    val v = (texNorm[1] * depthHeight).toInt().coerceIn(0, depthHeight - 1)
    return u to v
}
```

> `Frame.transformCoordinates2d` hỗ trợ các hệ: `IMAGE_PIXELS`, `TEXTURE_NORMALIZED`, `VIEW`, `VIEW_NORMALIZED`, `OPENGL_NORMALIZED_DEVICE_COORDINATES`.
> Chọn cặp phù hợp với nguồn toạ độ của bạn.

### 5.4. Unproject: (pixel, depth) → điểm 3D world-space

```kotlin
/**
 * Scale intrinsics của ảnh camera về kích thước depth image.
 * Depth image nhỏ hơn ảnh camera rất nhiều (thường ~160x120 .. 640x480).
 */
data class DepthIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float)

fun scaleIntrinsics(camera: Camera, depthWidth: Int, depthHeight: Int): DepthIntrinsics {
    val intr = camera.imageIntrinsics
    val (fxI, fyI) = intr.focalLength.let { it[0] to it[1] }
    val (cxI, cyI) = intr.principalPoint.let { it[0] to it[1] }
    val (wI, hI)   = intr.imageDimensions.let { it[0] to it[1] }

    val sx = depthWidth.toFloat()  / wI
    val sy = depthHeight.toFloat() / hI
    return DepthIntrinsics(fxI * sx, fyI * sy, cxI * sx, cyI * sy)
}

/**
 * (u,v) tính theo pixel của depth image; depthMm là giá trị đọc từ depth image.
 * Trả về toạ độ WORLD-SPACE, đơn vị mét.
 *
 * Lưu ý: giá trị depth của ARCore là hình chiếu lên TRỤC QUANG CHÍNH (thành phần z
 * trong hệ camera), KHÔNG phải khoảng cách theo tia. Công thức dưới đây đúng với
 * định nghĩa đó.
 *
 * ⚠️ CẦN VERIFY: dấu của y và z phụ thuộc quy ước hệ camera (ARCore: +X phải, +Y lên,
 * nhìn theo −Z; còn v của ảnh tăng xuống dưới). Chạy test ở mục 5.5 để chốt dấu.
 */
fun unprojectToWorld(
    u: Int, v: Int, depthMm: Int,
    intr: DepthIntrinsics,
    cameraPose: Pose
): FloatArray? {
    if (depthMm <= 0) return null
    val z = depthMm / 1000f                     // mét
    if (z < 0.3f || z > 5.0f) return null       // ngoài dải tin cậy

    val xCam =  (u - intr.cx) * z / intr.fx
    val yCam = -(v - intr.cy) * z / intr.fy     // đảo dấu: ảnh v xuống, camera Y lên
    val zCam = -z                               // ARCore camera nhìn theo −Z

    return cameraPose.transformPoint(floatArrayOf(xCam, yCam, zCam))
}
```

### 5.5. Test bắt buộc để chốt dấu (chạy 1 lần khi tích hợp)

```kotlin
/**
 * Sanity check: unproject điểm giữa depth image, rồi so với hit-test tại giữa màn hình.
 * Hai kết quả phải lệch < ~5 cm. Nếu lệch lớn (nhất là lệch theo trục dọc hoặc
 * ra sau lưng camera) → dấu của yCam/zCam đang sai.
 */
fun verifyUnprojection(frame: Frame, viewW: Int, viewH: Int): String {
    val (depth, conf) = acquireRawDepth(frame) ?: return "no depth yet"
    depth.use { d -> conf.use { _ ->
        val intr = scaleIntrinsics(frame.camera, d.width, d.height)
        val u = d.width / 2; val v = d.height / 2
        val mm = getMillimetersDepth(d, u, v)
        val fromDepth = unprojectToWorld(u, v, mm, intr, frame.camera.pose)
            ?: return "depth out of range"

        val fromHit = frame.hitTest(viewW / 2f, viewH / 2f)
            .firstOrNull()?.hitPose?.translation ?: return "no hit"

        return "delta = %.3f m (kỳ vọng < 0.05)".format(distance(fromDepth, fromHit))
    } }
}
```

> `Image` implement `AutoCloseable`; luôn `use {}` hoặc `close()` trong `finally`.
> **Rò `Image` là bug số 1 khi làm việc với Depth API** — sau vài chục frame, `acquire...()` sẽ ném exception vì hết buffer.

---

### 6. Bounding box: dựng OBB từ point cloud (khung xương)

```kotlin
data class ObbResult(
    val lengthM: Float, val widthM: Float, val heightM: Float,
    val volumeM3: Float,
    val originWorld: FloatArray,
    val axis1: FloatArray, val axis2: FloatArray, val normal: FloatArray,
    val pointCount: Int
)

fun buildObb(
    pointsWorld: List<FloatArray>,   // đã lọc confidence + range
    planeOrigin: FloatArray,
    planeNormal: FloatArray          // = plane.centerPose.yAxis
): ObbResult? {
    val n = planeNormal

    // 1) Bỏ điểm thuộc mặt nền và điểm nằm dưới mặt nền
    val above = pointsWorld.filter {
        val h = dot(sub(it, planeOrigin), n)
        h > 0.015f && h < 3.0f
    }
    if (above.size < 200) return null   // không đủ dữ liệu → yêu cầu quét thêm

    // 2) Chiếu xuống mặt phẳng → toạ độ 2D trong hệ (u, v) tuỳ ý của mặt phẳng
    val u0 = anyPerpendicular(n)
    val v0 = cross(n, u0)
    val pts2d = above.map { p ->
        val d = sub(p, planeOrigin)
        floatArrayOf(dot(d, u0), dot(d, v0))
    }

    // 3) PCA trên tập 2D → hướng chính
    val (e1_2d, e2_2d) = principalAxes2d(pts2d)

    // 4) Kích thước theo percentile (KHÔNG dùng min/max — quá nhạy với nhiễu)
    val proj1 = pts2d.map { it[0]*e1_2d[0] + it[1]*e1_2d[1] }.sorted()
    val proj2 = pts2d.map { it[0]*e2_2d[0] + it[1]*e2_2d[1] }.sorted()
    val heights = above.map { dot(sub(it, planeOrigin), n) }.sorted()

    val L = pct(proj1, 0.98f) - pct(proj1, 0.02f)
    val W = pct(proj2, 0.98f) - pct(proj2, 0.02f)
    val H = pct(heights, 0.98f)

    // 5) Đưa e1/e2 từ hệ 2D về world-space
    val axis1 = add(scale(u0, e1_2d[0]), scale(v0, e1_2d[1]))
    val axis2 = add(scale(u0, e2_2d[0]), scale(v0, e2_2d[1]))

    return ObbResult(L, W, H, L*W*H, planeOrigin, axis1, axis2, n, above.size)
}

fun pct(sortedAsc: List<Float>, p: Float): Float {
    if (sortedAsc.isEmpty()) return 0f
    val idx = ((sortedAsc.size - 1) * p).toInt().coerceIn(0, sortedAsc.size - 1)
    return sortedAsc[idx]
}
```

> `principalAxes2d` = eigenvector của ma trận hiệp phương sai 2×2 (giải tay được, không cần thư viện đại số).
> **Bản nâng cấp**: thay PCA bằng **rotating calipers trên convex hull 2D** để tìm hình chữ nhật bao có diện tích nhỏ nhất — chính xác hơn với vật không đối xứng.

---

### 7. Giám sát chất lượng tracking → thông điệp hướng dẫn

```kotlin
fun guidanceMessage(camera: Camera): String? {
    if (camera.trackingState == TrackingState.TRACKING) return null
    return when (camera.trackingFailureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT    -> "Môi trường quá tối — hãy bật thêm đèn"
        TrackingFailureReason.EXCESSIVE_MOTION      -> "Di chuyển máy chậm hơn"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Hãy hướng máy vào bề mặt có hoạ tiết rõ hơn"
        TrackingFailureReason.CAMERA_UNAVAILABLE    -> "Camera đang bị ứng dụng khác sử dụng"
        TrackingFailureReason.BAD_STATE             -> "Đang khôi phục AR…"
        TrackingFailureReason.NONE                  -> "Đang khởi tạo AR — hãy chậm rãi lia máy"
        else                                        -> "Đang khởi tạo AR — hãy chậm rãi lia máy"
    }
}
```

---

### 8. Ghi & phát lại session cho kiểm thử hồi quy

```kotlin
// GHI — chạy 1 lần trên máy chuẩn, tại hiện trường có ground truth đã đo bằng thước laser
fun startRecording(session: Session, file: File) {
    val cfg = RecordingConfig(session)
        .setMp4DatasetUri(Uri.fromFile(file))
        .setAutoStopOnPause(true)
    session.startRecording(cfg)
}

// PHÁT LẠI — chạy trên CI hoặc trên mọi máy trong ma trận thiết bị.
// ARCore xử lý MP4 y như feed camera thật → so được số đo giữa các máy trên CÙNG dữ liệu.
fun startPlayback(session: Session, file: File) {
    session.pause()
    session.setPlaybackDatasetUri(Uri.fromFile(file))
    session.resume()
}
```

MP4 ghi được gồm: video H.264 (ảnh CPU 640×480 dùng cho motion tracking, hoặc ảnh CPU độ phân giải cao nếu chọn), depth map từ cảm biến phần cứng nếu có, số đọc accelerometer + gyroscope, và metadata (phiên bản ARCore/SDK, device fingerprint).

**Đây là công cụ QA giá trị nhất trong toàn bộ dự án**: ghi 1 lần → test được mọi máy mà không phải mang cả ma trận thiết bị ra hiện trường mỗi lần.

---

### 9. Checklist các bug thường gặp

| Bug | Nguyên nhân | Cách sửa |
|---|---|---|
| `NotYetAvailableException` liên tục | Chưa có chuyển động / chưa đủ feature point | Gate UI đến khi depth khả dụng; hướng dẫn lia máy |
| Sau ~30 frame thì không lấy được depth nữa | **Rò `Image`** | `use {}` / `close()` trong `finally` cho MỌI `Image` |
| Số đo lệch hẳn sau khi xoay máy | Tự tính toạ độ depth image | Dùng `Frame.transformCoordinates2d()` |
| Số đo lệch nhiều ở rìa khung hình | Coi depth là khoảng cách theo tia | Depth là hình chiếu lên trục quang chính → dùng công thức unproject ở mục 5.4 |
| Vật thể "phình ra rồi co lại" | Đang dùng `InstantPlacementPoint` | Tắt `instantPlacementMode`; loại InstantPlacementPoint khỏi hit-test |
| fps sập sau vài phút | Rò anchor / xử lý point cloud mỗi frame | `detach()` anchor không dùng; giảm tần số xử lý point cloud (5 Hz) |
| Kích thước bị phóng đại 5–20% | Dùng min/max của point cloud | Dùng percentile 2/98 + statistical outlier removal |
| Số đo nhảy liên tục | Lưu `hitPose` tĩnh | Đọc pose từ `Anchor` mỗi frame |
| Đo tường trắng cho kết quả vô nghĩa | Confidence = 0 | Lọc theo confidence; cảnh báo người dùng |


---

### 10. Phụ lục: các tên API đã đối chiếu với tài liệu tham chiếu chính thức

Toàn bộ tên class/method/enum dùng trong tài liệu này đã được kiểm tra lại trên
`developers.google.com/ar/reference/java/com/google/ar/core/...`:

| API | Trạng thái | Ghi chú |
|---|---|---|
| `Plane.isPoseInPolygon(Pose)` | ✅ | |
| `Point.getOrientationMode()`, `Point.OrientationMode.ESTIMATED_SURFACE_NORMAL` | ✅ | Hằng còn lại: `INITIALIZED_TO_IDENTITY` |
| `Camera.getImageIntrinsics()`, `getTextureIntrinsics()` | ✅ | Dùng `imageIntrinsics` khi làm việc với ảnh CPU/depth |
| `Camera.getTrackingFailureReason()` | ✅ | `TrackingFailureReason` có **đúng 6** hằng: NONE, BAD_STATE, INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE |
| `HitResult.getDistance()` | ✅ | Trả về `float`, **đơn vị mét**, khoảng cách từ camera tới điểm hit |
| `HitResult.getHitPose()`, `createAnchor()`, `getTrackable()` | ✅ | |
| `Pose.getXAxis()/getYAxis()/getZAxis()` | ✅ | Trả về `float[3]` hướng của trục sau biến đổi |
| `Pose.getTranslation()`, `transformPoint()`, `compose()`, `inverse()`, `toMatrix()` | ✅ | **Đơn vị mét**, hệ thuận tay phải kiểu OpenGL |
| `Frame.acquireDepthImage16Bits()` | ✅ | `acquireDepthImage()` là bản **deprecated** |
| `Frame.acquireRawDepthImage16Bits()` | ✅ | `acquireRawDepthImage()` là bản **deprecated** |
| `Frame.acquireRawDepthConfidenceImage()` | ✅ | Format Y8, 0..255 |
| `Frame.transformCoordinates2d(Coordinates2d, float[], Coordinates2d, float[])` | ✅ | Có thêm overload nhận `FloatBuffer` |
| `Coordinates2d` | ✅ | Có **7** hằng: IMAGE_PIXELS, IMAGE_NORMALIZED, TEXTURE_TEXELS, TEXTURE_NORMALIZED, VIEW, VIEW_NORMALIZED, OPENGL_NORMALIZED_DEVICE_COORDINATES |
| `Session.isDepthModeSupported(Config.DepthMode)` | ✅ | |
| `Session.getConfig()` | ✅ | Có cả overload `getConfig(Config)` |
| `Session.close()` | ✅ | **`Session` KHÔNG implement `Closeable`** → không dùng `use {}` |
| `Session.startRecording(RecordingConfig)`, `stopRecording()`, `getRecordingStatus()` | ✅ | |
| `Session.setPlaybackDatasetUri(Uri)`, `getPlaybackStatus()` | ✅ | `setPlaybackDataset(String)` đã **deprecated** |
| `RecordingConfig(Session)`, `setMp4DatasetUri(Uri)`, `setAutoStopOnPause(boolean)`, `setRecordingRotation(int)`, `addTrack(Track)` | ✅ | `setMp4DatasetFilePath(String)` đã deprecated |
| `ArCoreApk.checkAvailability()`, `Availability.isTransient()/isSupported()/isUnknown()/isUnsupported()` | ✅ | |
| `ArCoreApk.requestInstall()`, `InstallStatus.INSTALLED/INSTALL_REQUESTED` | ✅ | |
| `Anchor.detach()`, `Anchor.getTrackingState()` | ✅ | |
| `Config.FocusMode` | ✅ | AUTO, FIXED |
| `Config.PlaneFindingMode` | ✅ | DISABLED, HORIZONTAL, VERTICAL, HORIZONTAL_AND_VERTICAL |
| `Config.UpdateMode` | ✅ | BLOCKING, LATEST_CAMERA_IMAGE |
| `Config.InstantPlacementMode` | ✅ | DISABLED, LOCAL_Y_UP |
| `Config.LightEstimationMode` | ✅ | DISABLED, AMBIENT_INTENSITY, ENVIRONMENTAL_HDR |
| `Config.CloudAnchorMode` | ✅ | DISABLED, ENABLED |
| `Config` setters | ⚠️ | **Fluent builder** (trả về `Config`) → Kotlin không sinh property khả biến; phải gọi `setXxx(...)` |
| Công thức unproject depth → 3D | ⚠️ | **Không có trong tài liệu Google** — suy ra từ `CameraIntrinsics`; dấu của y/z phải verify bằng test ở mục 5.5 |

---


# Phần 5 — Độ chính xác & kế hoạch kiểm thử

*(nguồn: `05-do-chinh-xac-va-kiem-thu.md`)*

### 1. Sự thật cần nói thẳng ngay từ đầu

> **Google không công bố bất kỳ con số độ chính xác nào cho việc đo bằng ARCore.**
> Tài liệu chỉ nêu Depth API có dải tối ưu 0.5–5 m và cảnh báo bề mặt ít hoạ tiết cho kết quả *"không chính xác"*.

Mọi con số bạn thấy trên các blog kỹ thuật đều là quan sát riêng lẻ, không phải chuẩn. Một blog triển khai thực tế nói thẳng: *thước dây/thước cứng cho kết quả chính xác hơn; app AR chỉ cho ước lượng gần đúng*, và độ chính xác *"phụ thuộc điều kiện ánh sáng, chất lượng camera và loại vật thể được đo."*

**Hệ quả cho sản phẩm:**
1. **Không đặt cam kết độ chính xác vào marketing** trước khi có số đo bench test nội bộ.
2. **Không nhắm vào use case cần độ chính xác cao** (gia công, cắt kính, may đo) bằng AR đơn thuần.
3. Use case phù hợp: ước lượng kích thước nội thất, kiểm tra vật có vừa không gian, ước lượng kích thước thùng hàng/logistics, ước lượng vật liệu, ghi chú kích thước sơ bộ tại hiện trường.

---

### 2. Cây nguồn sai số

```
Sai số cuối cùng
├── Sai số scale toàn cục (drift của SLAM)
│     • Nguyên nhân: fusion camera+IMU tích luỹ lệch theo thời gian/khoảng di chuyển
│     • Biểu hiện: mọi số đo cùng lệch theo một hướng; đo càng dài lệch càng nhiều
│     • Giảm thiểu: dùng Anchor (ARCore tự tinh chỉnh); phiên đo ngắn; lia lại để loop-closure
│
├── Sai số định vị điểm (lớn nhất trong thực tế)
│     • Nguồn A: chiều sâu sai — bề mặt ít hoạ tiết, ngoài dải 0.5–5m, scene động
│     • Nguồn B: người dùng chạm sai chỗ — 1 pixel ở 3 m ≈ vài mm..cm ngoài thực tế
│     • Nguồn C: điểm đo thật nằm ở góc/cạnh, nơi depth luôn nhiễu nhất
│     • Giảm thiểu: reticle giữa màn hình; lọc confidence; median nhiều frame;
│                   snap vào biên mặt phẳng; cho kéo tinh chỉnh
│
├── Sai số hình học (chỉ với bounding box)
│     • Nguồn: mặt nền lệch, trục chính chọn sai, min/max thay vì percentile
│     • Giảm thiểu: ép vuông góc, percentile 2/98, outlier removal
│
└── Sai số môi trường
      • Ánh sáng yếu, chuyển động nhanh, bề mặt phản chiếu/trong suốt
      • Giảm thiểu: cổng chất lượng + hướng dẫn chủ động, không cho đo khi chất lượng xấu
```

---

### 3. Kỹ thuật giảm sai số — xếp theo tỉ lệ lợi ích/chi phí

| # | Kỹ thuật | Chi phí | Lợi ích | Ưu tiên |
|---|---|---|---|---|
| 1 | **Dùng `Anchor` và đọc pose mỗi frame** (không lưu `hitPose` tĩnh) | ~0 | Cao — ARCore tự tinh chỉnh pose | 🔴 P0 |
| 2 | **Reticle giữa màn hình** thay vì chạm tự do | Thấp | Rất cao — loại bỏ sai số ngón tay | 🔴 P0 |
| 3 | **Cổng chất lượng**: chặn đo khi ngoài 0.3–5 m, khi mất tracking, khi confidence thấp | Thấp | Cao — loại các số đo rác | 🔴 P0 |
| 4 | **Ưu tiên DepthPoint → Plane → Point**, loại InstantPlacementPoint | Thấp | Cao | 🔴 P0 |
| 5 | **Median/EMA của reticle qua 5–10 frame** | Thấp | Trung bình — reticle ổn định, người dùng chạm chính xác hơn | 🟠 P1 |
| 6 | **Chiếu 2 điểm cùng mặt phẳng xuống mặt phẳng** trước khi tính | Thấp | Trung bình–Cao khi đo cạnh phẳng | 🟠 P1 |
| 7 | **Cho kéo tay cầm để tinh chỉnh** sau khi đặt điểm | Trung bình | **Rất cao** — người dùng tự sửa được sai số còn lại | 🟠 P1 |
| 8 | **Snap vào biên/góc mặt phẳng** khi reticle ở gần (< 3 cm) | Trung bình | Cao khi đo đồ nội thất, hộp | 🟠 P1 |
| 9 | **Percentile 2/98 + statistical outlier removal** cho point cloud | Trung bình | Rất cao cho bounding box | 🟠 P1 (pha 3) |
| 10 | **Gộp nhiều frame** khi dựng point cloud (15–40 frame từ nhiều góc) | Cao | Cao | 🟡 P2 |
| 11 | **Hiệu chuẩn tuỳ chọn bằng Augmented Images**: người dùng in một marker có kích thước biết trước, đặt cạnh vật → app suy ra hệ số hiệu chỉnh scale | Cao | Cao nhưng chỉ cho user chuyên nghiệp | 🟡 P2 |

---

### 4. Tiêu chí chấp nhận đề xuất

> Đây là **đề xuất của bộ tài liệu này**, không phải cam kết của ARCore. Chốt lại sau vòng bench test đầu tiên.

### M1 — Đo khoảng cách 2 điểm

| Điều kiện | Sai số tuyệt đối cho phép | Sai số tương đối cho phép |
|---|---|---|
| 0.3–1.0 m, bề mặt có hoạ tiết, sáng tốt | ≤ 1.5 cm | ≤ 3 % |
| 1.0–3.0 m, bề mặt có hoạ tiết, sáng tốt | ≤ 3 cm | ≤ 2 % |
| 3.0–5.0 m, bề mặt có hoạ tiết, sáng tốt | ≤ 8 cm | ≤ 3 % |
| Bề mặt ít hoạ tiết / sáng yếu | **Không cam kết** — phải hiện cảnh báo cho người dùng | — |

**Độ lặp lại (repeatability)**: đo 5 lần cùng một đoạn → độ lệch chuẩn ≤ ½ ngưỡng sai số ở trên. *Chỉ số này quan trọng không kém độ chính xác*: người dùng phát hiện ngay nếu đo 2 lần cho 2 số khác nhau.

### M4/M5 — Bounding box vật thể

| Điều kiện | Sai số từng chiều | Sai số thể tích |
|---|---|---|
| Vật hình hộp, 20–100 cm, đặt trên mặt phẳng có hoạ tiết | ≤ 5 % hoặc ≤ 3 cm (lấy giá trị lớn hơn) | ≤ 15 % |
| Vật không phải hình hộp (bo cong, không đối xứng) | ≤ 10 % | ≤ 30 % |
| Vật < 10 cm | **Ngoài phạm vi hỗ trợ** — chặn trong UI | — |

---

### 5. Kế hoạch bench test

### 5.1. Chuẩn bị ground truth

| Hạng mục | Chi tiết |
|---|---|
| Thiết bị đối chiếu | **Thước laser** (sai số ±1.5 mm) + thước dây 5 m + thước cặp cho vật nhỏ |
| Bộ vật mẫu (10 mẫu) | Thùng carton (3 kích cỡ khác nhau), bàn, cửa, tủ, ghế, vali, cái gối (vật mềm/bo cong), lọ hình trụ |
| Bộ đoạn mẫu (12 đoạn) | 4 đoạn ở mỗi dải: 0.3–1 m, 1–3 m, 3–5 m; mỗi dải có 2 đoạn trên bề mặt hoạ tiết, 2 đoạn trên bề mặt trơn |
| Điều kiện sáng (3 mức) | Sáng ban ngày trong nhà (> 300 lux) / đèn phòng ban đêm (~100 lux) / tối yếu (< 30 lux) |
| Điều kiện bề mặt (4 loại) | Gỗ có vân / tường trắng phẳng / mặt kính / vải tối màu |

### 5.2. Ma trận thiết bị (chọn 6–8 máy)

| Vai trò | Ví dụ | Vì sao có trong ma trận |
|---|---|---|
| Cao cấp có Depth | Pixel 8 Pro / Galaxy S-series mới | Đường cơ sở tốt nhất |
| Trung cấp có Depth | Galaxy A-series, moto g 5G | **Đây là phân khúc quyết định** — đông user nhất |
| Cận đáy có Depth | Pixel 4a hoặc máy Depth cũ nhất trong top-20 của bạn | Tìm ngưỡng dưới |
| Không có Depth (PLANE_ONLY) | Pixel 1 hoặc bất kỳ máy được ARCore chứng nhận nhưng không có Depth | Verify fallback |
| Không hỗ trợ ARCore | Máy giá rẻ bất kỳ ngoài danh sách | Verify màn hình UNSUPPORTED |
| Có ToF (nếu mua được) | LG V60 ThinQ | Đo xem ToF cải thiện được bao nhiêu — tham khảo |

### 5.3. Quy trình đo cho mỗi tổ hợp

```
Với mỗi (thiết bị × đoạn/vật mẫu × điều kiện sáng):
  1. Khởi động lại app (session mới, không tái dùng tracking cũ)
  2. Lia máy theo hướng dẫn onboarding, tối đa 10 giây
  3. Đo 5 lần liên tiếp, ghi lại: giá trị, tier, HitQuality, khoảng cách camera,
     confidence trung bình, thời gian từ lúc mở đến lúc đo được
  4. Ghi lại ground truth từ thước laser
  5. Tính: sai số tuyệt đối, sai số tương đối, độ lệch chuẩn của 5 lần
```

### 5.4. Kiểm thử hồi quy tự động — dùng Recording & Playback

Đây là phần **cần đầu tư sớm**, tiết kiệm rất nhiều công về sau:

```
Bước 1 (một lần): Tại hiện trường, dùng Recording API ghi 12–20 file MP4 dataset,
                  mỗi file kèm ground truth đã đo bằng thước laser.
Bước 2:           Lưu dataset vào repo/artifact store.
Bước 3 (mỗi PR):  Trên CI hoặc device farm, dùng Playback API cho app xử lý lại
                  từng dataset và tự động xuất số đo.
Bước 4:           So với ground truth → fail build nếu sai số vượt ngưỡng chấp nhận.
```

Giá trị: mọi thay đổi thuật toán (đổi ngưỡng confidence, đổi percentile, đổi cách lọc) đều được **định lượng ngay lập tức** thay vì "cảm giác là tốt hơn". MP4 dataset chứa cả IMU nên ARCore xử lý giống hệt session thật.

### 5.5. Mẫu bảng ghi kết quả

```csv
device,android_ver,tier,sample_id,sample_type,ground_truth_m,light_lux,surface,
run1_m,run2_m,run3_m,run4_m,run5_m,mean_m,stddev_m,abs_err_m,rel_err_pct,
hit_quality,cam_distance_m,avg_confidence,time_to_first_measure_s,notes
```

---

### 6. Telemetry cần gắn từ ngày đầu

Không có dữ liệu này thì không cải thiện được độ chính xác sau khi phát hành:

| Sự kiện | Thuộc tính |
|---|---|
| `ar_measure_opened` | tier, device model, android version |
| `ar_session_ready` | thời gian từ lúc mở đến lúc đủ điều kiện đo (giây) |
| `ar_tracking_failure` | reason, thời điểm trong phiên |
| `ar_point_placed` | trackable source (DepthPoint/Plane/Point), hit quality, khoảng cách camera, confidence |
| `ar_measurement_completed` | giá trị, đơn vị, mức tin cậy, số lần undo, số lần kéo tinh chỉnh |
| `ar_measurement_abandoned` | bước bị bỏ dở, lý do suy đoán |
| `ar_measurement_adjusted` | **độ lệch giữa giá trị tự động và giá trị người dùng chỉnh** ← chỉ số vàng: cho biết thuật toán sai bao nhiêu, trên dữ liệu thật, ở quy mô lớn |

Chỉ số `ar_measurement_adjusted` là proxy tốt nhất cho độ chính xác thực tế mà không cần ground truth. Nếu người dùng liên tục kéo cạnh dài ra thêm 8 %, bạn biết ngay percentile đang cắt quá nhiều.

---


# Phần 6 — Roadmap, effort & rủi ro

*(nguồn: `06-roadmap-va-rui-ro.md`)*

### 1. Chia pha

### Pha 0 — Spike kỹ thuật (1 tuần, 1 dev)
**Mục tiêu**: khẳng định khả thi trên thiết bị thật trước khi cam kết bất cứ deadline nào.

| Hạng mục | Định nghĩa hoàn thành |
|---|---|
| Dựng project ARCore + render camera background | Camera preview AR chạy trên 2 máy thật |
| Phát hiện tier | Log đúng FULL / PLANE_ONLY / UNSUPPORTED trên ≥ 3 máy khác nhau |
| Hit-test + đặt 2 anchor + in khoảng cách | Có số đo hiện ra |
| **Đo thử 5 vật đã biết kích thước, đối chiếu thước** | **Có bảng sai số thô đầu tiên → dữ liệu để quyết định go/no-go** |
| Verify công thức unproject depth (test ở `04` mục 5.5) | delta < 5 cm |

> 🚨 **Cổng quyết định**: nếu sai số thô của đo 2 điểm ở dải 1–3 m đã vượt 5 % trên máy tốt, đừng đi tiếp — quay lại xem lại kỹ thuật hoặc điều chỉnh scope sản phẩm.

### Pha 1 — Đo khoảng cách production-ready (3–4 tuần, 1 dev + 0.5 designer)
- Vòng đời ARCore hoàn chỉnh: permission, `requestInstall`, pause/resume, xử lý mất session
- Phân tier + màn hình UNSUPPORTED + nhập số đo thủ công
- Reticle giữa màn hình, xoay theo pháp tuyến bề mặt, đổi màu theo chất lượng
- M1 đo 2 điểm + M2 chuỗi điểm
- Cổng chất lượng + overlay hướng dẫn (thiếu sáng, quá xa, chưa đủ dữ liệu)
- Undo / xoá / kéo tinh chỉnh điểm / đổi đơn vị (m-cm-mm / ft-in)
- Onboarding 3 màn hình
- Telemetry đầy đủ
- **Bench test theo `05` trên ≥ 6 máy**

### Pha 2 — Đo diện tích + bounding box thủ công (2–3 tuần)
- M3 đo diện tích mặt phẳng (shoelace)
- M4 bounding box thủ công (chốt mặt nền → cạnh dài → cạnh rộng ép vuông góc → chiều cao)
- Render khối hộp wireframe + tay cầm kéo được
- M6 xuất kết quả: ảnh có annotation, JSON, lịch sử phiên đo
- **Hạ tầng Recording & Playback + bộ dataset ground truth trên CI**

### Pha 3 — Bounding box bán tự động (R&D, 4–6 tuần, rủi ro cao)
- Đường ống Raw Depth: acquire → confidence filter → unproject → gộp nhiều frame
- Voxel downsample + loại mặt nền + statistical outlier removal
- PCA / rotating calipers → OBB
- Tối ưu hiệu năng (khả năng cần chuyển vòng lặp sang NDK/C++)
- Occlusion shader cho vạch đo (dùng full depth) — nâng chất lượng cảm nhận rất nhiều
- **Cổng chất lượng: nếu không đạt tiêu chí ở `05` mục 4 sau 4 tuần → giữ nguyên M4 thủ công và dừng lại**

---

### 2. Ước tính effort

| Pha | Dev-week | Ghi chú |
|---|---|---|
| Pha 0 — Spike | 1 | Bắt buộc, không bỏ qua |
| Pha 1 — Đo khoảng cách | 3–4 | Cộng thêm ~1 tuần nếu tự viết OpenGL từ đầu |
| Pha 2 — Diện tích + hộp thủ công | 2–3 | Phần render khối hộp + tay cầm kéo chiếm phần lớn |
| Pha 3 — Hộp bán tự động | 4–6 | **Ước tính có độ tin cậy thấp** — là R&D thật sự |
| QA / bench test (song song) | 2–3 | Đo hiện trường tốn thời gian hơn dự kiến |
| **Tổng để có sản phẩm dùng được** | **~7–11 dev-week** | Không tính pha 3 |
| **Tổng cả pha 3** | **~11–17 dev-week** | |

Giả định: 1 dev Android senior đã biết OpenGL/3D cơ bản. **Nếu chưa ai trong team làm 3D/OpenGL, cộng thêm 2–3 tuần học** hoặc chọn SceneView để đổi rủi ro bảo trì lấy tốc độ.

---

### 3. Đăng ký rủi ro

| ID | Rủi ro | Xác suất | Tác động | Giảm thiểu |
|---|---|---|---|---|
| R1 | **Độ chính xác không đạt kỳ vọng của stakeholder** | 🔴 Cao | 🔴 Cao | Chốt tiêu chí chấp nhận bằng văn bản **trước** khi code; làm Pha 0 và trình bảng sai số thật trước khi cam kết; định vị sản phẩm là "ước lượng", không phải "thước đo" |
| R2 | **% user ở tier FULL quá thấp** → tính năng bounding box chỉ đến được thiểu số | 🟠 Trung bình | 🔴 Cao | Lấy số liệu top-20 model từ Play Console **ngay bây giờ**; nếu < 50 % thì làm M4 thủ công thay vì M5 |
| R3 | **Người dùng thất bại ở bước "lia máy"** → bỏ giữa dòng | 🔴 Cao | 🟠 Trung bình | Onboarding tốt; theo dõi `ar_measurement_abandoned`; tuyệt đối không cho đo trước khi đủ điều kiện |
| R4 | **Nóng máy / tụt pin** trong phiên đo dài | 🟠 Trung bình | 🟠 Trung bình | Dùng Raw Depth (½ chi phí); giảm tần số xử lý point cloud; auto-pause khi idle; đo nhiệt trong bench test |
| R5 | **Pha 3 (bounding box tự động) không converge** | 🟠 Trung bình | 🟠 Trung bình | Đặt cổng dừng 4 tuần; M4 thủ công là phương án lùi đã có sẵn |
| R6 | **Chọn SceneView rồi lib ngừng bảo trì** | 🟡 Thấp | 🟠 Trung bình | Pin version; bọc lớp render sau interface nội bộ để đổi được; hoặc chọn OpenGL thuần từ đầu |
| R7 | **Copy tutorial dùng Sceneform** (Google đã archive) | 🟠 Trung bình | 🟠 Trung bình | Ghi rõ vào code review checklist: từ chối mọi PR có `com.google.ar.sceneform` |
| R8 | **Rò `Image` / rò `Anchor`** gây crash & tụt fps sau vài phút | 🔴 Cao | 🟠 Trung bình | Bắt buộc `use {}` cho `Image`; `detach()` cho `Anchor`; thêm lint rule; test soak 10 phút |
| R9 | **Danh sách thiết bị của Google thay đổi**, whitelist hardcode bị lỗi thời | 🟠 Trung bình | 🟡 Thấp | Không hardcode — luôn kiểm tra runtime |
| R10 | Phát hành thị trường Trung Quốc (nếu có kế hoạch) | tuỳ | 🟠 Trung bình | Cần nghiên cứu riêng nhánh "Android (China)" + kênh phân phối của hãng |

---

### 4. Quyết định cần chốt trước khi bắt đầu code

| # | Quyết định | Các lựa chọn | Người quyết |
|---|---|---|---|
| D1 | Lớp render | OpenGL ES thuần (khuyến nghị) / SceneView (nhanh hơn, rủi ro lib) | Tech Lead |
| D2 | AR Required hay AR Optional | Optional (khuyến nghị nếu AR là 1 tính năng trong app lớn) | PO + Tech Lead |
| D3 | Có gắn `uses-feature com.google.ar.depth` không | Không (khuyến nghị — dùng fallback tier thay vì cắt máy) | PO |
| D4 | Scope pha 1: chỉ M1 hay cả M2/M3 | — | PO |
| D5 | Tiêu chí chấp nhận độ chính xác | Xem đề xuất ở `05` mục 4 | PO + QA |
| D6 | Có làm M5 (bounding box tự động) hay dừng ở M4 | **Quyết định sau khi có số liệu R2** | PO |
| D7 | Đầu tư hạ tầng Recording & Playback từ pha 1 hay pha 2 | Khuyến nghị pha 2, nhưng ghi dataset từ pha 1 | Tech Lead + QA |
| D8 | Có mở rộng sang iOS về sau không | Nếu **có** → cân nhắc lại Unity AR Foundation ngay từ đầu để dùng 1 codebase; nếu **không** → Android native là chọn đúng | PO |

> **Lưu ý về D8**: nếu iOS nằm trong roadmap 12 tháng, việc chọn Android native bây giờ nghĩa là sẽ phải viết lại toàn bộ phần đo bằng ARKit sau này (ARKit có LiDAR trên máy Pro → thuật toán và kỳ vọng độ chính xác khác hẳn). Cần chốt điều này **trước** pha 1, không phải sau.

---

### 5. Việc cần làm ngay tuần này

1. **Lấy top-20 model + phân bố Android version từ Play Console**, tra từng model trong danh sách thiết bị ARCore, ra được `% user tier FULL`. → đầu vào cho D6.
2. **Chốt D8 (có iOS hay không)** với PO. Đây là quyết định đắt nhất nếu chốt sai.
3. **Mua/mượn thiết bị cho ma trận test** (xem `05` mục 5.2) và **một thước laser**.
4. **Khởi động Pha 0** — 1 dev, 1 tuần, đầu ra là bảng sai số thật.
5. **Chốt tiêu chí chấp nhận bằng văn bản** dựa trên `05` mục 4, có PO ký.

---


# Phần 7 — Nguồn tham khảo

*(nguồn: `07-nguon-tham-khao.md`)*

### 1. Tài liệu chính thức của Google (độ tin cậy cao)

| Nội dung | Link |
|---|---|
| Tổng quan ARCore & môi trường phát triển được hỗ trợ *(trang gốc do bạn cung cấp)* | https://developers.google.com/ar/develop |
| Các khái niệm cơ bản (SLAM, feature point, plane, trackable, anchor, hit-test) | https://developers.google.com/ar/develop/fundamentals |
| **Danh sách thiết bị được hỗ trợ** (Depth API, ToF, yêu cầu Android version) | https://developers.google.com/ar/devices |
| Depth API — tổng quan (dải 0.5–5 m, max 65 m, depth-from-motion, ToF) | https://developers.google.com/ar/develop/depth |
| **Depth API — hướng dẫn Android** (code: isDepthModeSupported, acquireDepthImage16Bits, getMillimetersDepth, transformCoordinates2d) | https://developers.google.com/ar/develop/java/depth/developer-guide |
| Depth API — quickstart Android | https://developers.google.com/ar/develop/java/depth/quickstart |
| **Raw Depth API — Android** (acquireRawDepthImage16Bits, acquireRawDepthConfidenceImage, so sánh với full depth) | https://developers.google.com/ar/develop/java/depth/raw-depth |
| Hit-test — tổng quan (4 loại, fallback về plane khi không có depth) | https://developers.google.com/ar/develop/hit-test |
| **Hit-test — hướng dẫn Android** (hitTest, HitResult, createAnchor, trackable types) | https://developers.google.com/ar/develop/java/hit-test/developer-guide |
| Instant Placement — hướng dẫn Android (cảnh báo scale phỏng đoán) | https://developers.google.com/ar/develop/java/instant-placement/developer-guide |
| Recording & Playback API | https://developers.google.com/ar/develop/recording-and-playback |
| Bật ARCore trong app Android (manifest, minSdk 24, ArCoreApk) | https://developers.google.com/ar/develop/java/enable-arcore |
| Tải SDK | https://developers.google.com/ar/develop/downloads |
| Chạy app AR trên Android Emulator | https://developers.google.com/ar/develop/c/emulator |
| API reference — `Pose` (**đơn vị mét, hệ thuận tay phải kiểu OpenGL**) | https://developers.google.com/ar/reference/java/com/google/ar/core/Pose |
| API reference — `CameraIntrinsics` (focalLength, principalPoint, imageDimensions) | https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics |
| API reference — `Frame` | https://developers.google.com/ar/reference/java/com/google/ar/core/Frame |
| API reference — `Config.DepthMode` | https://developers.google.com/ar/reference/java/com/google/ar/core/Config.DepthMode |
| API reference — `DepthPoint` | https://developers.google.com/ar/reference/java/com/google/ar/core/DepthPoint |
| ARCore làm input cho model Machine Learning (nếu về sau muốn tự động nhận dạng vật thể để khoanh ROI) | https://developers.google.com/ar/develop/java/machine-learning |

### 2. Mã nguồn mẫu (độ tin cậy cao — của Google)

| Nội dung | Link |
|---|---|
| ARCore Android SDK + sample `hello_ar_kotlin` (nền tảng render OpenGL) | https://github.com/google-ar/arcore-android-sdk |
| Releases ARCore Android SDK (bản quan sát được: **1.54.0**) | https://github.com/google-ar/arcore-android-sdk/releases |
| **ARCore Depth Lab** — Unity, nhưng có scene *Oriented 3D Reticle* và *Point Cloud from raw depth*: nguồn tham khảo thuật toán tốt nhất | https://github.com/googlesamples/arcore-depth-lab |

### 3. Thư viện bên thứ ba (độ tin cậy trung bình — cân nhắc kỹ)

| Nội dung | Link | Ghi chú |
|---|---|---|
| SceneView — 3D & AR cho Android trên Google Filament + ARCore, hỗ trợ Jetpack Compose | https://github.com/sceneview/sceneview | Thư viện cộng đồng, là bản thay thế cho Sceneform. Phải pin version. |
| Sceneform Maintained — bản tiếp nối của Sceneform đã bị Google archive | https://github.com/SceneView/sceneform-android | Chỉ dùng nếu đang phải bảo trì code Sceneform cũ |

### 4. Nguồn tham khảo phụ (độ tin cậy thấp — chỉ dùng để đối chiếu định tính)

| Nội dung | Link | Ghi chú |
|---|---|---|
| Blog triển khai đo khoảng cách bằng ARCore (47Billion) | https://47billion.com/blog/distance-measurement-on-mobile-app-using-arcore/ | Khẳng định app AR chỉ cho **ước lượng gần đúng**, thước vật lý chính xác hơn; **không đưa con số sai số cụ thể** |
| Đánh giá khả năng xác định khoảng cách của ARCore (ResearchGate) | https://www.researchgate.net/publication/382420916_Evaluation_of_ARCORE_library_capabilities_for_determining_the_distance_to_objects_in_the_frame | Chưa đọc toàn văn — **nếu cần số liệu học thuật, đây là điểm khởi đầu** |
| Phân tích ARKit đo khoảng cách trên khuôn mặt (Sensors, MDPI) | https://doi.org/10.3390/s23094486 | Về ARKit, không phải ARCore. Tham khảo phương pháp luận bench test |

### 5. Những điều KHÔNG xác nhận được (minh bạch về khoảng trống)

| Hạng mục | Trạng thái |
|---|---|
| Con số độ chính xác/sai số chính thức của ARCore cho việc đo | ❌ **Google không công bố.** Mọi ngưỡng trong `05` là đề xuất nội bộ của tài liệu này |
| Phiên bản ARCore SDK hiện hành chính xác tại 08/2026 | ⚠️ Trang tải SDK yêu cầu chấp nhận ToS nên không đọc được qua crawler; trang release notes trả 404. Bản quan sát được trên GitHub: **1.54.0**. **Verify lại trước khi code** |
| Danh sách đầy đủ máy có cảm biến ToF | ⚠️ Chỉ xác nhận được **LG V60 ThinQ / V60 ThinQ 5G** được ghi rõ. Số lượng máy có ToF rất nhỏ |
| Tỉ lệ % thiết bị hỗ trợ Depth API trên tổng số máy ARCore | ❌ Không có số liệu công khai. **Phải tự tính từ top-20 model của bạn** (xem `06` mục 5) |
| Công thức unproject depth → 3D chính thức của Google | ⚠️ Tài liệu Raw Depth **không cung cấp**. Công thức trong `04` mục 5.4 suy ra từ `CameraIntrinsics`; **dấu phải verify bằng thực nghiệm** (có test ở `04` mục 5.5) |
| Sample "Measure" chính thức từ Google | ❌ Không có. Depth Lab có *Oriented 3D Reticle* và *Point Cloud* là gần nhất |


### 6. Ghi chú kiểm chứng

Toàn bộ tên class / method / enum trong `04-code-mau-kotlin.md` đã được đối chiếu lại với trang
API reference chính thức của ARCore. Bảng kết quả đối chiếu nằm ở `04-code-mau-kotlin.md`, mục 10.

Hai lỗi đã được phát hiện và sửa trong quá trình kiểm chứng (ghi lại để tránh tái phạm khi code):

1. **`Session` không implement `Closeable`** → không dùng được `use {}`, phải `try/finally { close() }`.
2. **Các setter của `Config` theo kiểu fluent builder** (trả về `Config`) → cú pháp property của Kotlin
   `config.depthMode = ...` **không compile**; phải dùng `config.setDepthMode(...)`.

---
