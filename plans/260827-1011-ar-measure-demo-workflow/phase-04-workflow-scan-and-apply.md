# Phase 04 — Workflow script: Scan + Apply

**Context:** `references/detection.md` · `references/catalog-merge.md` · `references/host-wiring.md`
(trong `~/.claude/skills/trung-apply-ar-measure/`)

## Overview
- Priority: P0
- Status: pending
- Hai phase đầu của script: 1 agent detect host, rồi chuỗi copy → merge TOML → wire entry point.

## Key insights
- Trục này **serial cứng**, đừng cố song song hoá: merge TOML cần kết quả detect, wire cần TOML xong.
  Giá trị của workflow ở đây là *nhìn thấy được*, không phải nhanh hơn.
- **Không copy logic skill vào script.** Prompt agent trỏ thẳng tới file references. Sửa KB 1 chỗ,
  cả skill lẫn workflow cùng đúng.
- Bucket-C alias là chỗ fail thật nhất: `libs.androidx.ui` v.v. — host có artifact rồi nhưng tên
  alias khác, Gradle tra theo tên literal.

## Requirements
- F1: agent Scan → `{case: A|B|C, minSdk, compileSdk, kotlin, navPattern, tomlActions[]}` (dùng
  `schema` ép structured output, không parse văn xuôi).
- F2: Case C → **dừng workflow**, trả findings cho gate 2. Không tự quyết.
- F3: `minSdk < 24 && !args.allowMinSdkRaise` → dừng, báo lại.
- F4: Apply: copy `AR_feature/`, thêm `include(":AR_feature")`, merge TOML theo 3 bucket, thêm
  `implementation(project(":AR_feature"))`, thêm 1 nhánh `when` render `ArMeasureHub()` vào
  `args.entryTab`.
- NF1: mỗi bước Apply là 1 stage `pipeline`, không `parallel` — có phụ thuộc thật.

## Architecture
```js
phase('Scan')
const host = await agent(scanPrompt, {schema: HOST_SCHEMA})
if (host.case === 'C' || blockedByMinSdk(host)) return { halted: true, host }
phase('Apply')
await agent(copyPrompt)      // copy + include
await agent(tomlPrompt)      // 3-bucket merge, host.tomlActions
await agent(wirePrompt)      // dependency + when-branch
```

## Related code files
- Tạo: script workflow (tự persist dưới session dir; iterate bằng `scriptPath`)
- Sửa trong host: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`,
  file chứa tab enum + `when(tab)`

## Implementation steps
1. Định nghĩa `HOST_SCHEMA` — ép agent Scan trả object.
2. Prompt Scan: trỏ `references/detection.md`, yêu cầu đọc TOML host và phân loại 3 bucket.
3. Cài 2 điều kiện dừng (Case C, minSdk).
4. Prompt copy: `AR_feature/` nguyên vẹn, **cấm sửa** `build.gradle.kts` hay source của module.
5. Prompt TOML: chỉ áp `host.tomlActions`, cấm blind-append, cấm thêm key trùng.
6. Prompt wire: 3 sửa đổi, đúng import `vn.apero.armeasure.ar.presentation.host.ArMeasureHub`.
7. Chạy trên host 1, gate build.

## Todo
- [ ] `HOST_SCHEMA`
- [ ] Agent Scan + 2 điều kiện dừng
- [ ] Stage copy / TOML / wire
- [ ] `:AR_feature:compileDebugKotlin` + `testDebugUnitTest` xanh (102 test)
- [ ] `:app:assembleDebug` + `:app:assembleRelease` xanh

## Success criteria
- Host chưa có module → sau khi chạy, build cả debug lẫn release xanh
- Case C / minSdk chặn thật, không âm thầm chạy tiếp
- Không file nào của `AR_feature` bị sửa

## Risk assessment
- Thiếu alias bucket-C → build lỗi "missing libs.*". Bám bảng trong `catalog-merge.md`
- Host kotlin cũ hơn 2.4.10 → để `compileDebugKotlin` trả lời, **không** tự bump kotlin của host
- Agent sửa nhầm `AR_feature/build.gradle.kts` → cấm rõ trong prompt + kiểm `git status`

## Security
Không commit `local.properties`. Không in nội dung file credential khi đọc host.

## Next steps
Kết quả Scan (`case`, `tomlActions`) đi tiếp vào report gate 2.
