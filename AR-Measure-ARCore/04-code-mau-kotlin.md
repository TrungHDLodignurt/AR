# 04 — Code mẫu Kotlin (Android native + ARCore)

> Toàn bộ code dưới đây dùng đúng tên class/method theo tài liệu tham chiếu chính thức của ARCore.
> Phần nào là *suy luận/đề xuất* (không có trong docs) đều được đánh dấu `// ⚠️ CẦN VERIFY`.

---

## 0. Thiết lập project

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

## 1. Phát hiện năng lực thiết bị & phân tier

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

## 2. Cấu hình Session

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

## 3. Hit-test có ưu tiên trackable — trái tim của việc chọn điểm đo

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

## 4. Đo khoảng cách 2 điểm

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

## 5. Đọc Depth & unproject pixel → điểm 3D world-space

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

## 6. Bounding box: dựng OBB từ point cloud (khung xương)

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

## 7. Giám sát chất lượng tracking → thông điệp hướng dẫn

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

## 8. Ghi & phát lại session cho kiểm thử hồi quy

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

## 9. Checklist các bug thường gặp

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

## 10. Phụ lục: các tên API đã đối chiếu với tài liệu tham chiếu chính thức

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
