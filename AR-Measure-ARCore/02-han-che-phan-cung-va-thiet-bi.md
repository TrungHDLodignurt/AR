# 02 — Hạn chế phần cứng, độ phủ thiết bị & chiến lược fallback

## 1. Ba tầng điều kiện (device gating)

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

## 2. Ma trận hạn chế phần cứng đầy đủ

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

## 3. Chiến lược fallback theo tier — bắt buộc implement

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

## 4. Câu hỏi cần dữ liệu nội bộ trước khi chốt scope

Bộ tài liệu này không thay được số liệu thị trường của bạn. Cần lấy từ analytics/Play Console:

1. **Phân bố Android version** của tập user hiện tại → bao nhiêu % ≥ API 24?
2. **Top 20 model** theo lượng user → tra từng model trong danh sách ARCore, đánh dấu có/không Depth API → ra được **% user ở tier FULL**.
3. Nếu **% tier FULL < 50%**, cần bàn lại: có nên đầu tư bounding box tự động, hay chỉ làm bản thủ công chạy được ở cả 2 tier?
