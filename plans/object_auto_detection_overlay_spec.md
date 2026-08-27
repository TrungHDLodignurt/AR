# Object Auto Detection & Boundary Overlay — Android Kotlin

## 1. Mục tiêu

Implement tính năng Android Kotlin cho phép:

1. Người dùng chọn/chụp một ảnh.
2. Người dùng tap vào vật thể cần detect.
3. Hệ thống tự động tìm boundary/silhouette của vật thể.
4. Xác định 4 góc nếu vật thể là hình chữ nhật.
5. Vẽ một quadrilateral overlay bám sát viền vật thể trên ảnh.
6. Biết trước kích thước thực tế:
   - cạnh dài = X cm
   - cạnh ngắn = Y cm
7. Dùng kích thước đã biết như một geometric constraint để loại detection sai.
8. Trả về confidence score.
9. Nếu confidence thấp, cho phép người dùng kéo chỉnh 4 góc thủ công.

> Lưu ý quan trọng:
> Kích thước thực tế X × Y chỉ đủ để validate/constraint hình học.
> Không được tự suy ra pixel/cm tuyệt đối chỉ từ một ảnh nếu chưa có camera
> calibration, reference object hoặc scale factor hợp lệ.

---

# 2. Tech Stack

- Kotlin
- Android
- Jetpack Compose
- MVVM / Clean Architecture
- OpenCV
- Coroutines

---

# 3. Detection Pipeline

Pipeline chính:

```text
Photo / Camera
      ↓
EXIF Orientation
      ↓
Resize for processing
      ↓
User tap
      ↓
Map UI coordinate → image coordinate
      ↓
Create ROI around tap
      ↓
Grayscale
      ↓
Gaussian Blur
      ↓
Canny Edge Detection
      ↓
Find External Contours
      ↓
Select contour using tap point
      ↓
Filter candidates
      ↓
ApproxPolyDP
      ↓
Find 4 corners
      ↓
Order corners
      ↓
Validate rectangle
      ↓
Validate expected aspect ratio
      ↓
Calculate confidence
      ↓
DetectionResult
      ↓
Map image coordinates → Compose coordinates
      ↓
Draw overlay
```

Không sử dụng Hough Transform làm thuật toán chính.

Hough chỉ là fallback tùy chọn khi contour bị đứt hoặc không tìm được polygon tốt.

---

# 4. Data Models

## KnownDimension

```kotlin
data class KnownDimension(
    val longCm: Double,
    val shortCm: Double
)
```

Ví dụ:

```kotlin
val knownDimension = KnownDimension(
    longCm = 120.0,
    shortCm = 60.0
)
```

## DetectionResult

```kotlin
data class DetectionResult(
    val corners: List<Point>,
    val confidence: Double,
    val longSidePx: Double,
    val shortSidePx: Double,
    val estimatedLongCm: Double?,
    val estimatedShortCm: Double?,
    val isPerspectiveDetected: Boolean
)
```

## ImagePoint

Có thể dùng OpenCV `Point`, nhưng nên có model riêng nếu cần tách domain khỏi OpenCV:

```kotlin
data class ImagePoint(
    val x: Double,
    val y: Double
)
```

---

# 5. Detector Interface

Tạo abstraction:

```kotlin
interface ObjectDetector {

    suspend fun detect(
        image: Mat,
        tapPoint: Point,
        knownDimension: KnownDimension
    ): DetectionResult?
}
```

Implementation:

```text
OpenCVObjectDetector
```

Không để OpenCV code trực tiếp trong Composable/ViewModel.

---

# 6. Step 1 — Convert Bitmap → Mat

```kotlin
fun bitmapToMat(bitmap: Bitmap): Mat {

    val mat = Mat()

    Utils.bitmapToMat(
        bitmap,
        mat
    )

    return mat
}
```

---

# 7. Step 2 — Handle EXIF Orientation

Trước khi detection phải đảm bảo ảnh đã được rotate đúng orientation.

Các orientation cần xử lý:

- NORMAL
- ROTATE_90
- ROTATE_180
- ROTATE_270
- FLIP_HORIZONTAL
- FLIP_VERTICAL

Không được detect trên ảnh chưa normalize EXIF orientation vì overlay có thể lệch so với ảnh hiển thị.

---

# 8. Step 3 — Resize ảnh để Detection

Không chạy OpenCV trực tiếp trên ảnh camera 4000×3000 nếu không cần.

Đề xuất:

```text
maximum dimension = 1600 px
```

Code:

```kotlin
fun resizeForDetection(src: Mat): Mat {

    val result = Mat()

    val maxSize = 1600.0

    val scale = minOf(
        maxSize / src.width(),
        maxSize / src.height(),
        1.0
    )

    Imgproc.resize(
        src,
        result,
        Size(
            src.width() * scale,
            src.height() * scale
        )
    )

    return result
}
```

Quan trọng:

Phải lưu `scale` để map tọa độ detection từ processing image về original image.

---

# 9. Step 4 — User Tap

UI:

```text
┌──────────────────────────────┐
│                              │
│       ┌─────────────┐        │
│       │             │        │
│       │   OBJECT    │        │
│       │      •      │ ← TAP  │
│       └─────────────┘        │
│                              │
└──────────────────────────────┘
```

Khi tap:

```kotlin
data class ImagePoint(
    val x: Float,
    val y: Float
)
```

Map từ Compose/View coordinates → image coordinates.

---

# 10. Step 5 — Coordinate Mapping

Không được giả định:

```text
Canvas size == Image size
```

Ví dụ:

```text
Image:
1920 × 1080

Compose:
1080 × 720
```

Cần transform chính xác.

Nếu dùng `ContentScale.Fit`:

```kotlin
fun mapTouchToImageFit(
    touchX: Float,
    touchY: Float,
    viewWidth: Float,
    viewHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): Point {

    val scale = minOf(
        viewWidth / imageWidth,
        viewHeight / imageHeight
    )

    val displayedWidth =
        imageWidth * scale

    val displayedHeight =
        imageHeight * scale

    val offsetX =
        (viewWidth - displayedWidth) / 2f

    val offsetY =
        (viewHeight - displayedHeight) / 2f

    val imageX =
        (touchX - offsetX) / scale

    val imageY =
        (touchY - offsetY) / scale

    return Point(
        imageX.toDouble(),
        imageY.toDouble()
    )
}
```

Nếu dùng `ContentScale.Crop`, phải implement transform riêng cho Crop.

Không dùng công thức đơn giản:

```kotlin
touchX / viewWidth * imageWidth
```

khi ảnh đang Fit/Crop.

---

# 11. Step 6 — Create ROI

Không cần process toàn bộ ảnh.

Tạo ROI xung quanh tap point.

```kotlin
fun createRoi(
    image: Mat,
    center: Point,
    radiusX: Int,
    radiusY: Int
): Rect {

    val left = maxOf(
        0,
        (center.x - radiusX).toInt()
    )

    val top = maxOf(
        0,
        (center.y - radiusY).toInt()
    )

    val right = minOf(
        image.width(),
        (center.x + radiusX).toInt()
    )

    val bottom = minOf(
        image.height(),
        (center.y + radiusY).toInt()
    )

    return Rect(
        left,
        top,
        right - left,
        bottom - top
    )
}
```

Sau đó:

```kotlin
val roiMat = Mat(
    image,
    roi
)
```

Tap point phải được convert sang ROI-local coordinate:

```kotlin
val localTapPoint = Point(
    tapPoint.x - roi.x,
    tapPoint.y - roi.y
)
```

---

# 12. Step 7 — Grayscale

```kotlin
val gray = Mat()

Imgproc.cvtColor(
    roiMat,
    gray,
    Imgproc.COLOR_BGR2GRAY
)
```

---

# 13. Step 8 — Gaussian Blur

```kotlin
val blurred = Mat()

Imgproc.GaussianBlur(
    gray,
    blurred,
    Size(5.0, 5.0),
    0.0
)
```

---

# 14. Step 9 — Canny

```kotlin
val edges = Mat()

Imgproc.Canny(
    blurred,
    edges,
    50.0,
    150.0
)
```

Có thể expose thresholds qua config:

```kotlin
data class DetectionConfig(
    val cannyLow: Double = 50.0,
    val cannyHigh: Double = 150.0,
    val gaussianKernel: Int = 5,
    val approxEpsilonRatio: Double = 0.02,
    val minConfidence: Double = 0.75
)
```

---

# 15. Step 10 — Find External Contours

```kotlin
val contours = mutableListOf<MatOfPoint>()
val hierarchy = Mat()

Imgproc.findContours(
    edges,
    contours,
    hierarchy,
    Imgproc.RETR_EXTERNAL,
    Imgproc.CHAIN_APPROX_SIMPLE
)
```

Chỉ dùng:

```text
RETR_EXTERNAL
```

ở bước đầu để tránh lấy quá nhiều contour bên trong object.

---

# 16. Step 11 — Select Contour Using Tap Point

Không được chỉ chọn:

```kotlin
contours.maxByOrNull {
    Imgproc.contourArea(it)
}
```

vì contour lớn nhất có thể là background.

Ưu tiên contour chứa tap:

```kotlin
fun containsPoint(
    contour: MatOfPoint,
    point: Point
): Boolean {

    val contour2f =
        MatOfPoint2f(*contour.toArray())

    return Imgproc.pointPolygonTest(
        contour2f,
        point,
        false
    ) >= 0.0
}
```

Filter:

```kotlin
val candidates = contours.filter {
    containsPoint(
        it,
        localTapPoint
    )
}
```

Nếu không có candidate chứa tap:

```text
Detection failed
```

Không tự động chọn một object khác ở xa tap nếu requirement yêu cầu user tap đúng object.

---

# 17. Step 12 — Filter Candidate Contours

Filter theo:

### Area

```kotlin
val area = Imgproc.contourArea(contour)
```

Reject nếu quá nhỏ.

### Bounding box

```kotlin
val rect =
    Imgproc.boundingRect(contour)
```

### Aspect ratio

Expected:

```kotlin
val expectedRatio =
    knownDimension.longCm /
    knownDimension.shortCm
```

Detected:

```kotlin
val width = rect.width.toDouble()
val height = rect.height.toDouble()

val detectedRatio =
    maxOf(width, height) /
    minOf(width, height)
```

Không dùng bounding box ratio làm tiêu chí duy nhất.
Sau khi có 4 corners phải tính ratio theo actual polygon sides.

---

# 18. Step 13 — ApproxPolyDP

```kotlin
val contour2f =
    MatOfPoint2f(*contour.toArray())

val perimeter =
    Imgproc.arcLength(
        contour2f,
        true
    )

val epsilon =
    0.02 * perimeter

val approx =
    MatOfPoint2f()

Imgproc.approxPolyDP(
    contour2f,
    approx,
    epsilon,
    true
)
```

Ưu tiên:

```text
approx points == 4
```

Nếu không phải 4:

- thử epsilon nhỏ hơn/lớn hơn
- thử morphology
- thử fallback edge processing
- cuối cùng mới cân nhắc Hough

Không tự động coi mọi contour 4 điểm là rectangle hợp lệ.

---

# 19. Step 14 — Order 4 Corners

Output phải theo thứ tự:

```text
P1 = top-left
P2 = top-right
P3 = bottom-right
P4 = bottom-left
```

Implementation:

```kotlin
fun orderPoints(
    points: Array<Point>
): Array<Point> {

    require(points.size == 4)

    val sortedBySum =
        points.sortedBy {
            it.x + it.y
        }

    val topLeft =
        sortedBySum.first()

    val bottomRight =
        sortedBySum.last()

    val remaining =
        sortedBySum.subList(1, 3)

    val topRight =
        remaining.maxBy {
            it.x - it.y
        }

    val bottomLeft =
        remaining.minBy {
            it.x - it.y
        }

    return arrayOf(
        topLeft,
        topRight,
        bottomRight,
        bottomLeft
    )
}
```

---

# 20. Step 15 — Calculate Side Lengths

```kotlin
fun distance(
    p1: Point,
    p2: Point
): Double {

    return hypot(
        p2.x - p1.x,
        p2.y - p1.y
    )
}
```

Calculate:

```kotlin
val top =
    distance(
        ordered[0],
        ordered[1]
    )

val right =
    distance(
        ordered[1],
        ordered[2]
    )

val bottom =
    distance(
        ordered[2],
        ordered[3]
    )

val left =
    distance(
        ordered[3],
        ordered[0]
    )
```

Long side:

```kotlin
val longSidePx =
    maxOf(
        top,
        right,
        bottom,
        left
    )
```

Short side:

```kotlin
val shortSidePx =
    minOf(
        top,
        right,
        bottom,
        left
    )
```

Tốt hơn nữa là average hai cạnh đối diện:

```text
longPx =
    (top + bottom) / 2

shortPx =
    (left + right) / 2
```

sau khi đã xác định orientation.

---

# 21. Step 16 — Validate Rectangle

## A. Opposite sides

```text
top ≈ bottom
left ≈ right
```

Tính relative error:

```kotlin
fun relativeError(
    a: Double,
    b: Double
): Double {

    return abs(a - b) /
        maxOf(a, b)
}
```

Ví dụ:

```kotlin
val horizontalError =
    relativeError(top, bottom)

val verticalError =
    relativeError(left, right)
```

---

# 22. Step 17 — Validate 90-Degree Angles

Tạo hàm tính góc giữa 3 điểm.

```kotlin
fun angle(
    a: Point,
    b: Point,
    c: Point
): Double {

    val abx = a.x - b.x
    val aby = a.y - b.y

    val cbx = c.x - b.x
    val cby = c.y - b.y

    val dot =
        abx * cbx +
        aby * cby

    val mag1 =
        hypot(abx, aby)

    val mag2 =
        hypot(cbx, cby)

    val cos =
        dot /
        (mag1 * mag2)

    return Math.toDegrees(
        acos(
            cos.coerceIn(-1.0, 1.0)
        )
    )
}
```

Check:

```text
angle ≈ 90°
```

Không bắt buộc tuyệt đối 90° vì perspective có thể làm hình chữ nhật thành quadrilateral trong ảnh.

---

# 23. Step 18 — Validate Expected Aspect Ratio

Known:

```kotlin
val expectedRatio =
    knownDimension.longCm /
    knownDimension.shortCm
```

Detected:

```kotlin
val detectedRatio =
    longSidePx /
    shortSidePx
```

Error:

```kotlin
val aspectError =
    abs(
        detectedRatio -
        expectedRatio
    ) / expectedRatio
```

Dùng aspect ratio để reject candidate quá sai.

Ví dụ:

```text
Expected = 2.0

Detected = 1.98
→ good

Detected = 1.20
→ reject
```

Perspective mạnh có thể khiến ratio pixel lệch, vì vậy không dùng threshold quá chặt.

---

# 24. Step 19 — Confidence Score

Không dùng:

```kotlin
points.size == 4
```

làm success condition duy nhất.

Confidence nên kết hợp:

```text
Tap inside contour
        +
4 corners
        +
Aspect ratio
        +
Opposite side consistency
        +
Angle consistency
        +
Contour area
        +
Rectangle quality
```

Model:

```kotlin
data class ConfidenceComponents(
    val tapScore: Double,
    val aspectScore: Double,
    val sideScore: Double,
    val angleScore: Double,
    val areaScore: Double
)
```

Ví dụ:

```kotlin
fun calculateConfidence(
    components: ConfidenceComponents
): Double {

    return (
        components.tapScore * 0.20 +
        components.aspectScore * 0.30 +
        components.sideScore * 0.20 +
        components.angleScore * 0.20 +
        components.areaScore * 0.10
    ).coerceIn(0.0, 1.0)
}
```

Threshold đề xuất ban đầu:

```text
>= 0.85
    auto accept

0.70 - 0.85
    accept but show uncertainty / allow adjustment

< 0.70
    detection uncertain → manual adjustment
```

Các threshold này cần tuning bằng test dataset thực tế.

---

# 25. Step 20 — Perspective Transform

Nếu tìm được 4 corners:

```kotlin
val srcPoints =
    MatOfPoint2f(
        ordered[0],
        ordered[1],
        ordered[2],
        ordered[3]
    )
```

Target:

```kotlin
val targetWidth = 1200.0
val targetHeight = 600.0

val dstPoints =
    MatOfPoint2f(
        Point(0.0, 0.0),
        Point(targetWidth, 0.0),
        Point(targetWidth, targetHeight),
        Point(0.0, targetHeight)
    )
```

Matrix:

```kotlin
val transform =
    Imgproc.getPerspectiveTransform(
        srcPoints,
        dstPoints
    )
```

Warp:

```kotlin
val warped = Mat()

Imgproc.warpPerspective(
    image,
    warped,
    transform,
    Size(
        targetWidth,
        targetHeight
    )
)
```

Perspective correction chỉ dùng khi cần xử lý/hiệu chỉnh hình học.

Không cần warp ảnh gốc chỉ để vẽ overlay.

---

# 26. Step 21 — Pixel → CM

Rất quan trọng:

Không được làm:

```kotlin
pxPerCm = detectedPx / knownCm
```

rồi tuyên bố đó là scale tuyệt đối nếu ảnh không có calibration.

Known dimensions nên được dùng để:

```text
1. Validate aspect ratio
2. Reject wrong contour
3. Constrain rectangle fitting
```

Để đo cm thực sự cần một trong các nguồn scale:

```text
Camera calibration
OR
Known reference object
OR
ARCore / depth / plane measurement
OR
Known camera distance + calibrated intrinsics
```

Có thể thiết kế thêm:

```kotlin
data class ScaleInfo(
    val pixelsPerCm: Double,
    val source: ScaleSource
)

enum class ScaleSource {
    REFERENCE_OBJECT,
    CAMERA_CALIBRATION,
    AR_DEPTH,
    MANUAL
}
```

---

# 27. Step 22 — Overlay UI

Compose structure:

```kotlin
Box(
    modifier = Modifier.fillMaxSize()
) {

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
    )

    DetectionOverlay(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        corners = result.corners,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
    )
}
```

Overlay phải dùng chính xác cùng:

- Image size
- ContentScale
- Alignment
- Crop/Fit transform

---

# 28. DetectionOverlay

```kotlin
@Composable
fun DetectionOverlay(
    imageWidth: Int,
    imageHeight: Int,
    corners: List<Point>,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {

    Canvas(
        modifier = modifier
    ) {

        val mapped =
            corners.map { point ->
                mapImagePointToCanvas(
                    point = point,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    contentScale = contentScale
                )
            }

        val path = Path()

        path.moveTo(
            mapped[0].x,
            mapped[0].y
        )

        for (i in 1 until mapped.size) {
            path.lineTo(
                mapped[i].x,
                mapped[i].y
            )
        }

        path.close()

        drawPath(
            path = path,
            style = Stroke(
                width = 4.dp.toPx()
            )
        )
    }
}
```

---

# 29. Mapping Image → Canvas

Đây là phần bắt buộc phải implement chuẩn.

Với `ContentScale.Fit`:

```kotlin
fun mapImagePointToCanvasFit(
    point: Point,
    imageWidth: Int,
    imageHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float
): Offset {

    val scale = minOf(
        canvasWidth / imageWidth,
        canvasHeight / imageHeight
    )

    val displayedWidth =
        imageWidth * scale

    val displayedHeight =
        imageHeight * scale

    val offsetX =
        (canvasWidth - displayedWidth) / 2f

    val offsetY =
        (canvasHeight - displayedHeight) / 2f

    return Offset(
        x = point.x.toFloat() * scale + offsetX,
        y = point.y.toFloat() * scale + offsetY
    )
}
```

Phải có implementation tương ứng cho:

```text
ContentScale.Fit
ContentScale.Crop
ContentScale.FillBounds
```

Nếu project chỉ dùng Fit thì chỉ cần Fit trước.

---

# 30. Step 23 — Manual Corner Adjustment

Nếu confidence thấp:

```text
┌──────────────────────────────┐
│                              │
│      ●────────────────●      │
│      │                │      │
│      │     OBJECT     │      │
│      │                │      │
│      ●────────────────●      │
│                              │
└──────────────────────────────┘
```

4 corner là draggable handles.

Model:

```kotlin
data class EditableCorners(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset
)
```

Khi drag:

```text
corner position
      ↓
update polygon
      ↓
recalculate side lengths
      ↓
update overlay
```

---

# 31. Step 24 — ViewModel

```kotlin
class ObjectDetectionViewModel(
    private val detector: ObjectDetector
) : ViewModel() {

    var state by mutableStateOf(
        DetectionUiState()
    )
        private set

    fun detect(
        image: Mat,
        tapPoint: Point,
        dimension: KnownDimension
    ) {

        viewModelScope.launch {

            state = state.copy(
                isDetecting = true
            )

            val result =
                withContext(Dispatchers.Default) {

                    detector.detect(
                        image,
                        tapPoint,
                        dimension
                    )
                }

            state = state.copy(
                isDetecting = false,
                result = result
            )
        }
    }
}
```

---

# 32. UI State

```kotlin
data class DetectionUiState(
    val isDetecting: Boolean = false,
    val result: DetectionResult? = null,
    val error: String? = null,
    val isManualEditing: Boolean = false
)
```

---

# 33. Threading

OpenCV detection:

```text
MUST NOT run on Main Thread
```

Use:

```kotlin
withContext(Dispatchers.Default)
```

Không block UI khi:

- resize
- grayscale
- Canny
- findContours
- approxPolyDP
- perspective transform

---

# 34. Memory Management

OpenCV `Mat` có native memory.

Cần release các Mat trung gian khi không còn dùng:

```kotlin
gray.release()
blurred.release()
edges.release()
hierarchy.release()
```

Nếu dùng Kotlin wrapper hoặc scope management trong project thì áp dụng pattern phù hợp.

Không giữ `Mat` lớn trong ViewModel lâu hơn cần thiết.

Original full-resolution image nên được giữ riêng cho final rendering/export.

---

# 35. Recommended Architecture

```text
feature/object-detection/
│
├── data/
│   └── OpenCVObjectDetector.kt
│
├── domain/
│   ├── ObjectDetector.kt
│   ├── DetectObjectUseCase.kt
│   └── CalculateConfidenceUseCase.kt
│
├── model/
│   ├── KnownDimension.kt
│   ├── DetectionResult.kt
│   ├── DetectionConfig.kt
│   └── ScaleInfo.kt
│
├── presentation/
│   ├── ObjectDetectionViewModel.kt
│   ├── DetectionUiState.kt
│   ├── ObjectDetectionScreen.kt
│   └── DetectionOverlay.kt
│
└── util/
    ├── ImageCoordinateMapper.kt
    ├── PointUtils.kt
    └── ExifUtils.kt
```

---

# 36. End-to-End Flow

```text
USER
 │
 │ Select photo
 ▼
PHOTO
 │
 │ Normalize EXIF
 ▼
NORMALIZED IMAGE
 │
 │ Resize processing copy
 ▼
PROCESSING IMAGE
 │
 │ User tap
 ▼
IMAGE COORDINATE
 │
 │ Create ROI
 ▼
ROI
 │
 ├── Grayscale
 ├── Gaussian Blur
 └── Canny
        │
        ▼
      EDGES
        │
        ▼
   FIND CONTOURS
        │
        ▼
 FILTER BY TAP POINT
        │
        ▼
 APPROX POLYGON
        │
        ▼
    4 CORNERS
        │
        ├── Aspect ratio
        ├── Side consistency
        ├── Angle consistency
        └── Area
        │
        ▼
 CONFIDENCE SCORE
        │
        ├── HIGH
        │      ↓
        │   AUTO ACCEPT
        │
        └── LOW
               ↓
        MANUAL ADJUSTMENT
               │
               ▼
         FINAL POLYGON
               │
               ▼
       OVERLAY ON IMAGE
```

---

# 37. Detection Strategy Priority

Implement theo thứ tự:

## Level 1

```text
Tap
→ ROI
→ Canny
→ Contours
→ ApproxPolyDP
```

## Level 2

Nếu Level 1 không tốt:

```text
Adaptive Threshold
OR
Morphological Close
OR
Different Canny thresholds
```

## Level 3

Nếu contour bị đứt:

```text
HoughLinesP fallback
```

## Level 4

Nếu background phức tạp:

```text
Segmentation model
```

Không bắt đầu bằng AI segmentation model nếu object là hình chữ nhật đơn giản và background tương đối sạch.

---

# 38. Morphological Fallback

Nếu edge bị đứt:

```kotlin
val kernel =
    Imgproc.getStructuringElement(
        Imgproc.MORPH_RECT,
        Size(5.0, 5.0)
    )

val closed = Mat()

Imgproc.morphologyEx(
    edges,
    closed,
    Imgproc.MORPH_CLOSE,
    kernel
)
```

Sau đó chạy lại:

```kotlin
Imgproc.findContours(...)
```

---

# 39. Multiple Detection Attempts

Không nên chỉ chạy Canny một lần.

Có thể thử:

```text
Attempt 1:
Canny 50 / 150

Attempt 2:
Canny 30 / 100

Attempt 3:
Canny 100 / 200

Attempt 4:
Morphological close + Canny
```

Mỗi attempt tạo candidate.

Sau đó chọn:

```text
highest confidence candidate
```

Điều này thường ổn định hơn hard-code một threshold duy nhất.

---

# 40. Candidate Scoring

Mỗi candidate:

```kotlin
data class DetectionCandidate(
    val corners: List<Point>,
    val confidence: Double,
    val longSidePx: Double,
    val shortSidePx: Double
)
```

Cuối cùng:

```kotlin
val best =
    candidates.maxByOrNull {
        it.confidence
    }
```

Nếu:

```text
confidence < minConfidence
```

→ return `null` hoặc result trạng thái uncertain.

---

# 41. Test Cases Bắt Buộc

Test tối thiểu:

### Test 1 — Frontal rectangle

```text
┌──────────────────────┐
│                      │
│       OBJECT         │
│                      │
└──────────────────────┘
```

Expected:

```text
4 corners
high confidence
```

### Test 2 — Rotated rectangle

```text
      /────────────/
     /            /
    /────────────/
```

Expected:

```text
4 corners
overlay follows rotation
```

### Test 3 — Perspective

```text
      ┌──────────────┐
     /              /
    /──────────────/
```

Expected:

```text
quadrilateral
not axis-aligned bounding box
```

### Test 4 — Multiple objects

```text
┌───────┐       ┌─────────┐
│ OBJ A │       │  OBJ B  │
└───────┘       └─────────┘
```

Tap OBJ B.

Expected:

```text
detect OBJ B
not OBJ A
```

### Test 5 — Texture

Object có texture / hoa văn.

Expected:

```text
không chọn các contour nhỏ bên trong
```

### Test 6 — Strong shadow

Expected:

```text
shadow không trở thành object boundary
```

### Test 7 — Wrong aspect ratio

Known:

```text
120 × 60 cm
```

Detected:

```text
100 × 100
```

Expected:

```text
low confidence / reject
```

### Test 8 — Tap outside

Expected:

```text
detection failed
```

### Test 9 — Partial occlusion

Expected:

```text
low confidence hoặc manual correction
```

### Test 10 — Manual adjustment

Expected:

```text
drag corner
→ polygon updates
→ dimensions update
```

---

# 42. Acceptance Criteria

Feature chỉ được coi là hoàn thành khi:

- [ ] User có thể tap vào object.
- [ ] Hệ thống ưu tiên object chứa tap point.
- [ ] Detect được boundary.
- [ ] Detect được 4 corners với rectangular object.
- [ ] Hỗ trợ object xoay.
- [ ] Hỗ trợ perspective cơ bản.
- [ ] Không chọn contour nhỏ bên trong object.
- [ ] Known dimensions được sử dụng để validate aspect ratio.
- [ ] Có confidence score.
- [ ] Confidence thấp không được silent accept.
- [ ] Có manual corner adjustment.
- [ ] Overlay khớp chính xác với Image.
- [ ] Coordinate mapping xử lý đúng Fit/Crop.
- [ ] EXIF orientation được xử lý.
- [ ] OpenCV chạy background thread.
- [ ] Original full-resolution image được giữ cho export.
- [ ] Không leak `Mat`.
- [ ] Có test cho rotated/perspective/multiple-object/shadow/texture.

---

# 43. Important Engineering Constraints

1. Không implement toàn bộ logic trong Composable.
2. Không chạy OpenCV trên Main Thread.
3. Không dùng Hough làm detector chính.
4. Không chọn contour lớn nhất một cách mù quáng.
5. Tap point phải được dùng để disambiguate object.
6. Không giả định ảnh và Canvas có cùng kích thước.
7. Phải xử lý ContentScale.
8. Phải xử lý EXIF rotation.
9. Phải giữ mapping giữa processing-resolution và original-resolution.
10. Không tuyên bố đo cm chính xác nếu chưa có calibration/reference.
11. Không auto-accept candidate chỉ vì có 4 points.
12. Confidence phải dựa trên nhiều yếu tố.
13. Khi detection không chắc chắn, cho phép manual correction.

---

# 44. Prompt Implementation cho AI Agent

Implement the complete feature described in this document.

Before coding:

1. Inspect the existing project architecture.
2. Identify:
   - current image loading mechanism
   - current Compose image component
   - current ViewModel pattern
   - existing OpenCV dependency if any
   - existing coordinate/image transformation utilities
3. Reuse existing architecture and dependencies whenever possible.
4. Do not duplicate existing utilities.

Implementation order:

1. Add/verify OpenCV dependency.
2. Create detection models.
3. Create ObjectDetector interface.
4. Implement OpenCVObjectDetector.
5. Implement:
   - resize
   - EXIF normalization
   - grayscale
   - Gaussian blur
   - Canny
   - contour detection
   - tap-point filtering
   - contour scoring
   - approxPolyDP
   - corner ordering
   - side calculation
   - aspect-ratio validation
   - angle validation
   - confidence calculation
6. Add ViewModel/use case.
7. Add Compose overlay.
8. Implement coordinate transformations.
9. Implement loading/error/uncertain states.
10. Implement draggable manual corners.
11. Add unit tests.
12. Add integration/UI tests where practical.

Do not refactor unrelated project code.

Do not change existing business logic outside this feature.

Keep the implementation modular so that a future segmentation model can replace
the OpenCV contour detector without changing the UI layer.

Future detector abstraction should allow:

```text
OpenCV detector
       OR
ML segmentation detector
       OR
AR/depth detector
```

all to produce the same DetectionResult.

Final output should include:

- files created
- files modified
- dependencies added
- explanation of detection pipeline
- explanation of coordinate mapping
- known limitations
- test results
