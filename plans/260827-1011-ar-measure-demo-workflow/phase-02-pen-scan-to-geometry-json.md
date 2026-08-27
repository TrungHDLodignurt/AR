# Phase 02 — Scan .pen → geometry JSON + bảng phân file

**Context:** [design geometry report](../reports/report-260827-1000-aip936-design-geometry-reference.md)
(mẫu dữ liệu đích) · [brainstorm R2](../reports/brainstormer-260827-1011-ar-measure-demo-workflow.md)

## Overview
- Priority: P0
- Status: pending
- Chuyển việc trích geometry thủ công hôm 27/08 thành một bước máy chạy, và quan trọng hơn: xuất
  **bảng phân file** để fan-out không ghi đè nhau.

## Key insights
- Report design-geometry hiện có chính là **định dạng đích** — đã chứng minh đủ dùng để sửa UI.
  Không thiết kế schema mới, JSON hoá đúng cái đó.
- **Màn ≠ file.** `ArCameraChrome.kt` phục vụ cả SCR-19 lẫn SCR-20; `MeasureModeSheet` là SCR-18
  riêng. Fan-out theo màn = 2 agent tranh 1 file. Bảng phân file là output quan trọng nhất phase này.
- `.pen` trên đĩa stale — chỉ pencil MCP đọc được bản live.

## Requirements
- F1: đọc .pen qua `mcp__pencil__*`, xuất `geometry.json`: mỗi node `{screen, node, x, y, w, h,
  gap, align, radius}`.
- F2: map mỗi màn → (các) file composable trong `AR_feature/src/main`.
- F3: đảo ngược map → `file-ownership.json`: mỗi file 1 entry, kèm danh sách màn nó phục vụ.
- NF1: file nào không map được màn nào → liệt kê riêng, **không đoán**.

## Architecture
```
.pen (live, qua MCP)
   └─> geometry.json        { screens: [ {id, nodes:[...]} ] }
   └─> file-ownership.json  [ {file, screens:[...], agentSlot:N} ]  ← đầu vào fan-out
```

## Related code files
- Tạo: `geometry.json`, `file-ownership.json` (trong thư mục plan)
- Đọc: `AR_feature/src/main/java/vn/apero/armeasure/**` (tìm composable theo tên màn)

## Implementation steps
1. Liệt kê node cần lấy — bám danh sách trong design-geometry report: SCR-14, 15, 18, 19, 20, 21,
   22, 23 + MeasureModeSheet + UnitMenu + ColorPickerBar.
2. Gọi pencil MCP đọc từng screen, chuẩn hoá ra `geometry.json`.
3. `grep` tìm composable tương ứng từng màn trong module. Ghi map màn → file.
4. Đảo map, gom theo file. File nào ≥2 màn → 1 agent duy nhất nhận cả 2 màn.
5. Kiểm tra bất biến: **mỗi file xuất hiện đúng 1 lần** trong `file-ownership.json`.
6. Đếm số entry → đó là số agent phase Restyle. Nếu > 10, gộp file cùng thư mục lại.

## Todo
- [ ] Chốt danh sách màn cần scan
- [ ] `geometry.json`
- [ ] Map màn → file
- [ ] `file-ownership.json`, verify mỗi file 1 lần
- [ ] Đếm agent slot, gộp nếu > 10

## Success criteria
- `geometry.json` parse được, có đủ ≥10 màn
- `file-ownership.json`: không file nào trùng — chạy `jq -r '.[].file' | sort | uniq -d` ra rỗng
- Danh sách file không map được màn nào ghi rõ ràng, không im lặng bỏ qua

## Risk assessment
- `.pen` stale/thiếu node → hỏi designer save lại; tạm dùng report design-geometry làm nguồn thay thế
- Tên composable không khớp tên màn → map tay, ghi lại, đừng để agent đoán

## Security
Không có. Read-only trên design + source.

## Next steps
`file-ownership.json` là input bắt buộc của phase 05.
