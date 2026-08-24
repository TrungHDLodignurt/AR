# Báo cáo: Hạn chế phần cứng ARCore & lý do 3/3 device test fail

**Ngày:** 2026-08-24
**Nguồn:** `AR-Measure-ARCore/02-han-che-phan-cung-va-thiet-bi.md`, `00-README.md`, `07-nguon-tham-khao.md` (chắt lọc từ tài liệu chính thức Google, xem link trích dẫn ở mục 4)

## 1. Kết luận nhanh

- **ARCore KHÔNG bị loại bỏ/khai tử.** Google chỉ **đổi tên gói** từ "ARCore" thành **"Dịch vụ Google Play cho AR" / "Google Play Services for AR"** (`com.google.ar.core`) — đúng như dòng chữ trên trang Play Store: *"Dịch vụ này trước đây có tên là ARCore"*. API, SDK, cơ chế hoạt động không đổi.
- **Cái bị khai tử thật là Sceneform** (thư viện render 3D cũ của Google) — không liên quan gói ARCore runtime. App đang dùng SceneView (kế nhiệm Sceneform) nên không ảnh hưởng.
- **3/3 device fail** vì lỗi **"Thiết bị của bạn không tương thích với phiên bản này"** — đây là do **Play Store**, không phải app, chặn ở **Cửa 2 / Gate 2**: thiết bị không nằm trong danh sách ARCore-certified của Google. Không phải bug code, không phải build sai.

## 2. Mô hình 3 cửa (device gating) — trích `02-han-che-phan-cung-va-thiet-bi.md` mục 1

```
Cửa 1: Android 7.0+ (API 24)                  → fail: không có AR
   ↓
Cửa 2: Thiết bị được ARCore chứng nhận         → fail: không có AR   ⚠️ 3 máy đang kẹt ở đây
        + có Google Play Services for AR
   ↓
Cửa 3: Hỗ trợ Depth API                        → fail: chỉ đo được trên mặt phẳng
   ↓
(Tuỳ chọn) Có cảm biến ToF phần cứng           → cực hiếm, coi như bonus
```

Ghi chú quan trọng (trích nguyên văn tài liệu):
> "Không thể suy ra từ cấu hình máy — **phải tra danh sách hoặc kiểm tra runtime**."
> "Một số model bị nâng ngưỡng: ví dụ Nexus 5X/6P yêu cầu Android 8.0+; Nokia G50, Nokia X10 yêu cầu Android 13+."

→ Máy cấu hình mạnh vẫn có thể KHÔNG được Google chứng nhận, vì Google chỉ certify sau khi tự kiểm tra camera + cảm biến chuyển động của từng model cụ thể.

## 3. Ma trận hạn chế phần cứng đầy đủ

| # | Hạn chế | Mức tác động | Biểu hiện | Cách xử lý |
|---|---|---|---|---|
| H1 | Không có ToF/LiDAR đại chúng trên Android | 🔴 Cao | Cần lia máy vài giây mới đo được | Onboarding hướng dẫn lia máy |
| H2 | Depth cần chuyển động thiết bị | 🔴 Cao | Đứng yên → không có depth | Gate nút "Đo" đến khi đủ depth/plane |
| H3 | Bề mặt không hoạ tiết (tường trắng, kính, gương, vật bóng/đen) | 🔴 Cao | Confidence = 0, đo lệch | Cảnh báo "bề mặt khó nhận diện" |
| H4 | Dải tin cậy chỉ 0.5–5 m | 🟠 Trung bình | Vật rất nhỏ/rất xa sai nhiều | Chặn/cảnh báo ngoài 0.3–5 m |
| H5 | Ánh sáng yếu → mất tracking | 🟠 Trung bình | Số đo nhảy, mất anchor | Bắt `TrackingFailureReason.INSUFFICIENT_LIGHT` |
| H6 | Chuyển động máy quá nhanh | 🟠 Trung bình | Mất tracking, drift | Bắt `EXCESSIVE_MOTION` |
| H7 | Scene động (người/vật di chuyển) | 🟠 Trung bình | Depth-from-motion giả định scene tĩnh | Cảnh báo UX |
| H8 | Nhiệt & pin (camera+depth+render liên tục) | 🟠 Trung bình | Máy nóng, fps sập sau 5–10 phút | Raw Depth (½ chi phí), giảm tần số xử lý |
| H9 | Độ phân giải/fps camera khác theo máy | 🟡 Thấp | Trải nghiệm không đồng nhất | Chọn `CameraConfig` theo tier máy |
| H10 | Depth image khác kích thước/orientation với ảnh camera | 🟡 Thấp, dễ bug | Điểm đo lệch khi xoay máy | Luôn dùng `Frame.transformCoordinates2d()` |
| H11 | Emulator hỗ trợ giới hạn | 🟡 Thấp | — | Không dùng emulator để đo chính xác |
| **H12** | **Máy thị trường Trung Quốc không có Google Play Services for AR** | 🟡 Tuỳ thị trường | **Không chạy được — đúng dạng lỗi đang gặp** | Tra nhánh "Android (China)" trong danh sách thiết bị |

## 4. Vì sao 3 máy đều fail — hướng điều tra tiếp

Lỗi trên Play Store xảy ra ở bước `ArCoreApk.requestInstall()` trong `MainActivity.kt` khi app cố cài gói ARCore. Play Store tự chặn theo **model máy**, không phải theo app. Nguyên nhân khả dĩ, xếp theo khả năng cao → thấp:

1. **Cả 3 máy đều không nằm trong danh sách ARCore-certified** của Google (danh sách gốc: https://developers.google.com/ar/devices) — máy giá rẻ, hãng ít phổ biến, hoặc bản ROM khu vực không được Google duyệt.
2. **Máy là hàng thị trường Trung Quốc** (ROM rút gọn, thiếu đầy đủ Google Play Services) → xem H12.
3. Tài khoản Play Store trên máy test ở khu vực/quốc gia bị hạn chế phân phối gói AR.
4. (Ít khả năng hơn) Máy là máy ảo/giả lập giả danh nhiều "device" — Play Store phát hiện môi trường không phải phần cứng thật.

**Việc cần làm để chốt nguyên nhân:** cung cấp **tên hãng + model cụ thể** của 3 máy đã test, tôi tra chéo với danh sách chính thức của Google để xác định máy nào rớt vì lý do gì (certified nhưng bị chặn account/vùng, hay thật sự chưa từng được certify).

## 5. Kết luận báo cho sếp

- App code xử lý đúng: có catch `UnavailableException` → fallback về màn hình "AR không hỗ trợ", không crash, tab Level vẫn dùng được (đúng thiết kế degraded app).
- **Đây là giới hạn của tập thiết bị test, không phải lỗi implementation.** ARCore vẫn hoạt động — chỉ đổi tên gói, không bị Google khai tử.
- Cần dữ liệu thiết bị thật (top model user đang dùng) để tính được **% user rơi vào tier FULL/PLANE_ONLY/UNSUPPORTED** trước khi chốt scope tính năng — theo đúng khuyến nghị ở mục 4 của tài liệu `02`.

## 6. Nguồn trích dẫn

- Danh sách thiết bị hỗ trợ ARCore (chính thức): https://developers.google.com/ar/devices
- Bật ARCore trong app Android (manifest, `ArCoreApk`): https://developers.google.com/ar/develop/java/enable-arcore
- Depth API tổng quan: https://developers.google.com/ar/develop/depth
- Tài liệu nội bộ: `AR-Measure-ARCore/02-han-che-phan-cung-va-thiet-bi.md`, `00-README.md`

## 7. Kết quả kiểm tra thực tế trên máy đang cắm (16:00, 24/08/2026)

2 máy đang cắm qua `adb`, đã build debug APK từ source hiện tại và cài trực tiếp để lấy log gốc từ chính `ARCore-InstallService` (bằng chứng thật, không suy đoán từ danh sách web):

| Máy | Model | Chipset | Android | Kết quả |
|---|---|---|---|---|
| Xiaomi POCO X7 | `24095PCADG` (codename `malachite`) | MediaTek Dimensity (mt6878) | 16 | ❌ **`ARCore-InstallService: The device is not supported.`** — bị chính ARCore từ chối cục bộ, trước cả khi ra Play Store |
| Samsung Galaxy A07 | `SM-A075F` (codename `a07`) | MediaTek (mt6789) | 16 | ❌ Không có gói ARCore local để tự chặn → app redirect ra Play Store (`requestInstall = -5, launching fullscreen`) → **Play Store server-side từ chối** (đúng màn hình "Thiết bị của bạn không tương thích" đã chụp) |

**Kết luận: cả 2 máy đang có trong tay đều KHÔNG được Google chứng nhận ARCore** — xác nhận bằng log thật, không phải suy đoán. Tra chéo trang danh sách chính thức (`developers.google.com/ar/devices`) qua web cho kết quả không nhất quán giữa các lần fetch (trang quá dài, bị cắt) nên **không dùng làm nguồn kết luận cuối** — log runtime từ máy thật là bằng chứng đáng tin hơn.

Cả hai đều là dòng máy tầm trung/giá rẻ (POCO X-series không-Pro, Samsung A0x-series thấp nhất của dòng A) — đúng như dự đoán ở mục 4: nhóm máy này thường rơi ngoài danh sách chứng nhận của Google, bất kể chip có mạnh hay không (Google certify theo camera + IMU của từng model, không theo hiệu năng CPU/GPU).

## Câu hỏi chưa giải quyết

- Model của máy thứ 3 (trong báo cáo gốc nói test 3 máy, ở đây mới xác nhận được 2 máy đang cắm) — cần bổ sung để tra tiếp.
- Có cần mượn 1 máy đã biết chắc certified (Pixel/Galaxy S-series) để xác nhận app chạy đúng khi có ARCore thật không?
