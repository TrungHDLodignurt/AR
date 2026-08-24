# 06 — Roadmap, ước tính effort & đăng ký rủi ro

## 1. Chia pha

### Pha 0 — Spike kỹ thuật (1 tuần, 1 dev)
**Mục tiêu**: khẳng định khả thi trên thiết bị thật trước khi cam kết bất cứ deadline nào.

| Hạng mục | Định nghĩa hoàn thành |
|---|---|
| Dựng project ARCore + render camera background | Camera preview AR chạy trên 2 máy thật |
| Phát hiện tier | Log đúng FULL / PLANE_ONLY / UNSUPPORTED trên ≥ 3 máy khác nhau |
| Hit-test + đặt 2 anchor + in khoảng cách | Có số đo hiện ra |
| **Đo thử 5 vật đã biết kích thước, đối chiếu thước** | **Có bảng sai số thô đầu tiên → dữ liệu để quyết định go/no-go** |
| Verify công thức unproject depth (test ở `04` mục 5.5) | delta < 5 cm |

> 🚨 **Cổng quyết định**: nếu sai số thô của đo 2 điểm ở dải 1–3 m đã vượt 5 % trên máy tốt, đừng đi tiếp — quay lại xem lại kỹ thuật hoặc điều chỉnh scope sản phẩm.

### Pha 1 — Đo khoảng cách production-ready (3–4 tuần, 1 dev + 0.5 designer)
- Vòng đời ARCore hoàn chỉnh: permission, `requestInstall`, pause/resume, xử lý mất session
- Phân tier + màn hình UNSUPPORTED + nhập số đo thủ công
- Reticle giữa màn hình, xoay theo pháp tuyến bề mặt, đổi màu theo chất lượng
- M1 đo 2 điểm + M2 chuỗi điểm
- Cổng chất lượng + overlay hướng dẫn (thiếu sáng, quá xa, chưa đủ dữ liệu)
- Undo / xoá / kéo tinh chỉnh điểm / đổi đơn vị (m-cm-mm / ft-in)
- Onboarding 3 màn hình
- Telemetry đầy đủ
- **Bench test theo `05` trên ≥ 6 máy**

### Pha 2 — Đo diện tích + bounding box thủ công (2–3 tuần)
- M3 đo diện tích mặt phẳng (shoelace)
- M4 bounding box thủ công (chốt mặt nền → cạnh dài → cạnh rộng ép vuông góc → chiều cao)
- Render khối hộp wireframe + tay cầm kéo được
- M6 xuất kết quả: ảnh có annotation, JSON, lịch sử phiên đo
- **Hạ tầng Recording & Playback + bộ dataset ground truth trên CI**

### Pha 3 — Bounding box bán tự động (R&D, 4–6 tuần, rủi ro cao)
- Đường ống Raw Depth: acquire → confidence filter → unproject → gộp nhiều frame
- Voxel downsample + loại mặt nền + statistical outlier removal
- PCA / rotating calipers → OBB
- Tối ưu hiệu năng (khả năng cần chuyển vòng lặp sang NDK/C++)
- Occlusion shader cho vạch đo (dùng full depth) — nâng chất lượng cảm nhận rất nhiều
- **Cổng chất lượng: nếu không đạt tiêu chí ở `05` mục 4 sau 4 tuần → giữ nguyên M4 thủ công và dừng lại**

---

## 2. Ước tính effort

| Pha | Dev-week | Ghi chú |
|---|---|---|
| Pha 0 — Spike | 1 | Bắt buộc, không bỏ qua |
| Pha 1 — Đo khoảng cách | 3–4 | Cộng thêm ~1 tuần nếu tự viết OpenGL từ đầu |
| Pha 2 — Diện tích + hộp thủ công | 2–3 | Phần render khối hộp + tay cầm kéo chiếm phần lớn |
| Pha 3 — Hộp bán tự động | 4–6 | **Ước tính có độ tin cậy thấp** — là R&D thật sự |
| QA / bench test (song song) | 2–3 | Đo hiện trường tốn thời gian hơn dự kiến |
| **Tổng để có sản phẩm dùng được** | **~7–11 dev-week** | Không tính pha 3 |
| **Tổng cả pha 3** | **~11–17 dev-week** | |

Giả định: 1 dev Android senior đã biết OpenGL/3D cơ bản. **Nếu chưa ai trong team làm 3D/OpenGL, cộng thêm 2–3 tuần học** hoặc chọn SceneView để đổi rủi ro bảo trì lấy tốc độ.

---

## 3. Đăng ký rủi ro

| ID | Rủi ro | Xác suất | Tác động | Giảm thiểu |
|---|---|---|---|---|
| R1 | **Độ chính xác không đạt kỳ vọng của stakeholder** | 🔴 Cao | 🔴 Cao | Chốt tiêu chí chấp nhận bằng văn bản **trước** khi code; làm Pha 0 và trình bảng sai số thật trước khi cam kết; định vị sản phẩm là "ước lượng", không phải "thước đo" |
| R2 | **% user ở tier FULL quá thấp** → tính năng bounding box chỉ đến được thiểu số | 🟠 Trung bình | 🔴 Cao | Lấy số liệu top-20 model từ Play Console **ngay bây giờ**; nếu < 50 % thì làm M4 thủ công thay vì M5 |
| R3 | **Người dùng thất bại ở bước "lia máy"** → bỏ giữa dòng | 🔴 Cao | 🟠 Trung bình | Onboarding tốt; theo dõi `ar_measurement_abandoned`; tuyệt đối không cho đo trước khi đủ điều kiện |
| R4 | **Nóng máy / tụt pin** trong phiên đo dài | 🟠 Trung bình | 🟠 Trung bình | Dùng Raw Depth (½ chi phí); giảm tần số xử lý point cloud; auto-pause khi idle; đo nhiệt trong bench test |
| R5 | **Pha 3 (bounding box tự động) không converge** | 🟠 Trung bình | 🟠 Trung bình | Đặt cổng dừng 4 tuần; M4 thủ công là phương án lùi đã có sẵn |
| R6 | **Chọn SceneView rồi lib ngừng bảo trì** | 🟡 Thấp | 🟠 Trung bình | Pin version; bọc lớp render sau interface nội bộ để đổi được; hoặc chọn OpenGL thuần từ đầu |
| R7 | **Copy tutorial dùng Sceneform** (Google đã archive) | 🟠 Trung bình | 🟠 Trung bình | Ghi rõ vào code review checklist: từ chối mọi PR có `com.google.ar.sceneform` |
| R8 | **Rò `Image` / rò `Anchor`** gây crash & tụt fps sau vài phút | 🔴 Cao | 🟠 Trung bình | Bắt buộc `use {}` cho `Image`; `detach()` cho `Anchor`; thêm lint rule; test soak 10 phút |
| R9 | **Danh sách thiết bị của Google thay đổi**, whitelist hardcode bị lỗi thời | 🟠 Trung bình | 🟡 Thấp | Không hardcode — luôn kiểm tra runtime |
| R10 | Phát hành thị trường Trung Quốc (nếu có kế hoạch) | tuỳ | 🟠 Trung bình | Cần nghiên cứu riêng nhánh "Android (China)" + kênh phân phối của hãng |

---

## 4. Quyết định cần chốt trước khi bắt đầu code

| # | Quyết định | Các lựa chọn | Người quyết |
|---|---|---|---|
| D1 | Lớp render | OpenGL ES thuần (khuyến nghị) / SceneView (nhanh hơn, rủi ro lib) | Tech Lead |
| D2 | AR Required hay AR Optional | Optional (khuyến nghị nếu AR là 1 tính năng trong app lớn) | PO + Tech Lead |
| D3 | Có gắn `uses-feature com.google.ar.depth` không | Không (khuyến nghị — dùng fallback tier thay vì cắt máy) | PO |
| D4 | Scope pha 1: chỉ M1 hay cả M2/M3 | — | PO |
| D5 | Tiêu chí chấp nhận độ chính xác | Xem đề xuất ở `05` mục 4 | PO + QA |
| D6 | Có làm M5 (bounding box tự động) hay dừng ở M4 | **Quyết định sau khi có số liệu R2** | PO |
| D7 | Đầu tư hạ tầng Recording & Playback từ pha 1 hay pha 2 | Khuyến nghị pha 2, nhưng ghi dataset từ pha 1 | Tech Lead + QA |
| D8 | Có mở rộng sang iOS về sau không | Nếu **có** → cân nhắc lại Unity AR Foundation ngay từ đầu để dùng 1 codebase; nếu **không** → Android native là chọn đúng | PO |

> **Lưu ý về D8**: nếu iOS nằm trong roadmap 12 tháng, việc chọn Android native bây giờ nghĩa là sẽ phải viết lại toàn bộ phần đo bằng ARKit sau này (ARKit có LiDAR trên máy Pro → thuật toán và kỳ vọng độ chính xác khác hẳn). Cần chốt điều này **trước** pha 1, không phải sau.

---

## 5. Việc cần làm ngay tuần này

1. **Lấy top-20 model + phân bố Android version từ Play Console**, tra từng model trong danh sách thiết bị ARCore, ra được `% user tier FULL`. → đầu vào cho D6.
2. **Chốt D8 (có iOS hay không)** với PO. Đây là quyết định đắt nhất nếu chốt sai.
3. **Mua/mượn thiết bị cho ma trận test** (xem `05` mục 5.2) và **một thước laser**.
4. **Khởi động Pha 0** — 1 dev, 1 tuần, đầu ra là bảng sai số thật.
5. **Chốt tiêu chí chấp nhận bằng văn bản** dựa trên `05` mục 4, có PO ký.
