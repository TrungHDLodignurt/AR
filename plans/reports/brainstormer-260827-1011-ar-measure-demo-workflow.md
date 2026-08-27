---
title: Demo workflow apply AR_feature — brainstorm record
date: 2026-08-27
status: agreed
decision-owner: trunghd
---

# Workflow "apply AR_feature vào host" — bản trình diễn

## Problem statement

Tiêu chí L3 đòi: workflow auto 2-3 bước nối, KB có loop, người duyệt tại checkpoint, hệ thống có
hình hài hoàn chỉnh. Skill `trung-apply-ar-measure` đã cover phần *thực thi*. Thiếu phần **trình
diễn được**: một hệ thống nhìn thấy được đang chạy, có fan-out, có gate người duyệt.

Quyết định của owner: **giữ skill làm bản dùng thật, viết thêm 1 Workflow riêng để trình diễn.**
Chấp nhận trùng lặp có kiểm soát. Deadline 2-3 tuần.

## Input

- `hostPath` — path repo Android host (owner cung cấp, nhiều app khác nhau)
- `penUrl` — link .pen (optional; chỉ khi host muốn custom UI các màn trong feature)

## Approaches đã cân nhắc

| # | Phương án | Kết luận |
|---|---|---|
| A | Workflow thuần thay skill | **Loại.** Workflow chạy background, không hỏi được giữa chừng → mất tiêu chí "người duyệt tại checkpoint". Trục apply lại serial cứng, fan-out gần như vô nghĩa (tiết kiệm ~3 phút). |
| B | Skill gọi Workflow ở các phase fan-out | Tốt về kiến trúc nhưng phức tạp, và owner muốn tách bạch bản-thật / bản-diễn. |
| C | **Workflow độc lập, chỉ để trình diễn** | **Chọn.** Skill không đụng tới. Workflow là lớp điều phối mỏng, không copy logic. |

## Kiến trúc chốt

```
main loop (skill/session)
  ├─ GATE 1  AskUserQuestion — 4 quyết định THẬT (xem dưới)
  │            ↓ args = {hostPath, penUrl, answers}
  ├─ Workflow(script)  ← chạy thẳng, không dừng
  │     phase Scan     1 agent: detect host (Case A/B/C, TOML, minSdk, nav pattern)
  │                    + main loop đã dump geometry .pen ra JSON trước đó
  │     phase Apply    pipeline: copy module → merge TOML → wire entry point
  │     phase Restyle  fan-out N agent, MỖI AGENT SỞ HỮU 1 FILE (không phải 1 màn)
  │     phase Verify   parallel: manifest grep · build gate · coverage gate · inset sweep
  │            ↓ return findings
  └─ GATE 2  báo cáo phát sinh + owner duyệt
```

**Ràng buộc nền tảng (không phải lựa chọn thiết kế):** Workflow không gọi được `AskUserQuestion`.
Cả 2 gate bắt buộc nằm NGOÀI script.

## GATE 1 — 4 câu hỏi thật, không fake

Owner ban đầu định "fake" gate này vì các quyết định đã settled trong repo nguồn. Không cần fake —
với mỗi **host mới** cả 4 đều thật sự chưa có đáp án:

| Câu hỏi | Rẽ nhánh thật cái gì |
|---|---|
| Mount `ArMeasureHub()` vào tab/màn nào | Mỗi host enum tab riêng; skill ghi rõ phải hỏi operator, không đoán |
| Host `minSdk` < 24 → chấp nhận nâng sàn? | Đổi install footprint, không đảo ngược |
| Custom UI theo .pen hay giữ design gốc? | Bật/tắt cả phase Restyle |
| ImageSaver mặc định hay của host? | Ảnh user lưu ở đâu — quyết định privacy của host |

**Yêu cầu cứng:** tối thiểu 2 lựa chọn phải làm output khác đi thật. Sếp lớn hỏi "chọn cái kia thì
sao" mà hardcode = vỡ demo tại chỗ.

## Rủi ro + xử lý

| # | Rủi ro | Mức | Xử lý |
|---|---|---|---|
| R1 | **Subagent trong workflow có gọi được `mcp__pencil__*` không — CHƯA AI VERIFY.** Tool doc cảnh báo MCP xác thực tương tác có thể vắng mặt trong headless run | 🔴 Cao | Thiết kế **JSON-handoff**: main loop (có pencil) scan .pen 1 lần → dump geometry ra JSON → truyền qua `args`. Subagent không cần MCP. Rủi ro biến mất hoàn toàn. Vẫn phải test 1 agent gọi pencil ở **tuần 1** để biết chắc |
| R2 | Nhiều agent sửa cùng 1 file → ghi đè nhau. `ArCameraChrome.kt` phục vụ cả SCR-19 lẫn SCR-20 | 🔴 Cao | Phase Scan xuất **bảng phân file**; mỗi agent own file riêng biệt. Không dùng worktree (tốn + phải merge) |
| R3 | Host demo có `compileSdk` < 36 / kotlin quá cũ / JDK < 17 → build đỏ trên màn chiếu | 🟠 TB | **Pre-flight bắt buộc** mọi host ứng viên trước ngày demo: grep `compileSdk`/`minSdk`/`kotlin`/`java -version`. 2 phút |
| R4 | Bucket-C alias TOML khác nhau mỗi host → `assembleDebug` fail vì thiếu `libs.*` | 🟠 TB | Đã có thuật toán 3-bucket trong `references/catalog-merge.md`; agent Scan chạy nó, không tự chế |
| R5 | Trùng lặp skill ↔ workflow, sửa 1 chỗ quên chỗ kia | 🟠 TB | Workflow **không copy logic**. Mỗi agent nhận prompt "làm theo `references/<file>.md`". KB một nguồn duy nhất, script còn ~150 dòng điều phối |
| R6 | Agent số lượng lớn vượt guideline session (medium, <15) | 🟡 Thấp | ~10 file restyle + 1 scan + 3 verify ≈ 14. Vừa khít. Nếu vượt, gộp file theo cụm màn |
| R7 | Demo cần ≥8 ô chạy song song mới "ăn hình"; workflow apply thuần chỉ 2-3 ô | 🟡 Thấp | Bắt buộc bật trục .pen restyle trong bản demo — đó là chỗ duy nhất fan-out thật |

## Cái workflow này KHÔNG làm

- Không sửa API public của module (vẫn đúng 3 symbol)
- Không đặt điểm AR / vẽ shape 3-tap — người cầm máy, không script được
- Không thay thế skill: skill vẫn là bản dùng thật

## Success metrics

1. Chạy được end-to-end trên ≥2 host khác nhau, ra kết quả khác nhau (chứng minh không hardcode)
2. `:app:assembleDebug` **và** `:app:assembleRelease` xanh trên host sau khi workflow chạy xong
3. Gate 1 đổi lựa chọn → output đổi thật, chứng minh live được
4. Cây `/workflows` hiện ≥8 agent chạy song song ở phase Restyle
5. Gate 2 báo đúng các phát sinh (TOML bucket-C, minSdk raise, màn .pen không map được)

## Next steps

1. **Tuần 1** — test subagent gọi `mcp__pencil__*` (R1). Song song: pre-flight 2-3 host ứng viên (R3)
2. **Tuần 1** — viết stage Scan: detect host + xuất bảng phân file từ geometry JSON (R2)
3. **Tuần 2** — script workflow đầy đủ, chạy thử trên host thứ nhất
4. **Tuần 2-3** — chạy host thứ hai, tinh chỉnh gate, tổng duyệt

## Unresolved

1. `.pen` trên đĩa đã stale — designer cần save lại, hay chấp nhận đọc live qua MCP mỗi lần?
2. Restyle output là token override hay patch thẳng composable? Owner nói skill đã đủ dùng thật →
   với bản demo có thể patch thẳng, nhưng nếu sau này dùng thật thì mỗi host thành 1 fork module.
   Chưa chốt.
3. Chưa biết host demo cụ thể là app nào → chưa chạy được pre-flight R3.
