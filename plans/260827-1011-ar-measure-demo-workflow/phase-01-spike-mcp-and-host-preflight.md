# Phase 01 — Spike: pencil MCP trong subagent + pre-flight host

**Context:** [brainstorm R1/R3](../reports/brainstormer-260827-1011-ar-measure-demo-workflow.md) ·
[detection.md](~/.claude/skills/trung-apply-ar-measure/references/detection.md)

## Overview
- Priority: P0 — chặn toàn bộ phase còn lại
- Status: pending
- Trả lời 2 câu chưa ai biết: subagent trong Workflow có gọi được `mcp__pencil__*` không, và host
  ứng viên có đủ điều kiện build không.

## Key insights
- Tool doc cảnh báo MCP xác thực tương tác (pencil, claude.ai) **có thể vắng mặt** trong headless /
  background run. Workflow chạy background → đúng vùng rủi ro.
- Nếu fail: không phải hỏng kế hoạch. Đường lui đã có — main loop scan 1 lần, dump JSON, truyền qua
  `args`. Nhưng phải biết TRƯỚC khi viết phase 05.
- Pre-flight rẻ (2 phút/host) nhưng bỏ qua thì build đỏ ngay trên màn chiếu.

## Requirements
- F1: chạy 1 workflow tối giản, 1 agent, yêu cầu nó `ToolSearch("select:mcp__pencil__read_skill")`
  rồi gọi thật. Ghi lại kết quả nguyên văn.
- F2: với mỗi host owner cung cấp — đọc `compileSdk`, `minSdk`, `kotlin`, `agp`, JDK đang chạy.
- NF1: không sửa file nào của host ở phase này. Read-only.

## Related code files
- Tạo: `plans/260827-1011-ar-measure-demo-workflow/spike-mcp-result.md`
- Đọc: `<host>/gradle/libs.versions.toml`, `<host>/app/build.gradle.kts`, `<host>/settings.gradle.kts`

## Implementation steps
1. Viết workflow 1-agent tối giản, prompt: "gọi `mcp__pencil__read_skill`, trả về nguyên văn kết quả
   hoặc lỗi". Chạy.
2. Ghi kết quả vào `spike-mcp-result.md`: **gọi được** / **không thấy tool** / **lỗi auth**.
3. Nếu không gọi được → chốt luôn: phase 05 dùng JSON-handoff, ghi vào plan.md unresolved #1.
4. Xin owner path các host ứng viên.
5. Mỗi host chạy pre-flight:
   ```bash
   grep -nE "compileSdk|minSdk|targetSdk|JavaVersion" <host>/app/build.gradle.kts
   grep -nE "^(agp|kotlin|composeBom) =" <host>/gradle/libs.versions.toml
   java -version; grep -n "org.gradle.java.home" <host>/gradle.properties
   grep -c "AR_feature" <host>/settings.gradle.kts   # Case A/B
   ```
6. Lập bảng host: qua / không qua / cần sửa gì. Host `compileSdk` < 36 hoặc JDK < 17 → loại khỏi demo.

## Todo
- [ ] Workflow spike 1 agent gọi pencil MCP
- [ ] Ghi `spike-mcp-result.md`
- [ ] Xin path host từ owner
- [ ] Pre-flight từng host
- [ ] Bảng kết luận host nào demo được

## Success criteria
- Biết chắc (có log, không suy đoán) subagent gọi được pencil MCP hay không
- ≥2 host qua pre-flight, ghi rõ Case A/B và minSdk có bị nâng sàn không

## Risk assessment
- MCP fail → đã có fallback, không chặn
- Owner chưa cấp host kịp → làm được phần MCP trước, pre-flight sau; nhưng 07/08 sẽ trượt lịch

## Security
- Chỉ đọc host, không ghi. Không log giá trị trong `local.properties` / keystore / API key nếu vô
  tình gặp khi grep.

## Next steps
Kết quả MCP quyết định thiết kế phase 05. Bảng host là đầu vào phase 07/08.
