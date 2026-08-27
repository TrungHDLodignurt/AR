# Phase 06 — Workflow script: Verify song song + Gate 2

**Context:** `references/manifest-verification.md` · `references/caveats.md`

## Overview
- Priority: P0
- Status: pending
- Phase cuối trong script (4 kiểm tra chạy song song), rồi trả findings ra ngoài cho người duyệt.

## Key insights
- 4 kiểm tra **độc lập thật** → đây là chỗ `parallel` đúng nghĩa, khác với phase Apply.
- `assembleRelease` **bắt buộc**. R8 chỉ chạy ở release và fail im lặng — bài học `:feature-video`.
- Gate 2 nằm **ngoài** workflow (workflow không hỏi được người). Script chỉ *trả* findings; phần
  trình bày + xin duyệt do main loop làm.
- Caveat phải nói ra, không để build xanh ngụ ý feature đã được xác thực.

## Requirements
- F1: 4 agent song song — (a) build debug+release, (b) đếm merged manifest, (c) feature-coverage
  gate, (d) đọc diff restyle đối chiếu danh sách divergence cố ý.
- F2: gom thành `{blocking[], warnings[], caveats[], manualTests[]}`.
- F3: main loop render bảng + xin owner duyệt.
- NF1: `blocking` rỗng mới coi là chạy xong.

## Architecture
```js
phase('Verify')
const checks = await parallel([
  () => agent(buildPrompt,     {schema: CHECK_SCHEMA}),   // assembleDebug + assembleRelease
  () => agent(manifestPrompt,  {schema: CHECK_SCHEMA}),   // grep count 1/1/1/2/0
  () => agent(coveragePrompt,  {schema: CHECK_SCHEMA}),   // 7 tính năng có entry point chưa
  () => agent(diffPrompt,      {schema: CHECK_SCHEMA}),   // divergence cố ý có bị sửa nhầm không
])
return summarize(checks.filter(Boolean))
```
Đếm manifest kỳ vọng: `camera.ar`=1, `com.google.ar.core`=1, `armeasure.fileprovider`=1,
`ArCameraActivity|ArPhotoActivity`=2, `WRITE_EXTERNAL_STORAGE`=0.

## Related code files
- Đọc: merged manifest trong `<host>/app/build/intermediates/**`, `git diff` của `AR_feature`

## Implementation steps
1. Viết 4 prompt, mỗi cái trỏ đúng mục trong `manifest-verification.md`.
2. `CHECK_SCHEMA` chung cho cả 4 → gom dễ.
3. Hàm `summarize` phân loại blocking / warning.
4. Nạp sẵn `caveats[]` từ `references/caveats.md` (ARCore certification, nhiệt, emulator, không phải
   thước cặp) và `manualTests[]` từ script test tay 8 mục.
5. Main loop: render bảng, xin duyệt.

## Todo
- [ ] 4 prompt kiểm tra
- [ ] `CHECK_SCHEMA` + `summarize`
- [ ] Nạp caveats + manual tests
- [ ] Lớp render gate 2 ở main loop
- [ ] Thử với 1 lỗi cố ý → phải rơi vào `blocking`

## Success criteria
- Build xanh cả debug lẫn release trên host
- 5 con số manifest đúng kỳ vọng
- 7 tính năng (Distance/Box/Cylinder/Photo/unit/undo-redo/save) đều có entry point tới được
- Gate 2 nêu rõ: cái gì máy xác thực được, cái gì cần người cầm máy

## Risk assessment
- Release build lâu → chạy song song với 3 kiểm tra kia, không nối tiếp
- Coverage gate đọc code thì pass giả → phải tap-through thật trên máy, ghi rõ đó là việc của người
- Gate 2 quá dài, sếp không đọc → tối đa 1 bảng + 3 dòng caveat

## Security
Không in mapping.txt/keystore ra màn hình khi demo.

## Next steps
Gate 2 duyệt xong là hết 1 lượt chạy. Phase 07 chạy thật trên host 1.
