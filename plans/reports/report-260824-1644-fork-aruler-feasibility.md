# Đánh giá khả thi: fork 2 tính năng nổi bật của ARuler

**Phạm vi:** tính năng "đo tự động vật hình tròn/elip" và "đo thể tích đống vật liệu (heap)".
Không đụng tới ads/paywall/onboarding (ngoài phạm vi theo quy tắc clean-room).
**Phương pháp:** Phase 1 static recon (đã làm ở báo cáo trước) + đọc symbol xuất ra (exported
function name) từ các lib native — **không đọc/copy code đã decompile**, chỉ dùng tên hàm để xác
định ARuler dựa trên thuật toán/lib nào, rồi tự đánh giá khả thi build lại bằng thuật toán gốc
(clean-room).

## 1. Phát hiện quan trọng nhất: cả 2 tool đều dựa trên thuật toán/lib MÃ NGUỒN MỞ, không phải ML riêng tự train

| Tool | Lib native | Symbol xuất ra | Suy ra |
|---|---|---|---|
| Circle/ellipse tự động | `libcircle-detector-lib.so` | `CVCore_Circle_initAAMED`, `..._runAAMED_JNI` | Dùng **AAMED** — thuật toán phát hiện ellipse/circle công bố học thuật, có mã nguồn mở, **không phải model TensorFlow tự train** |
| Heap/volume | `libpcl-lib.so` | `PCLGrymalaLib_pclPlaneDetection`, `..._pclStatFiltering` | Dùng thẳng **PCL (Point Cloud Library)** thật — bản build native của thư viện mã nguồn mở, đúng 2 bước "loại mặt nền" + "loại nhiễu thống kê" mà `03-thiet-ke-tinh-nang-ar-measure.md` mục 7 đã tự đề xuất từ trước |

→ **Tin tốt cho khả thi:** không cần "học lại" thuật toán bí mật của Grymala, không cần training
data. AAMED và PCL đều là công khai, dùng lại được (đọc thuật toán/lib gốc, viết code mới — đúng
clean-room). Không thấy file `.tflite`/model trong APK — TensorFlow Lite (+GPU delegate) khả năng
dùng cho tính năng khác không liên quan đo (chưa xác nhận, xem mục 4).

## 2. Đánh giá từng tool

### Tool A — Đo tự động vật hình tròn/elip (đường kính ống, bánh xe, lỗ khoan...)

- **Khả thi: CAO.** Có 2 hướng:
  1. Dùng lại thuật toán **AAMED** (nếu license cho phép — cần kiểm tra trước khi dùng, xem mục 3), hoặc
  2. Đơn giản hơn: OpenCV có sẵn **Hough Circle Transform** — kém chính xác hơn AAMED một chút với ellipse nghiêng góc, nhưng **license OpenCV (Apache 2.0) rõ ràng, không cần lo pháp lý**, code ít hơn nhiều.
- **Không cần tier FULL/Depth API** — vật hình tròn đo được miễn nó rơi trên 1 mặt phẳng đã phát hiện (ống nằm trên bàn, lỗ trên tường) → dùng plane hit-test đã có sẵn trong `MeasureHit.kt`, không cần đầu tư thêm hạ tầng depth. **Diện phủ thiết bị rộng hơn nhiều** so với tool B.
- **Việc cần làm:** thêm OpenCV Android vào dependency, viết 1 bước "chụp khung hình tại vùng ngắm → detect ellipse → lấy 2 điểm mép đối diện trên ellipse → hit-test 2 điểm đó ra 3D → dùng công thức khoảng cách Euclid đã có sẵn (`DistanceCalculator`/tương đương)". Gần như là **tái dùng logic đo 2 điểm đã có**, chỉ thêm bước "tìm 2 điểm đó tự động thay vì người dùng chạm tay".
- **Ước tính:** ~1–2 dev-week (đã có sẵn hạ tầng hit-test + anchor + tính khoảng cách từ Pha 1).

### Tool B — Đo thể tích đống vật liệu (heap) bằng point cloud

- **Khả thi: TRUNG BÌNH → THẤP**, không phải vì thuật toán khó hiểu, mà vì **đây chính là hạng mục
  rủi ro cao nhất đã được cảnh báo từ trước** trong `06-roadmap-va-rui-ro.md` (Pha 3 — "Bounding
  box bán tự động", 4-6 dev-week, "ước tính có độ tin cậy thấp — là R&D thật sự", risk R5).
- Khác biệt so với M5 đã lên kế hoạch: M5 tính OBB (hộp chữ nhật) từ point cloud; tool heap của
  ARuler tính **thể tích của bề mặt bất định hình** (đống cát/sỏi) — về mặt toán, đây là tích phân
  chiều cao trên lưới 2D thay vì percentile L×W×H, **phức tạp hơn M5 một chút**, không kém.
- **Bắt buộc cần Depth API (tier FULL)** → chỉ chạy được trên một phần thiết bị đã được ARCore
  chứng nhận VÀ hỗ trợ depth (xem `02-han-che-phan-cung-va-thiet-bi.md` Cửa 3). Đây là hạn chế
  thiết bị chồng thêm lên hạn chế đã có, thu hẹp diện phủ người dùng hơn nữa.
- ARuler còn tự viết **shader riêng** (`heap.vert`/`heap.frag`, cả bản GLSL và SPIR-V/Vulkan) để
  vẽ mặt heap dạng lưới 3D thật — nghĩa là họ **không dùng overlay 2D** như `ar-tape-measure` đang
  làm (README của mình: "Nothing is drawn as 3D geometry" — quyết định có chủ đích, xem lý do ở
  đó). Muốn làm tool này đúng kiểu ARuler thì phải **đảo ngược quyết định kiến trúc render** đang có
  — không nhỏ.
- PCL (C++) build cho Android tự nó cũng mất công (kéo theo Eigen, FLANN — dependency nặng), dù
  license (BSD-3) không phải vấn đề.
- **Ước tính:** tái dùng số đã có ở `06` cho Pha 3 (4–6 dev-week) **+ thêm 1–2 tuần** cho phần tích
  thể tích heightfield + render mesh riêng (ARuler làm, doc hiện tại của mình mới tính tới OBB, chưa
  tính heightfield) → **~5–8 dev-week, rủi ro R&D giữ nguyên mức cao như R5 đã ghi.**

## 3. Rủi ro pháp lý cần chốt trước khi bắt tay

- **AAMED**: cần tự tra lại license của mã nguồn mở AAMED (paper/repo gốc) trước khi dùng thương
  mại — nhiều code học thuật đi kèm license nghiên cứu, không mặc định cho phép dùng sản phẩm bán
  tiền. Đây là **must-check**, không giả định.
- **PCL**: BSD-3-Clause, dùng thương mại được, ghi attribution theo license là đủ.
- **OpenCV**: Apache 2.0, dùng thương mại được.
- Tuyệt đối không lấy `libcircle-detector-lib.so` / `libpcl-lib.so` của ARuler bỏ thẳng vào app —
  đó là binary compile riêng của Grymala (có thể đã tinh chỉnh khác bản gốc), không phải mã nguồn
  mở tải công khai. Việc cần làm là **tự build lại từ mã nguồn mở gốc**, không phải lấy file `.so`
  này.

## 4. Câu hỏi chưa xác định được

- TensorFlow Lite (+ GPU delegate) trong ARuler dùng cho tính năng gì — không tìm được model
  `.tflite` hay symbol JNI nào tham chiếu trực tiếp trong Phase 1. Có thể dùng cho 1 feature khác
  không phải đo (ví dụ nhận diện loại vật thể để gợi ý tool phù hợp), hoặc model được tải về từ
  server lúc runtime (không nằm trong APK). Không quan trọng cho quyết định fork 2 tool trên, nhưng
  cần Phase 3 (đọc `AndroidManifest`-declared entry points) nếu muốn biết chắc.
- License gốc của AAMED (nghiên cứu hay permissive) — chưa tra, cần làm trước khi code Tool A.
- ARuler xử lý heightfield/mesh của heap theo thuật toán cụ thể nào (marching squares? triangulation
  đơn giản?) — không xác định được ở Phase 1, cần Phase 3 nếu quyết định làm Tool B.

## 5. Khuyến nghị

1. **Tool A (circle/ellipse) — làm được, effort thấp, nên đưa vào scope gần** (đứng sau Pha 1 M1/M2
   trong `06`, trước khi tính tới Pha 3). Rào cản chính là kiểm tra license AAMED — nếu vướng, dùng
   Hough Circle Transform của OpenCV thay thế, chấp nhận độ chính xác thấp hơn một chút.
2. **Tool B (heap volume) — không nên ưu tiên.** Nó cộng thêm rủi ro/effort lên trên rủi ro R&D đã
   ghi nhận sẵn ở Pha 3 (`06` risk R5), lại còn kéo theo phải đổi kiến trúc render (từ 2D overlay
   sang mesh 3D thật) — hai rủi ro chồng lên nhau. Chỉ cân nhắc **sau khi** Pha 3 (M5 — OBB) đã chạy
   ổn và có số liệu % user tier FULL đủ cao (theo R2 trong `06`).
