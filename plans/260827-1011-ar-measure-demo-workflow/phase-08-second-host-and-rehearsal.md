# Phase 08 — Host thứ hai + tổng duyệt

## Overview
- Priority: P1
- Status: pending — chặn bởi 07
- Chạy cùng workflow trên host khác để chứng minh không hardcode, rồi tổng duyệt buổi trình bày.

## Key insights
- Giá trị thuyết phục nằm ở chỗ **cùng script, host khác, kết quả khác** — không phải ở chỗ nó chạy
  được 1 lần.
- Hai thứ chắc chắn khác nhau giữa 2 host: bucket-C alias trong TOML, và bộ
  `minSdk`/`compileSdk`/`kotlin`. Đó chính là bằng chứng cần trưng.
- Demo dài quá thì mất người xem. Toàn bộ ≤10 phút.

## Requirements
- F1: chạy end-to-end trên host 2, không sửa script.
- F2: bảng so sánh 2 host: case, TOML actions, entry point, số agent restyle, findings.
- F3: kịch bản trình bày ≤10 phút, có mốc thời gian.
- F4: có sẵn phương án dự phòng khi chạy live hỏng.

## Related code files
- Tạo: `demo-script.md` (kịch bản), `host-comparison.md` (bảng so sánh)

## Implementation steps
1. Pre-flight host 2 (đã làm ở phase 01, xác nhận lại).
2. Chạy end-to-end. Nếu phải sửa script → sửa xong **chạy lại cả host 1** để không hồi quy.
3. Lập `host-comparison.md`.
4. Viết `demo-script.md`:
   - 0–1' bài toán: apply feature vào N app, mỗi app UI khác
   - 1–2' gate 1, đổi 1 lựa chọn cho thấy nó có tác dụng thật
   - 2–6' workflow chạy, mở `/workflows` xem cây fan-out
   - 6–8' gate 2, đọc findings + caveat
   - 8–10' bảng so sánh 2 host + phần nào KB đã được cập nhật ngược
5. Dự phòng: quay sẵn video 1 lượt chạy thành công + chuẩn bị host đã chạy xong để mở ra xem kết quả
   nếu live fail.
6. Chạy thử toàn bộ kịch bản 1 lần, bấm giờ.

## Todo
- [ ] Pre-flight lại host 2
- [ ] Chạy end-to-end host 2
- [ ] `host-comparison.md`
- [ ] `demo-script.md` + mốc thời gian
- [ ] Quay video dự phòng
- [ ] Tổng duyệt bấm giờ ≤10'

## Success criteria
- Cùng script, 2 host, 2 kết quả khác nhau, cả 2 build xanh
- Kịch bản chạy trong 10 phút
- Có video dự phòng

## Risk assessment
- Live fail → video dự phòng, không chữa cháy trên sân khấu
- Mạng/máy chậm lúc demo → chuẩn bị sẵn kết quả chạy trước, chỉ chạy live phần fan-out cho ăn hình
- Sếp hỏi "auto được bao nhiêu %" → chuẩn bị sẵn con số: bước nào máy làm, bước nào người quyết

## Security
Không mở file cấu hình chứa key của host trên màn chiếu.

## Next steps
Sau demo: cân nhắc router `/trung` + run log nối HR (ngoài phạm vi plan này).
