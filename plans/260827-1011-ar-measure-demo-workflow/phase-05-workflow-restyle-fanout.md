# Phase 05 — Workflow script: Restyle fan-out theo .pen

**Context:** [phase 02](phase-02-pen-scan-to-geometry-json.md) (bảng phân file) ·
[phase 01](phase-01-spike-mcp-and-host-preflight.md) (kết quả MCP) ·
[design geometry](../reports/report-260827-1000-aip936-design-geometry-reference.md)

## Overview
- Priority: P0 — **phần duy nhất fan-out thật**, và là phần ăn hình nhất khi demo
- Status: pending — **bị chặn bởi 01 và 02**
- Mỗi agent nhận 1 file + geometry các màn file đó phục vụ, sửa UI cho khớp .pen.

## Key insights
- Không có phase này thì cây `/workflows` chỉ hiện 2-3 ô — nhìn còn tệ hơn skill chạy tuần tự.
- **Chia theo file, không theo màn** (R2). `file-ownership.json` là hợp đồng.
- Kết quả phase 01 quyết định: agent gọi MCP trực tiếp, hay đọc `geometry.json` truyền qua `args`.
  **Mặc định thiết kế theo JSON-handoff** kể cả khi MCP chạy được — ít rủi ro hơn, scan 1 lần rẻ
  hơn N lần.
- Chỉ chạy khi `args.doRestyle === true`.
- Design geometry là **dp trên khung 354 rộng** — không so px thô với máy thật. So tỉ lệ và offset
  tương đối.

## Requirements
- F1: `pipeline` (không `parallel`) trên `file-ownership.json` — mỗi item chạy độc lập tới hết.
- F2: mỗi agent nhận: đường dẫn file, geometry các màn liên quan, danh sách **divergence cố ý**
  (design report §Intentional divergences) — cấm "sửa" những cái đó.
- F3: agent trả structured `{file, changes[], unmapped[], skipped[]}`.
- F4: `doRestyle=false` → bỏ toàn bộ phase.
- NF1: ≤10 agent. Vượt thì gộp theo thư mục.
- NF2: agent **không** đụng file ngoài file mình sở hữu.

## Architecture
```js
if (!args.doRestyle) return { restyled: [] }
phase('Restyle')
const results = await pipeline(
  ownership,                                   // từ file-ownership.json
  item => agent(restylePrompt(item, geometry), {label: `restyle:${item.file}`, schema: RESTYLE_SCHEMA}),
  r => agent(`compile check + sửa lỗi cú pháp trong ${r.file}`, {label: `fix:${r.file}`})
)
```

## Related code files
- Sửa: file trong `AR_feature/src/main/.../presentation/**` theo `file-ownership.json`
- Đọc: `geometry.json`

## Implementation steps
1. Đọc kết quả phase 01 → chốt agent lấy geometry kiểu nào.
2. Viết `restylePrompt(item, geometry)`: nêu rõ file được sở hữu, geometry đích, và **danh sách cấm
   sửa** (text tiếng Anh, touch target ≥48dp, chrome inset khỏi system bar, capture button căn giữa
   theo màn thật, undo/redo/Clear không có trong mock, `in` vs `inch`).
3. `RESTYLE_SCHEMA` — ép báo cáo cái gì đã đổi, cái gì không map được.
4. Stage 2 mỗi item: compile check riêng file đó.
5. Chạy thử 2 item trước, xác nhận không đụng nhau, rồi bung hết.
6. `git status` sau khi chạy: số file đổi ≤ số entry ownership.

## Todo
- [ ] Chốt cách lấy geometry (theo phase 01)
- [ ] `restylePrompt` + danh sách cấm sửa
- [ ] `RESTYLE_SCHEMA`
- [ ] Chạy thử 2 item
- [ ] Bung đủ N item, verify không ghi đè
- [ ] `:AR_feature:compileDebugKotlin` xanh sau restyle

## Success criteria
- ≥8 agent hiện song song trong `/workflows`
- `git status` không có file lạ ngoài danh sách ownership
- Module vẫn compile, 102 test vẫn xanh
- `unmapped[]` được báo lên gate 2, không im lặng

## Risk assessment
- Agent "sửa" nhầm divergence cố ý (dịch text sang tiếng Việt, thu touch target về 40dp theo mock)
  → **rủi ro cao nhất phase này**. Chặn bằng danh sách cấm trong prompt + đọc diff ở gate 2
- 2 agent chung file → đã chặn ở phase 02, verify lại bằng `uniq -d`
- Patch thẳng composable = host này fork module (unresolved #2 của plan). Bản demo chấp nhận

## Security
`.pen` là tài sản design nội bộ — không đẩy nội dung ra dịch vụ ngoài.

## Next steps
`changes[]`/`unmapped[]` là nội dung chính của report gate 2.
