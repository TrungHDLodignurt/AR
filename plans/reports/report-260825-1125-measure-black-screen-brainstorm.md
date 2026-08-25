# Brainstorm + fix: Measure tab đen sau khi background/resume

## Problem
Tab AR "Measure" hiện đen sì sau khi user rời app (bấm home) rồi quay lại, chỉ hết khi xoá cache/kill process. Bug ban đầu report ở tab Photo (Canny/Hough), không liên quan — đây là tab AR riêng (ARSceneView/ARCore), package `measure/`.

## Root cause — xác nhận bằng log thật trên Pixel 6, không suy đoán
Log native ARCore lộ nguyên văn lỗi:
```
texture names are not set. [ArStatusErrorSpace::AR_ERROR_TEXTURE_NOT_SET]
```
lặp lại mỗi frame, không tự hết dù chờ >90s (baseline test không can thiệp gì). Nguyên nhân: thư viện `io.github.sceneview:arsceneview:4.31.0` không re-bind camera GL texture với ARCore Session đúng lúc sau khi app background/resume (surface GL bị OS huỷ rồi tạo lại, texture cũ mất hiệu lực).

## Approaches evaluated
1. **Watchdog remount ARSceneView only** (fix đầu tiên, đã ship trước brainstorm) — không ổn định: 2 lần test cho kết quả mâu thuẫn (hồi phục sau ~40s, hoặc kẹt >2 phút). Do `engine`/`materialLoader` (Filament Engine) sống NGOÀI phạm vi remount nên texture lỗi không được reset.
2. **Port kiến trúc app tham chiếu** (ARuler, com.craftars.measuretools) — loại bỏ. ARuler tự viết renderer Vulkan riêng; measuretools là app Unity (`com.unity3d.player.UnityPlayerActivity`, xác nhận qua `aapt2 dump badging`). Cả 2 đều thay hẳn kiến trúc rendering, không portable, vi phạm YAGNI.
3. **Remount cả Engine + Session** (đã chọn) — đưa `rememberEngine()`/`rememberMaterialLoader()` vào trong `key(instanceKey)` cùng `ARSceneView`, để watchdog phá + tạo lại TOÀN BỘ Filament state, không chỉ ARCore Session.

## Kết quả sau fix #3
- `AR_ERROR_TEXTURE_NOT_SET`: **0 lần** trong test sau fix (trước đó: hàng trăm lần/phút). Bug gốc đã hết.
- Test tự động (adb, máy nằm yên trên bàn) vẫn thấy watchdog remount lặp lại nhiều vòng không hồi phục — nhưng log cho thấy nguyên nhân KHÁC hẳn: `FEATURE_DSP_RANSAC_INLIER_INSUFFICIENT (0/20)`, `VIO_OUTPUT_NOT_TRACKING` — ARCore không track được vì thiếu chuyển động/feature hình ảnh (máy đứng yên), không phải bug. Camera vẫn mở/stream liên tục bình thường suốt (`CameraService: Start/Stop camera streaming`), chỉ là tracking chưa init được.
- **Giới hạn của investigation:** không validate được bằng adb tự động "bao lâu thì hồi phục" vì ARCore cần chuyển động thật — cần user test tay (cầm máy di chuyển sau khi resume) để xác nhận cuối.

## Final state
- `MeasureScreen.kt`: `engine`/`materialLoader` giờ nằm trong `key(instanceKey)`; watchdog giữ nguyên 6s timeout, 1s poll.
- `MeasureState.kt`: thêm `lastFrameAtMillis` (Compose state, không phải plain var — tránh race cross-thread).
- Log debug tạm (`MeasureDiag`) đã xoá sạch, không commit.
- Build/test: compile sạch, `testDebugUnitTest` pass, cài & smoke-test trên Pixel 6 OK.

## Update 260825: root cause thật + fix cuối cùng đã verify OK trên device

Sau update trên, tiếp tục điều tra sâu với user (nhiều vòng test tay + log thật trên Pixel 6, POCO X7, đối chiếu Samsung không dính) — kết luận cuối:

### Root cause thật (không phải texture-not-set do background nữa)
Bắt được full Java stack trace lúc màn đen:
```
com.google.ar.core.exceptions.TextureNotSetException
    at com.google.ar.core.Session.update(Session.java)
    at io.github.sceneview.ar.arcore.ARSession.updateOrNull(ArSession.kt:155)
    at io.github.sceneview.SceneRenderer.renderFrame(SceneRenderer.kt:251)
```
Đọc source `arsceneview` 4.31.0 (`SceneRenderer.kt`): vòng render chỉ chờ `swapChain` sẵn sàng + `isResumed`, **không chờ** `session.setCameraTextureNames()` (chạy trong `onSessionCreated`, thread/timing khác) đã xong hay chưa. Đây là race giữa 2 việc độc lập — khớp bug đã biết của chính Google ARCore SDK (github.com/google-ar/arcore-android-sdk issue #1170).

**Race phụ thuộc tốc độ GPU/camera driver từng máy** — test thực tế: Pixel 6 và POCO X7 đều dính (gần như mọi lần cold-start), Samsung không dính. Không phải bug cố định 100%, mà cửa sổ race hẹp/rộng tuỳ driver.

### Các hướng đã thử và bị loại (ghi lại để khỏi lặp lại)
1. Remount `ARSceneView` mỗi khi stall >Ns (watchdog) — hoạt động nhưng không ổn định qua nhiều lần test (đôi khi hồi phục ~40s, đôi khi kẹt >2 phút).
2. Remount cả Filament Engine cùng Session (đưa `engine`/`materialLoader` vào `key(instanceKey)`) — **làm TỆ HƠN**: gần như 100% fail kể cả cold-start. Engine là object nặng giữ tài nguyên GPU, phá/tạo liên tục (~10s/lần do watchdog) nhiều khả năng để lại tài nguyên GPU dở dang, làm race tệ thêm. Đã revert về Engine tạo 1 lần duy nhất.
3. Remount chủ động mỗi lần resume từ background — **làm TỆ HƠN**: phá cả session đang chạy tốt (background ngắn trước đó vốn ổn), tạo race đóng/mở camera mới không cần thiết. Đã revert, chỉ còn reset lại đồng hồ watchdog lúc resume (không remount).
4. Port kiến trúc từ app tham chiếu (ARuler dùng Vulkan tự viết, `com.craftars.measuretools` là app Unity — xác nhận qua `aapt2 dump badging`) — không portable, đổi hẳn kiến trúc rendering, quá tay.
5. Watchdog đếm giờ cả lúc app đang ở background (bug phụ phát hiện thêm) — đã fix: giờ watchdog reset đồng hồ liên tục lúc backgrounded, chỉ đếm thời gian foreground thật.

### Fix cuối cùng — đã user xác nhận OK trên device
User tự test tay phát hiện: bấm qua tab khác rồi quay lại tab Measure (không sửa code gì) → hết đen ngay. Tức là chỉ cần cho GPU/camera driver 2-3 giây "khởi động" trước khi mount AR là né được race.

**Implement:** delay `ArWarmupDelayMs = 2000L` trước khi mount `ARSceneView` lần đầu tiên trong mỗi process (flag top-level `hasAttemptedArWarmup`, reset khi kill app/process mới, không delay lại khi chỉ chuyển tab trong cùng phiên). Hiện hint "Getting the camera ready…" trong lúc chờ thay vì màn đen im lặng.

- File sửa: `MeasureScreen.kt` (delay warmup + revert Engine/resume-remount), `MeasureState.kt` (`lastFrameAtMillis`), `strings.xml` (`hint_warming_up`).
- **User xác nhận: kill app → mở lại → hết đen.**
- Watchdog (10s, chỉ đếm foreground time) giữ lại làm lưới an toàn phụ cho case hiếm khác.

## Next steps / unresolved
- Chưa test case "background thật lâu" (nhiều phút) với bản fix cuối — nên verify thêm nếu có thời gian.
- Chưa xoá temp diagnostic log (`MeasureDiag`) khỏi lịch sử — đã xoá khỏi code hiện tại, không còn trong working tree.
- Chưa commit — theo rule chỉ commit khi user yêu cầu.
