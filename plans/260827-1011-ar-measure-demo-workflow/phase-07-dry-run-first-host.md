# Phase 07 — Dry run host thứ nhất

## Overview
- Priority: P1
- Status: pending — chặn bởi 04, 05, 06
- Chạy end-to-end lần đầu trên host thật, không can thiệp tay giữa chừng. Mục đích là **lòi lỗi**,
  không phải để đẹp.

## Key insights
- Lần chạy đầu gần như chắc chắn fail ở TOML bucket-C hoặc ở prompt restyle. Đó là mục đích.
- Chạy trên nhánh riêng của host, không phải nhánh chính — rollback bằng `git checkout` 1 lệnh.
- Workflow có `resumeFromRunId`: sửa script rồi chạy lại, phần agent không đổi trả cache ngay,
  không phải làm lại từ đầu. Dùng triệt để ở phase này.

## Requirements
- F1: 1 lượt end-to-end: gate 1 → workflow → gate 2, người không sửa gì giữa chừng.
- F2: mỗi lần fail ghi lại: fail ở agent nào, vì sao, sửa prompt hay sửa KB.
- F3: sau khi xanh, rollback host về sạch rồi chạy lại lần nữa để xác nhận lặp lại được.

## Related code files
- Host: nhánh `feat/ar-measure-demo` (tạo mới)
- Tạo: `dry-run-log.md` trong thư mục plan

## Implementation steps
1. `git checkout -b feat/ar-measure-demo` trong host. Xác nhận `git status` sạch.
2. Chạy gate 1 → workflow → gate 2. Không can thiệp.
3. Ghi log từng lần fail vào `dry-run-log.md`.
4. Sửa script/prompt, `Workflow({scriptPath, resumeFromRunId})` để chạy tiếp từ chỗ hỏng.
5. Khi xanh: `git reset --hard` + xoá `AR_feature/` trong host, chạy lại từ đầu.
6. Lần 2 phải xanh mà không sửa gì thêm.
7. Phát hiện nào thuộc về KB (host drift, alias mới, geometry lệch) → **cập nhật vào
   `references/*.md` của skill**, không chỉ sửa script. Đây là vòng lặp KB.

## Todo
- [ ] Nhánh host + xác nhận sạch
- [ ] Lượt chạy 1, ghi `dry-run-log.md`
- [ ] Sửa + resume tới khi xanh
- [ ] Rollback, chạy lại lượt 2 không sửa
- [ ] Cập nhật KB skill từ phát hiện thu được

## Success criteria
- Lượt 2 xanh end-to-end, không can thiệp tay
- `assembleDebug` + `assembleRelease` xanh
- ≥1 phát hiện được đưa ngược vào KB skill (chứng minh loop có thật)

## Risk assessment
- Host bẩn từ trước → xác nhận `git status` trước khi chạy, nếu không sạch thì dừng
- Chạy lại nhiều lần tốn token → dùng `resumeFromRunId`, đừng chạy lại từ đầu

## Security
Không commit `local.properties`, keystore, `google-services.json` của host lên nhánh demo.

## Next steps
Host 2 ở phase 08 để chứng minh không hardcode.
