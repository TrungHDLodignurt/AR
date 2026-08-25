# Deep Research: ARCore hạn chế phần cứng & giải pháp thay thế cho đo vật thể/mặt phẳng

**Ngày:** 2026-08-24 | **Nguồn:** 5 WebSearch (gemini CLI không có trên máy) + đối chiếu tài liệu nội bộ đã có

**Lưu ý:** repo đã có bộ tài liệu ARCore rất đầy đủ (`AR-Measure-ARCore/00-07`, 2 report trong `plans/reports/`).
Report này **không lặp lại** phần đó — chỉ (1) cập nhật số liệu 2025-2026 mới tìm được, (2) trả lời riêng
phần "giải pháp thay thế" mà bộ tài liệu cũ chưa có, đúng với tên nhánh `feature/photo-reference-measure`
đang gợi ý hướng đi mới.

## Tóm tắt điều hành

ARCore vẫn là lựa chọn đúng cho tier máy được chứng nhận (đo bằng depth-from-motion, không cần ToF/LiDAR).
Nhưng **2/2 máy test thật của mình đều bị Google từ chối chứng nhận** (xem report `fork-aruler-feasibility`
và `arcore-hardware-limitation`) — đây không phải case hiếm, mà là rủi ro thật với phân khúc máy tầm
trung/giá rẻ. Vì vậy **giải pháp thay thế không dùng ARCore** (đo bằng 1 ảnh chụp + vật tham chiếu kích
thước biết trước — "photo-reference measure") là hướng bổ trợ hợp lý, phủ được nhóm máy bị chặn ở Cửa 2,
đổi lại độ chính xác thấp hơn và cần người dùng thao tác thêm 1 bước (đặt vật tham chiếu vào khung hình).
Khuyến nghị: **build song song 2 pipeline, không thay thế nhau** — ARCore cho máy tier FULL/PLANE, photo-
reference cho máy tier UNSUPPORTED, cùng share tầng UI đo (chấm điểm, tính khoảng cách) đã thiết kế ở `03`.

## 1. Cập nhật số liệu ARCore 2025-2026 (bổ sung vào `02`, `05`)

| Chỉ số | Số liệu mới | Nguồn |
|---|---|---|
| % thiết bị active hỗ trợ Depth API | **>88%** (tính đến 05/2026) | [Google Depth docs](https://developers.google.com/ar/develop/depth) |
| Độ chính xác đo thực tế (không LiDAR) | **±2-5cm** trong phạm vi 3m; lỗi trung bình 1-5cm ở phòng dân dụng | [Coohom AR vs Laser](https://www.coohom.com/article/ar-measuring-apps-vs-laser-distance-meters-which-is-more-accurate) |
| ARCore vs ARKit | ARCore: số liệu nhảy lúc đầu nhưng ổn định sau; ARKit mượt hơn nhưng kém chính xác hơn khi không có LiDAR — **ARKit+LiDAR đạt cm-level, ARCore không tự động dùng depth để hiệu chỉnh mặc định** | [Zhongyu Wang - Medium](https://zhongyu-wang.medium.com/arcore-vs-arkit-in-terms-of-indoor-positioning-20e12193eabc) |
| Hiệu năng native (mid-range) | Plane detection 55-60 FPS, object tracking 50-60 FPS, RAM 140-200MB, CPU 25-40% | [Angry Shark Studio](https://www.angry-shark-studio.com/blog/ar-foundation-vs-arcore-comparison/) |
| Giới hạn plane detection | ARCore phát hiện mặt ngang tốt (bàn, sàn); mặt đứng (tường) **yếu hơn đáng kể** — cần texture rõ, ánh sáng đủ | [Grid Dynamics](https://www.griddynamics.com/blog/arkit-arcore-recognize-vertical-planes) |
| Anchor/jitter | Bắt buộc dùng Anchor (không dùng raw pose) + Moving Average Filter trên hit-test để chống số nhảy | [ARCore Anchors guide](https://developers.google.com/ar/develop/anchors) |

→ Xác nhận lại khuyến nghị cũ trong `03`: **luôn snap vào Anchor + Plane, không tin raw camera pose**;
đo mặt tường (mặt đứng) sẽ kém ổn định hơn đo sàn/mặt bàn — cần disclaimer UX riêng cho tính năng "đo tường".

## 2. Bảng so sánh giải pháp thay thế/bổ trợ ARCore

| Giải pháp | Cần phần cứng đặc biệt? | Độ chính xác | Độ phủ thiết bị | Effort tích hợp | Phù hợp use-case |
|---|---|---|---|---|---|
| **ARCore Depth API (hiện có)** | Không | ±2-5cm (0.5-5m) | ~88% máy active *nếu* qua được Cửa 2 (certified) | Đã có sẵn (Pha 1 `06`) | Đo khoảng cách/vật thể/mặt phẳng khi máy được chứng nhận |
| **ARKit + LiDAR (chỉ tham khảo, không áp dụng — app Android)** | LiDAR (chỉ iPhone Pro/iPad Pro) | cm-level, tốt nhất thị trường | Rất hẹp (chỉ dòng Pro) | N/A cho Android | Không dùng được, ghi để đối chiếu benchmark |
| **ToF sensor phần cứng (Android)** | Cảm biến ToF vật lý | Tốt hơn depth-from-motion nhưng ARCore fusion tự động, không cần code riêng | Hiếm trên Android (thị phần ToF dùng cho camera lấy nét, không phổ biến cho AR); chủ yếu vài dòng flagship TQ | Miễn phí nếu có — ARCore tự dùng khi phát hiện | Bonus, không nên thiết kế phụ thuộc |
| **Monocular ML depth estimation (MiDaS v3.1, Depth Anything v2)** | Không (chạy trên ảnh RGB thường) | Depth *tương đối*, không phải *metric* (không tự ra đơn vị cm/m) — cần calibrate thêm mới dùng đo được | 100% máy có camera | Cao: cần tích hợp model on-device (TFLite/NNAPI), thêm bước hiệu chỉnh scale | Không khuyến nghị làm chính — chỉ hợp khi cần depth map để segment object, không hợp để ra số đo tuyệt đối |
| **Photogrammetry (multi-photo 3D reconstruction)** | Không | Có thể chính xác nếu đủ ảnh + baseline tốt | 100% máy | Rất cao (SfM pipeline, xử lý nặng, thường off-device) | Quá nặng cho use-case "đo nhanh 1 vật" — hợp cho quét phòng 3D, không hợp mobile realtime |
| **Photo-reference (1 ảnh + vật tham chiếu kích thước biết trước)** | Không | Phụ thuộc độ chính xác người dùng đặt vật tham chiếu + góc chụp vuông góc; sai số lớn hơn ARCore nếu chụp nghiêng | 100% máy (kể cả máy không certified ARCore) | Trung bình: chỉ cần object detection cạnh vật tham chiêu (OpenCV/ML Kit) + tính tỉ lệ pixel→cm, không cần SLAM | **Fallback tier UNSUPPORTED** — đúng hướng nhánh hiện tại |
| **Google ML Kit Object Detection** | Không | N/A (chỉ detect, không đo) | 100% máy | Thấp (SDK có sẵn, on-device) | Dùng để tự động khoanh vùng vật thể/vật tham chiếu trong ảnh, không tự đo được — phải kết hợp với 1 trong các giải pháp trên |

## 3. Đề xuất kiến trúc "Photo-reference measure" (đúng tên nhánh hiện tại)

**Nguyên lý:** người dùng đặt 1 vật tham chiếu kích thước chuẩn đã biết (thẻ ATM/căn cước 85.6×54mm, tờ
A4 210×297mm, hoặc đồng xu) cạnh vật cần đo, chụp 1 ảnh vuông góc với mặt phẳng chứa cả 2 vật → tính tỉ lệ
pixel/mm từ vật tham chiếu → suy ra kích thước vật cần đo từ pixel của nó trong cùng ảnh.

```
Ảnh chụp
   │
   ├─► Object detection/segmentation (ML Kit hoặc OpenCV contour)
   │        ├─ Vật tham chiếu → 4 góc/cạnh (pixel)
   │        └─ Vật cần đo     → 4 góc/cạnh hoặc bounding box (pixel)
   │
   ├─► Tính scale = kích_thước_thật_vật_tham_chiếu(mm) / kích_thước_pixel_vật_tham_chiếu
   │
   └─► Kích thước vật cần đo(mm) = kích_thước_pixel_vật_cần_đo × scale
```

**Điều kiện chính xác (bắt buộc ràng buộc UX):**
1. Mặt phẳng chứa cả 2 vật phải **phẳng và vuông góc với trục camera** (nghiêng → sai số phi tuyến, lớn
   theo góc nghiêng — cảnh báo bằng gyroscope/accelerometer nếu góc nghiêng > ~10°).
2. Vật tham chiếu và vật cần đo phải **cùng mặt phẳng/cùng khoảng cách tới camera** — không dùng được cho
   vật 3D có chiều sâu khác biệt lớn (vd đo được mặt trước TV nhưng không đo được độ dày TV bằng 1 ảnh).
3. Cần bộ vật tham chiếu chuẩn cho người dùng chọn (thẻ ngân hàng là phổ biến nhất, ai cũng có sẵn).

**Việc cần làm nếu triển khai:**
- Thêm ML Kit Object Detection (hoặc OpenCV contour/edge detection) để tự tìm cạnh vật tham chiếu.
- Module tính scale + suy ra kích thước — logic thuần toán học, không cần ARCore, tái dùng được
  `DistanceCalculator` hiện có (đổi input từ 3D world-point sang pixel×scale).
- Onboarding: hướng dẫn "đặt thẻ cạnh vật, chụp vuông góc từ trên xuống" + cảnh báo góc nghiêng.
- **Giới hạn công bố rõ cho user:** chỉ đo được kích thước 2D trên cùng 1 mặt phẳng (dài×rộng của mặt
  phẳng đó), không đo được thể tích/độ sâu như ARCore Pha 3. Đây là trade-off chấp nhận được để đổi lấy
  100% device coverage.

## 4. Khuyến nghị chốt

1. **Không thay ARCore bằng photo-reference** — giữ ARCore làm pipeline chính (đã có design đầy đủ ở
   `03`, `04`, `05`, roadmap ở `06`). Photo-reference là **fallback cho tier UNSUPPORTED** (máy không qua
   Cửa 2), không phải bản nâng cấp.
2. Route chọn pipeline theo kết quả `ArCoreApk.checkAvailability()` đã có trong code — `UNSUPPORTED` thì
   chuyển sang màn hình photo-reference thay vì chỉ hiện "AR không hỗ trợ" như hiện tại.
3. Đo mặt tường/mặt đứng bằng ARCore: cần disclaimer UX riêng vì plane detection mặt đứng yếu hơn mặt
   ngang (mục 1 ở trên) — không phải bug, là hạn chế thuật toán.
4. Trước khi code photo-reference: chốt bộ vật tham chiếu hỗ trợ (khuyến nghị chỉ 1 loại đầu tiên — thẻ
   ATM/CCCD, kích thước ISO/IEC 7810 chuẩn 85.6×54mm, phổ biến nhất) để giảm scope.

## 5. Câu hỏi chưa giải quyết

- Chưa có benchmark thực tế nào cho hướng photo-reference trong nội bộ (khác với ARCore đã có report
  test 2 máy thật) — cần build spike + tự đo đối chiếu thước thật trước khi cam kết % sai số.
- Chưa xác định ML Kit Object Detection có đủ chính xác để tự khoanh cạnh thẻ ATM (vật nhỏ, ít texture)
  hay cần OpenCV edge/contour detection thủ công hơn — cần thử nghiệm cả 2.
- Model máy thứ 3 trong bộ test cũ vẫn chưa xác nhận (câu hỏi tồn đọng từ report `arcore-hardware-limitation`).

## Nguồn tham khảo

- https://developers.google.com/ar/develop/depth
- https://developers.google.com/ar/develop/java/depth/developer-guide
- https://developers.google.com/ar/develop/anchors
- https://www.griddynamics.com/blog/arkit-arcore-recognize-vertical-planes
- https://www.angry-shark-studio.com/blog/ar-foundation-vs-arcore-comparison/
- https://zhongyu-wang.medium.com/arcore-vs-arkit-in-terms-of-indoor-positioning-20e12193eabc
- https://www.coohom.com/article/ar-measuring-apps-vs-laser-distance-meters-which-is-more-accurate
- https://github.com/cake-lab/Mobile-AR-Depth-Estimation
- https://developers.google.com/ar/devices
- Nội bộ: `AR-Measure-ARCore/00-07`, `plans/reports/report-260824-1520-arcore-hardware-limitation.md`,
  `plans/reports/report-260824-1644-fork-aruler-feasibility.md`
