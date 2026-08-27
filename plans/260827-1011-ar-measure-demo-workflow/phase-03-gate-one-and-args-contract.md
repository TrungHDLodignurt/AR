# Phase 03 — Gate 1 + hợp đồng `args`

**Context:** [brainstorm §GATE 1](../reports/brainstormer-260827-1011-ar-measure-demo-workflow.md) ·
[host-wiring.md](~/.claude/skills/trung-apply-ar-measure/references/host-wiring.md)

## Overview
- Priority: P0
- Status: pending
- Lớp hỏi-người-duyệt nằm NGOÀI workflow, biến câu trả lời thành `args` cho script.

## Key insights
- **Workflow không gọi được `AskUserQuestion`** — chạy background. Đây là ràng buộc nền tảng, không
  phải lựa chọn thiết kế.
- 4 câu hỏi **không cần fake**: với mỗi host mới cả 4 đều thật sự chưa có đáp án.
- Yêu cầu cứng cho demo: ≥2 lựa chọn phải làm output khác đi **thật**. Sếp hỏi "chọn cái kia thì
  sao" mà hardcode = vỡ tại chỗ.

## Requirements
- F1: 4 câu hỏi — tab mount ở đâu · minSdk nâng sàn có OK không · restyle theo .pen hay giữ gốc ·
  imageSaver default hay của host.
- F2: gom thành `args = {hostPath, penUrl, entryTab, allowMinSdkRaise, doRestyle, imageSaver}`.
- F3: `doRestyle=false` → workflow bỏ hẳn phase Restyle (chứng minh lựa chọn có tác dụng thật).
- F4: `allowMinSdkRaise=false` + host minSdk<24 → dừng, báo lại, không copy module.

## Architecture
```
AskUserQuestion (main loop)
   └─> args object
        └─> Workflow({script, args})   ← script đọc global `args`
```
Lưu ý: `args` phải truyền dạng JSON **thật**, không phải chuỗi JSON-encoded — chuỗi hoá thì
`args.filter`/`args.map` trong script sẽ throw.

## Related code files
- Tạo: `gate-one-questions.md` (nội dung 4 câu + mapping sang args)
- Đọc: `<host>` nav pattern để gợi ý sẵn danh sách tab cho câu 1

## Implementation steps
1. Trước khi hỏi: quét nhanh host tìm enum tab / danh sách Activity, để câu 1 đưa được lựa chọn thật
   thay vì ô trống.
2. Soạn 4 câu, mỗi câu ghi rõ chọn khác nhau thì output khác chỗ nào.
3. Định nghĩa shape `args`, viết ra file để phase 04-06 bám theo, không tự chế field.
4. Cài 2 nhánh thật: `doRestyle` và `allowMinSdkRaise`.
5. Thử tay: chạy gate 2 lần với 2 bộ đáp án khác nhau, xác nhận `args` khác nhau.

## Todo
- [ ] Quét host lấy danh sách tab thật
- [ ] Soạn 4 câu + `gate-one-questions.md`
- [ ] Chốt shape `args`
- [ ] Cài nhánh `doRestyle`, `allowMinSdkRaise`
- [ ] Thử 2 bộ đáp án, so `args`

## Success criteria
- Đổi đáp án → `args` đổi, và (phase 04+) output workflow đổi theo
- Không câu nào là câu trang trí

## Risk assessment
- Hỏi quá nhiều → demo lê thê. Giới hạn đúng 4 câu, không thêm.
- Câu 1 không tìm được tab của host → hỏi mở, chấp nhận owner gõ tay

## Security
Không hiển thị nội dung `local.properties`/keystore khi quét host.

## Next steps
Shape `args` là hợp đồng cho phase 04, 05, 06.
