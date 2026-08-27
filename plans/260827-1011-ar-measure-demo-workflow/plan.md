---
title: "Demo workflow — apply AR_feature vào host app, có .pen restyle fan-out"
description: "Workflow độc lập (không đụng skill trung-apply-ar-measure) nhận hostPath + penUrl, chạy 2 gate người duyệt ngoài script, fan-out N agent restyle UI theo .pen. Mục tiêu: trình diễn hệ thống L3."
status: pending
priority: P2
effort: 18h
branch: feature/photo-reference-measure
tags: [workflow, subagent, demo, pen, integration, l3]
created: 2026-08-27
---

# Demo workflow — apply AR_feature + .pen restyle

Skill `trung-apply-ar-measure` **giữ nguyên, không sửa** — nó là bản dùng thật. Workflow này là bản
trình diễn: cùng việc, nhưng phơi ra được fan-out nhiều agent và 2 checkpoint người duyệt.

Đầu vào đã chốt, không derive lại:
[bản brainstorm](../reports/brainstormer-260827-1011-ar-measure-demo-workflow.md) — kiến trúc, 4 câu
gate 1, bảng rủi ro R1–R7. [Spec module](../../AR_feature/README.md) — 3 symbol public, wiring.
[Design geometry](../reports/report-260827-1000-aip936-design-geometry-reference.md) — dạng dữ liệu
scan .pen phải xuất ra.

## Phases

| # | Phase | Effort | Gate |
|---|---|---|---|
| 01 | [Spike: pencil MCP trong subagent + pre-flight host](phase-01-spike-mcp-and-host-preflight.md) | 2h | biết chắc R1 đúng/sai, ≥2 host qua pre-flight |
| 02 | [Scan .pen → geometry JSON + bảng phân file](phase-02-pen-scan-to-geometry-json.md) | 3h | JSON hợp lệ, không 2 agent chung 1 file |
| 03 | [Gate 1 + hợp đồng `args`](phase-03-gate-one-and-args-contract.md) | 1.5h | đổi lựa chọn → args đổi thật |
| 04 | [Workflow script: Scan + Apply](phase-04-workflow-scan-and-apply.md) | 3h | host build xanh sau khi chạy |
| 05 | [Workflow script: Restyle fan-out](phase-05-workflow-restyle-fanout.md) | 3h | ≥8 agent song song, không ghi đè file |
| 06 | [Workflow script: Verify + Gate 2](phase-06-workflow-verify-and-gate-two.md) | 2h | assembleRelease xanh, report phát sinh đúng |
| 07 | [Dry run host thứ nhất](phase-07-dry-run-first-host.md) | 2h | end-to-end, không can thiệp tay |
| 08 | [Host thứ hai + tổng duyệt](phase-08-second-host-and-rehearsal.md) | 1.5h | 2 host ra 2 kết quả khác nhau |

Tuần tự, trừ **02 ∥ 03** (file rời nhau). 01 chặn tất cả.

## Ordering facts

- **01 chặn 05.** Nếu subagent không gọi được pencil MCP thì phase Restyle phải đọc JSON thay vì gọi
  MCP — đổi cả prompt lẫn cách truyền dữ liệu. Không thiết kế 05 trước khi biết kết quả 01.
- **02 chặn 05.** Bảng phân file là đầu vào bắt buộc của fan-out; thiếu nó là R2 (2 agent ghi đè).
- **03 ∥ 02**: gate 1 chỉ động tới lớp `AskUserQuestion` + shape của `args`, không đụng scan.
- **07 trước 08**: host 1 để gỡ lỗi, host 2 để chứng minh không hardcode. Đảo lại là phí.
- Mỗi phase 04–06 gate bằng `assembleDebug` **và** `assembleRelease` trên host — R8 chỉ chạy ở
  release và fail im lặng.
- Đặt điểm AR / vẽ shape 3-tap **không script được**, không phase nào gate bằng nó.

## Ràng buộc nền tảng (không phải lựa chọn)

1. **Workflow không gọi được `AskUserQuestion`** — chạy background. Cả 2 gate nằm ngoài script.
2. **Concurrency cap** = min(16, CPU-2); guideline session hiện tại: medium, <15 agent. Ngân sách:
   1 scan + ~10 restyle + 3 verify = 14. Vượt thì gộp file theo cụm màn, không nâng cap.
3. **Workflow không copy logic của skill.** Agent nhận prompt trỏ tới
   `~/.claude/skills/trung-apply-ar-measure/references/*.md`. KB một nguồn duy nhất.
4. Không sửa API public của module — vẫn đúng 3 symbol.

## Unresolved questions

1. `.pen` trên đĩa stale. Designer save lại, hay mỗi lần đọc live qua MCP?
2. Restyle patch thẳng composable (demo chạy được ngay) hay sinh token override (giữ module 1 bản)?
   Bản demo tạm patch thẳng; nếu sau này dùng thật thì mỗi host thành 1 fork — chưa chốt.
3. Host demo cụ thể chưa biết → phase 01 pre-flight chưa chạy được cho tới khi owner cung cấp path.

## Out of scope

Sửa skill `trung-apply-ar-measure`. Sửa source `AR_feature` ngoài phạm vi restyle theo .pen. Router
`/trung` và run log nối HR (việc riêng, đã bàn nhưng không nằm trong plan này). 6 tool mock còn lại
(Angle, Polyline, Square...) — vẫn out of scope như plan gốc.
