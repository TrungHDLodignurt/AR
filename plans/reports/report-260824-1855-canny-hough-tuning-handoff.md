# Handoff: tinh chỉnh Canny+Hough auto-fit quad cho ảnh thật

**Mục tiêu:** tab "Photo" (đo qua ảnh) khi tap vào vật tham chiếu phải tự fit khung sát cạnh thật (giống ARuler dùng FastSAM), nhưng bằng Canny+Hough thuần Kotlin — không ML, không license risk (xem lý do chọn hướng này ở cuối file).

## Bundle app mẫu để tham chiếu
- **Package:** `com.grymala.aruler` (Play Store: "ARuler")
- Đã decompile sẵn, KHÔNG cần pull lại: `/private/tmp/claude-501/-Users-admin-ahndroidne-StudioProjects-ar-tape-measure/ac1df39a-8527-4bf9-8cbd-8addcb66b74a/scratchpad/aruler/`
  - `res-out/resources/AndroidManifest.xml` — manifest decode
  - `src-out/sources/` — full decompiled (jadx, R8-obfuscated nhưng class có tên trong manifest giữ nguyên)
- Tính năng đang port: `presentation.photomeasure.referencescene.PhotorulerActivity` (đã port xong luồng UX — chọn ref trước, tap-to-reveal, kéo quad có kính lúp). Cơ chế auto-fit thật của ARuler dùng **FastSAM** (`fastsam_s.tflite`, tải on-demand qua Play Asset Delivery) — **không port trực tiếp được** vì FastSAM build trên Ultralytics YOLOv8, license AGPL-3.0 (copyleft mạnh — dùng phải mở source app hoặc mua license thương mại). Đây là lý do chọn Canny+Hough thay thế, KHÔNG cố port FastSAM.

## Code liên quan (package `vn.quancua.artapemeasure.photomeasure`)
- `GrayscaleImage.kt` — ảnh grayscale + Gaussian blur (pure, không Android type)
- `CannyEdgeDetector.kt` — Sobel, non-max suppression, hysteresis threshold (percentile adaptive)
- `HoughTransform.kt` — Hough line transform, có `maxLines` (đã tăng 12→40, xem dưới)
- `QuadFromEdges.kt` — chọn 4 đường quanh điểm tap, giao nhau ra quad, auto-orient long/short
- `AutoFitQuad.kt` — entry point nhận `Bitmap` thật + điểm tap, crop window 480px, gọi 3 file trên
- `PhotoMeasureState.kt` — hàm `revealQuadAt` (suspend) gọi `autoFitQuad`, fallback về khung mặc định nếu null. Có `android.util.Log.d("PhotoMeasure", "autoFitQuad tap=... result=...")` để debug qua logcat (tag: `PhotoMeasure`).
- Test pure: `app/src/test/java/vn/quancua/artapemeasure/photomeasure/QuadFromEdgesTest.kt` (ảnh giả lập, PASS — không đại diện ảnh thật)

## Đã làm & kết quả
1. **Fix 1 (đã commit `4fc3bea`):** `maxLines` trong `HoughTransform.kt` từ 12 → 40. Nguyên nhân: nền thật (hoạ tiết lót chuột) sinh nhiều đường mạnh, cạnh vật tham chiếu bị loại khỏi top-12 trước khi `quadFromLines` được xét. Verify bằng ảnh thật (điện thoại trên lót chuột) → fit sát cạnh, xác nhận bằng cách vẽ quad đè lên ảnh edge-map.
2. **Case fail mới (CHƯA fix):** ảnh khác — điện thoại trên bàn sáng màu, nhiều vật khác cùng khung (ống kim loại, dao, dây cáp, khăn giấy). Log thật:
   ```
   PhotoMeasure: autoFitQuad tap=Vec2(x=1328.1951, y=709.20166) result=null
   ```
   → vẫn fallback về khung mặc định. Giả thuyết chưa verify: cửa sổ crop 480px quanh điểm tap bắt luôn cạnh của vật KHÁC (ống kim loại/dao) mạnh hơn cạnh điện thoại, hoặc bối cảnh sáng/tương phản cao làm ngưỡng percentile chọn nhầm. **Chưa xác nhận bằng số liệu — cần đào lại đúng cách ở mục dưới, không đoán.**

## Phương pháp tinh chỉnh (không cần cài APK mỗi lần — nhanh)
Đã dùng cách này để tìm ra fix 1, lặp lại cho case mới:
1. Lấy 1 ảnh thật KHÔNG có overlay UI vẽ đè (ảnh gốc sạch). Nếu có video màn hình quay lúc test, dùng `ffmpeg -ss <giây lẻ> -i video.mp4 -update 1 -frames:v 1 out.png` dò đúng khung hình trước khi quad xuất hiện.
2. Viết file test tạm `app/src/test/java/vn/quancua/artapemeasure/photomeasure/RealPhotoDiagnosticTest.kt` (XOÁ sau khi xong, không commit) — dùng `javax.imageio.ImageIO` (pure JDK, không cần Robolectric/device) để load PNG, crop vùng quanh điểm tap thật (lấy toạ độ từ log `PhotoMeasure` hoặc ước lượng), chạy trực tiếp `cannyEdges` → `houghLines` → `quadFromLines`, in ra: số edge pixel, top N hough lines (theta/rho/votes), kết quả quad.
3. Vẽ quad kết quả đè lên ảnh edge-map (dùng `BufferedImage` + `Graphics2D`, `ImageIO.write`) rồi `Read` file đó để XEM TRỰC QUAN đúng/sai — đừng chỉ tin số tọa độ, đã có lần đoán sai vì số trông hợp lý nhưng hình sai (tap point đặt lệch ngoài vật thể).
4. Chạy: `./gradlew :app:testDebugUnitTest --tests "vn.quancua.artapemeasure.photomeasure.RealPhotoDiagnosticTest" --console=plain -i 2>&1 | grep -A100 "..."`
5. Sửa param trong file production (`HoughTransform.kt`, `CannyEdgeDetector.kt`, `QuadFromEdges.kt`, `AutoFitQuad.kt` windowSizePx), lặp lại bước 4 tới khi quad fit đúng, rồi mới build APK cài máy thật để user xác nhận cuối.
6. **Nhớ xoá file test tạm trước khi commit.**

## Máy test đang có (adb)
- Pixel 6: `18311FDF60085N` (ARCore certified, cho phép `adb input tap/swipe` — không bị chặn)
- POCO X7: `Q88PCM4TOZDUY9QK` (MIUI, **chặn** `adb input` — không tự động hoá tap được, phải nhờ user)

## Git
- Branch: `feature/photo-reference-measure` (không phải `main`)
- Commit gần nhất liên quan: `4fc3bea` (fix maxLines), `2c16de1` (không liên quan — AR tab, package `measure/`, đừng đụng khi làm việc này)
- **Chỉ `git add` đúng file đã sửa trong `photomeasure/`** — không dùng `git add -A`/`git add .` vì có thể có session/agent khác đang sửa file khác song song trong repo.

## Câu hỏi/rủi ro chưa giải quyết
- Case ảnh sáng/nhiều vật thể (log trên) — nguyên nhân thật chưa xác nhận bằng ảnh gốc (không tìm thấy trên 2 máy adb hiện có, scratchpad ảnh/video cũ cũng đã bị dọn). Cần user cung cấp lại ảnh/video repro để đào tiếp theo đúng quy trình ở mục "Phương pháp tinh chỉnh".
- Chưa có cách phân biệt UI-wise cho user biết "đây là auto-detect thật hay fallback" — hiện chỉ có log, không có tín hiệu trên UI.

## Update 260824-1928: fix bug kiến trúc (không phải tuning) trong `QuadFromEdges.kt`
**Phát hiện qua code review, không qua ảnh thật** (không có ảnh case 2 để test) — nhưng là bug rõ ràng, độc lập với ảnh cụ thể nào:

`quadFromLines` bản cũ chỉ tìm đường Hough **gần 0°/90° so với trục ảnh** (`angleToleranceDegrees=20°`), rồi mới lấy đường gần điểm tap nhất mỗi phía. Nếu vật tham chiếu bị chụp nghiêng > 20° so với khung ảnh (rất thường gặp — user không luôn đặt vật song song cạnh ảnh), `horizontalLines`/`verticalLines` đều rỗng → trả `null` ngay dù Canny/Hough tìm cạnh hoàn hảo. Toàn bộ test cũ (`QuadFromEdgesTest.kt`) chỉ test rectangle thẳng trục nên gap này chưa lộ ra — đây rất có thể là 1 phần nguyên nhân case 2 (không loại trừ khả năng còn nguyên nhân khác, vẫn cần ảnh thật để xác nhận).

**Fix:** thử MỌI hướng của các đường Hough phát hiện được làm trục "primary" (không cố định 0°/90°), ghép với trục vuông góc làm "secondary", chọn cặp cho ra quad hợp lệ (chứa điểm tap, diện tích không suy biến) có tổng vote cao nhất. Tự động tổng quát hoá cho mọi góc xoay, đồng thời tự nhiên loại cạnh của vật khác trong khung (vì cạnh vật lạ hiếm khi bao trọn điểm tap).

- File sửa: `QuadFromEdges.kt` (logic), `QuadFromEdgesTest.kt` (thêm test rectangle xoay 35°, dùng để verify fix — PASS, 4/4 test cũ vẫn PASS).
- **Chưa build APK / test trên ảnh thật** — cần ảnh/video case 2 (hoặc ảnh nghiêng bất kỳ) để verify theo đúng quy trình "vẽ quad đè lên edge-map" trước khi coi là xong.
- **Không đụng** `windowSizePx=480` trong `AutoFitQuad.kt` dù nghi ngờ có thể quá nhỏ so với ảnh full-res 2048px (vật to hơn 480px có thể bị crop mất cạnh xa) — đây là đánh đổi 2 chiều (tăng window = dễ dính vật khác hơn, đúng vấn đề case 2), không có ảnh thật thì không nên đoán số.

**Status cuối (260825):** đã commit `f3ad223`. Xem tóm tắt kỹ thuật đầy đủ ở
`report-260825-1703-session-handoff-box-cylinder-measure.md` §9. Vẫn CHƯA verify trên ảnh thật case
2 (chỉ test synthetic) — vẫn là câu hỏi mở, mục windowSizePx=480 vẫn chưa đụng.
