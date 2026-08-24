# 07 — Nguồn tham khảo & ghi chú độ tin cậy

## 1. Tài liệu chính thức của Google (độ tin cậy cao)

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

## 2. Mã nguồn mẫu (độ tin cậy cao — của Google)

| Nội dung | Link |
|---|---|
| ARCore Android SDK + sample `hello_ar_kotlin` (nền tảng render OpenGL) | https://github.com/google-ar/arcore-android-sdk |
| Releases ARCore Android SDK (bản quan sát được: **1.54.0**) | https://github.com/google-ar/arcore-android-sdk/releases |
| **ARCore Depth Lab** — Unity, nhưng có scene *Oriented 3D Reticle* và *Point Cloud from raw depth*: nguồn tham khảo thuật toán tốt nhất | https://github.com/googlesamples/arcore-depth-lab |

## 3. Thư viện bên thứ ba (độ tin cậy trung bình — cân nhắc kỹ)

| Nội dung | Link | Ghi chú |
|---|---|---|
| SceneView — 3D & AR cho Android trên Google Filament + ARCore, hỗ trợ Jetpack Compose | https://github.com/sceneview/sceneview | Thư viện cộng đồng, là bản thay thế cho Sceneform. Phải pin version. |
| Sceneform Maintained — bản tiếp nối của Sceneform đã bị Google archive | https://github.com/SceneView/sceneform-android | Chỉ dùng nếu đang phải bảo trì code Sceneform cũ |

## 4. Nguồn tham khảo phụ (độ tin cậy thấp — chỉ dùng để đối chiếu định tính)

| Nội dung | Link | Ghi chú |
|---|---|---|
| Blog triển khai đo khoảng cách bằng ARCore (47Billion) | https://47billion.com/blog/distance-measurement-on-mobile-app-using-arcore/ | Khẳng định app AR chỉ cho **ước lượng gần đúng**, thước vật lý chính xác hơn; **không đưa con số sai số cụ thể** |
| Đánh giá khả năng xác định khoảng cách của ARCore (ResearchGate) | https://www.researchgate.net/publication/382420916_Evaluation_of_ARCORE_library_capabilities_for_determining_the_distance_to_objects_in_the_frame | Chưa đọc toàn văn — **nếu cần số liệu học thuật, đây là điểm khởi đầu** |
| Phân tích ARKit đo khoảng cách trên khuôn mặt (Sensors, MDPI) | https://doi.org/10.3390/s23094486 | Về ARKit, không phải ARCore. Tham khảo phương pháp luận bench test |

## 5. Những điều KHÔNG xác nhận được (minh bạch về khoảng trống)

| Hạng mục | Trạng thái |
|---|---|
| Con số độ chính xác/sai số chính thức của ARCore cho việc đo | ❌ **Google không công bố.** Mọi ngưỡng trong `05` là đề xuất nội bộ của tài liệu này |
| Phiên bản ARCore SDK hiện hành chính xác tại 08/2026 | ⚠️ Trang tải SDK yêu cầu chấp nhận ToS nên không đọc được qua crawler; trang release notes trả 404. Bản quan sát được trên GitHub: **1.54.0**. **Verify lại trước khi code** |
| Danh sách đầy đủ máy có cảm biến ToF | ⚠️ Chỉ xác nhận được **LG V60 ThinQ / V60 ThinQ 5G** được ghi rõ. Số lượng máy có ToF rất nhỏ |
| Tỉ lệ % thiết bị hỗ trợ Depth API trên tổng số máy ARCore | ❌ Không có số liệu công khai. **Phải tự tính từ top-20 model của bạn** (xem `06` mục 5) |
| Công thức unproject depth → 3D chính thức của Google | ⚠️ Tài liệu Raw Depth **không cung cấp**. Công thức trong `04` mục 5.4 suy ra từ `CameraIntrinsics`; **dấu phải verify bằng thực nghiệm** (có test ở `04` mục 5.5) |
| Sample "Measure" chính thức từ Google | ❌ Không có. Depth Lab có *Oriented 3D Reticle* và *Point Cloud* là gần nhất |


## 6. Ghi chú kiểm chứng

Toàn bộ tên class / method / enum trong `04-code-mau-kotlin.md` đã được đối chiếu lại với trang
API reference chính thức của ARCore. Bảng kết quả đối chiếu nằm ở `04-code-mau-kotlin.md`, mục 10.

Hai lỗi đã được phát hiện và sửa trong quá trình kiểm chứng (ghi lại để tránh tái phạm khi code):

1. **`Session` không implement `Closeable`** → không dùng được `use {}`, phải `try/finally { close() }`.
2. **Các setter của `Config` theo kiểu fluent builder** (trả về `Config`) → cú pháp property của Kotlin
   `config.depthMode = ...` **không compile**; phải dùng `config.setDepthMode(...)`.
