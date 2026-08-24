# 03 — Thiết kế tính năng AR Measure: kiến trúc, UX, thuật toán

## 1. Phạm vi tính năng

| Mã | Tính năng | Tier tối thiểu | Pha |
|---|---|---|---|
| M1 | **Đo khoảng cách 2 điểm** (chiều dài, chiều rộng, đường chéo) | PLANE_ONLY | 1 |
| M2 | **Đo chuỗi điểm** (polyline: chu vi, đường gấp khúc) | PLANE_ONLY | 1 |
| M3 | **Đo diện tích** mặt phẳng (đa giác trên sàn/tường/mặt bàn) | PLANE_ONLY | 1 |
| M4 | **Đo vật thể D×R×C — thủ công** (người dùng chạm để dựng khối hộp) | PLANE_ONLY | 2 |
| M5 | **Đo vật thể D×R×C — bán tự động** (khoanh vùng → point cloud → oriented bounding box) | FULL | 3 |
| M6 | Xuất kết quả (ảnh chụp có annotation, JSON số đo, lịch sử phiên đo) | — | 2 |

---

## 2. Kiến trúc module

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

## 3. Luồng UX (rất quan trọng — đây là nơi tính năng đo thường thất bại)

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

## 4. Thuật toán M1 — đo khoảng cách 2 điểm

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

## 5. Thuật toán M3 — đo diện tích mặt phẳng

1. Người dùng đặt ≥ 3 điểm trên cùng một `Plane`.
2. Dựng hệ toạ độ 2D cục bộ của mặt phẳng: `origin = plane.centerPose.translation`, `u = plane.centerPose.xAxis`, `v = plane.centerPose.zAxis`.
3. Chiếu từng điểm: `(x_i, y_i) = ( dot(p_i − origin, u), dot(p_i − origin, v) )`.
4. Diện tích bằng công thức shoelace:
   `A = ½ · | Σ (x_i · y_{i+1} − x_{i+1} · y_i) |`  (m²)
5. Chu vi = tổng độ dài các cạnh.

Cảnh báo nếu đa giác tự cắt (self-intersecting) — kết quả shoelace sẽ vô nghĩa.

---

## 6. Thuật toán M4 — đo vật thể D×R×C, phương án THỦ CÔNG (làm trước)

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

## 7. Thuật toán M5 — bounding box BÁN TỰ ĐỘNG từ Raw Depth (pha 3, cần R&D)

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

## 8. Cấu hình Session đề xuất

| Thiết lập | Giá trị | Lý do |
|---|---|---|
| `depthMode` | `AUTOMATIC` nếu `isDepthModeSupported`, ngược lại `DISABLED` | Mặc định là tắt, phải bật |
| `planeFindingMode` | `HORIZONTAL_AND_VERTICAL` | Cần cả sàn/bàn và tường |
| `focusMode` | `AUTO` | Ảnh nét hơn → nhiều feature point hơn → tracking tốt hơn. (Mặc định của một số cấu hình là FIXED.) |
| `updateMode` | `LATEST_CAMERA_IMAGE` | Vòng lặp render không bị block |
| `instantPlacementMode` | `DISABLED` | Tránh nguy cơ vô tình lấy số đo từ pose phỏng đoán |
| `lightEstimationMode` | `DISABLED` | Không cần, tiết kiệm CPU/nhiệt |
| `cloudAnchorMode`, `geospatialMode` | `DISABLED` | Không dùng |
