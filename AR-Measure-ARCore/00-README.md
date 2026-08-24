# AR Measure trên Android — Bộ tài liệu triển khai

> **Mục tiêu**: xây dựng tính năng đo kích thước thực tế qua camera (đo khoảng cách 2 điểm + đo vật thể Dài × Rộng × Cao) trên Android, dùng **ARCore**.
> **Nền tảng đã chọn**: Android native (Kotlin + ARCore SDK, có tuỳ chọn NDK cho phần xử lý point cloud).
> **Nguồn gốc dữ liệu**: chắt lọc từ tài liệu chính thức ARCore của Google (xem `07-nguon-tham-khao.md`).
> **Ngày biên soạn**: 2026-08-24.

---

## 1. Kết luận nhanh cho quyết định triển khai

| Câu hỏi | Trả lời ngắn |
|---|---|
| ARCore làm được tính năng đo không? | **Được.** Google đưa "Measurement" vào danh sách use case chính thức của Depth API. |
| Cần phần cứng đặc biệt (LiDAR/ToF) không? | **Không bắt buộc.** ARCore dựng chiều sâu bằng thuật toán *depth-from-motion* trên camera RGB thường + IMU. |
| Đo khoảng cách 2 điểm — độ khó | **Thấp.** Chỉ cần hit-test + Anchor + khoảng cách Euclid. ~1 sprint cho bản dùng được. |
| Đo vật thể D×R×C — độ khó | **Trung bình → Cao.** Bản "3 lần chạm thủ công" khả thi nhanh; bản "tự động dựng bounding box từ point cloud" cần R&D. |
| Rào cản lớn nhất | **Không phải API — mà là độ chính xác & độ phủ thiết bị.** Xem `02` và `05`. |
| Khoảng đo tin cậy | **0.5 m – 5 m** (Google công bố là dải tối ưu). Tối đa lý thuyết 65 m nhưng không dùng cho đo. |
| Rủi ro chặn tính năng | Bề mặt không hoạ tiết (tường trắng, kính, gương), ánh sáng yếu, người dùng không di chuyển máy. |

**Khuyến nghị**: chia 2 pha. Pha 1 giao *đo khoảng cách/chiều dài* (giá trị người dùng cao, rủi ro thấp). Pha 2 mới làm *bounding box vật thể*, và bắt buộc có bench test đối chiếu thước laser trước khi lên production.

---

## 2. Cấu trúc bộ tài liệu

| File | Nội dung | Dành cho |
|---|---|---|
| `00-README.md` | Bản đồ tài liệu + kết luận nhanh | Tất cả |
| `01-tong-quan-arcore.md` | ARCore hoạt động thế nào, các API dùng cho đo, API nào bỏ qua | Dev / Tech Lead |
| `02-han-che-phan-cung-va-thiet-bi.md` | Ma trận yêu cầu phần cứng, độ phủ thiết bị, chiến lược fallback theo tier | Tech Lead / PO |
| `03-thiet-ke-tinh-nang-ar-measure.md` | Kiến trúc module, luồng UX, thuật toán đo 2 điểm & bounding box | Dev / Designer |
| `04-code-mau-kotlin.md` | Code mẫu chạy được: session config, hit-test, đo, unproject depth → point cloud | Dev |
| `05-do-chinh-xac-va-kiem-thu.md` | Nguồn sai số, kỹ thuật giảm sai số, kế hoạch bench test, tiêu chí chấp nhận | QA / Dev |
| `06-roadmap-va-rui-ro.md` | Chia pha, ước tính effort, đăng ký rủi ro, quyết định kiến trúc cần chốt | PO / Tech Lead |
| `07-nguon-tham-khao.md` | Toàn bộ link nguồn + ghi chú độ tin cậy | Tất cả |

---

## 3. Cảnh báo trước khi đọc tiếp

1. **Số phiên bản SDK trong tài liệu này cần verify lại** tại thời điểm bắt đầu code. Trang tải SDK của Google yêu cầu chấp nhận ToS nên không đọc được version hiện hành qua crawler; bản mới nhất quan sát được trên GitHub là `1.54.0`.
2. **Không có con số độ chính xác chính thức nào từ Google.** Mọi ngưỡng sai số trong `05` là *đề xuất tiêu chí chấp nhận* của bộ tài liệu này, không phải cam kết của ARCore. Phải tự đo.
3. **Sceneform đã bị Google khai tử.** Đừng dựa vào nó. Xem quyết định kiến trúc trong `06`.
