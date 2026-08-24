# 01 — Tổng quan ARCore, chắt lọc theo hướng "đo kích thước"

## 1. ARCore là gì, làm được gì (nền tảng)

ARCore là nền tảng AR của Google. Nó làm đúng **2 việc lõi**:

1. **Motion tracking (SLAM)** — theo dõi vị trí + hướng của thiết bị trong không gian. Cơ chế: camera nhặt ra các *feature point* (điểm đặc trưng thị giác) trên ảnh, theo dõi chúng dịch chuyển qua các frame, kết hợp với số đọc từ cảm biến quán tính (accelerometer + gyroscope) để suy ra pose của máy.
2. **Environmental understanding** — dựng hiểu biết về thế giới thật: phát hiện mặt phẳng, sinh bản đồ chiều sâu, ước lượng ánh sáng.

Ba khả năng Google nêu ở trang tổng quan: *motion tracking*, *environmental understanding*, *light estimation*. Với tính năng đo, ta chỉ cần **2 cái đầu**. Light estimation không liên quan (chỉ dùng để render vật ảo trông thật).

### Điểm quan trọng nhất cho bài toán đo

> **Pose trong ARCore có đơn vị là MÉT, trong hệ toạ độ thuận tay phải (right-handed, quy ước OpenGL).**

Đây chính là lý do tính năng đo khả thi: ARCore đã trả về toạ độ *thang đo thực* (metric scale), không phải toạ độ tương đối. Đo = lấy 2 điểm world-space rồi tính khoảng cách Euclid. Không cần calibrate, không cần vật tham chiếu.

Nguồn scale này đến từ việc fusion camera + IMU. Cũng chính vì vậy mà **sai số đo phụ thuộc chất lượng tracking**, chứ không phụ thuộc "độ phân giải camera" như trực giác thường nghĩ.

---

## 2. Các khối API của ARCore — cái nào dùng, cái nào bỏ

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

## 3. Depth API — phần cốt tử, đọc kỹ

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

## 4. Hit-test — cơ chế chọn điểm đo

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

## 5. Những gì trang tổng quan ARCore *nói rõ* là hạn chế

Trích ý từ tài liệu chính thức:

- *"Bề mặt phẳng không có hoạ tiết, ví dụ tường trắng, có thể không được phát hiện đúng."*
- Depth: *"bề mặt ít hoạ tiết cho kết quả đo không chính xác"*, và *"depth chỉ khả dụng khi có chuyển động của người dùng"*.
- Depth mode **mặc định TẮT**, phải bật thủ công.
- Depth API **cần thiết bị có đủ năng lực xử lý** → không phải mọi máy chạy ARCore đều có Depth.

---

## 6. SDK & môi trường phát triển

Google cung cấp SDK cho: **Android (Kotlin/Java)**, **Android NDK (C)**, **Unity (AR Foundation)**, **iOS**, **Unreal**, **Web**.
Đã chọn: **Android (Kotlin/Java)**, cân nhắc NDK cho vòng lặp xử lý point cloud nếu profiling cho thấy JNI/Kotlin quá chậm.

| Hạng mục | Giá trị |
|---|---|
| `minSdkVersion` | **24** (Android 7.0) |
| Dependency | `implementation 'com.google.ar:core:<version>'` — verify version mới nhất trước khi code (bản quan sát được: `1.54.0`) |
| Runtime prerequisite | **Google Play Services for AR** phải có trên máy (`ArCoreApk.checkAvailability()` / `requestInstall()`) |
| Emulator | Có hỗ trợ nhưng giới hạn — **không dùng emulator để đánh giá độ chính xác** |
