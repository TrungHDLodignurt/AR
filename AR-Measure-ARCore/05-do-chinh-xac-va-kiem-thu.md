# 05 — Độ chính xác & kế hoạch kiểm thử

## 1. Sự thật cần nói thẳng ngay từ đầu

> **Google không công bố bất kỳ con số độ chính xác nào cho việc đo bằng ARCore.**
> Tài liệu chỉ nêu Depth API có dải tối ưu 0.5–5 m và cảnh báo bề mặt ít hoạ tiết cho kết quả *"không chính xác"*.

Mọi con số bạn thấy trên các blog kỹ thuật đều là quan sát riêng lẻ, không phải chuẩn. Một blog triển khai thực tế nói thẳng: *thước dây/thước cứng cho kết quả chính xác hơn; app AR chỉ cho ước lượng gần đúng*, và độ chính xác *"phụ thuộc điều kiện ánh sáng, chất lượng camera và loại vật thể được đo."*

**Hệ quả cho sản phẩm:**
1. **Không đặt cam kết độ chính xác vào marketing** trước khi có số đo bench test nội bộ.
2. **Không nhắm vào use case cần độ chính xác cao** (gia công, cắt kính, may đo) bằng AR đơn thuần.
3. Use case phù hợp: ước lượng kích thước nội thất, kiểm tra vật có vừa không gian, ước lượng kích thước thùng hàng/logistics, ước lượng vật liệu, ghi chú kích thước sơ bộ tại hiện trường.

---

## 2. Cây nguồn sai số

```
Sai số cuối cùng
├── Sai số scale toàn cục (drift của SLAM)
│     • Nguyên nhân: fusion camera+IMU tích luỹ lệch theo thời gian/khoảng di chuyển
│     • Biểu hiện: mọi số đo cùng lệch theo một hướng; đo càng dài lệch càng nhiều
│     • Giảm thiểu: dùng Anchor (ARCore tự tinh chỉnh); phiên đo ngắn; lia lại để loop-closure
│
├── Sai số định vị điểm (lớn nhất trong thực tế)
│     • Nguồn A: chiều sâu sai — bề mặt ít hoạ tiết, ngoài dải 0.5–5m, scene động
│     • Nguồn B: người dùng chạm sai chỗ — 1 pixel ở 3 m ≈ vài mm..cm ngoài thực tế
│     • Nguồn C: điểm đo thật nằm ở góc/cạnh, nơi depth luôn nhiễu nhất
│     • Giảm thiểu: reticle giữa màn hình; lọc confidence; median nhiều frame;
│                   snap vào biên mặt phẳng; cho kéo tinh chỉnh
│
├── Sai số hình học (chỉ với bounding box)
│     • Nguồn: mặt nền lệch, trục chính chọn sai, min/max thay vì percentile
│     • Giảm thiểu: ép vuông góc, percentile 2/98, outlier removal
│
└── Sai số môi trường
      • Ánh sáng yếu, chuyển động nhanh, bề mặt phản chiếu/trong suốt
      • Giảm thiểu: cổng chất lượng + hướng dẫn chủ động, không cho đo khi chất lượng xấu
```

---

## 3. Kỹ thuật giảm sai số — xếp theo tỉ lệ lợi ích/chi phí

| # | Kỹ thuật | Chi phí | Lợi ích | Ưu tiên |
|---|---|---|---|---|
| 1 | **Dùng `Anchor` và đọc pose mỗi frame** (không lưu `hitPose` tĩnh) | ~0 | Cao — ARCore tự tinh chỉnh pose | 🔴 P0 |
| 2 | **Reticle giữa màn hình** thay vì chạm tự do | Thấp | Rất cao — loại bỏ sai số ngón tay | 🔴 P0 |
| 3 | **Cổng chất lượng**: chặn đo khi ngoài 0.3–5 m, khi mất tracking, khi confidence thấp | Thấp | Cao — loại các số đo rác | 🔴 P0 |
| 4 | **Ưu tiên DepthPoint → Plane → Point**, loại InstantPlacementPoint | Thấp | Cao | 🔴 P0 |
| 5 | **Median/EMA của reticle qua 5–10 frame** | Thấp | Trung bình — reticle ổn định, người dùng chạm chính xác hơn | 🟠 P1 |
| 6 | **Chiếu 2 điểm cùng mặt phẳng xuống mặt phẳng** trước khi tính | Thấp | Trung bình–Cao khi đo cạnh phẳng | 🟠 P1 |
| 7 | **Cho kéo tay cầm để tinh chỉnh** sau khi đặt điểm | Trung bình | **Rất cao** — người dùng tự sửa được sai số còn lại | 🟠 P1 |
| 8 | **Snap vào biên/góc mặt phẳng** khi reticle ở gần (< 3 cm) | Trung bình | Cao khi đo đồ nội thất, hộp | 🟠 P1 |
| 9 | **Percentile 2/98 + statistical outlier removal** cho point cloud | Trung bình | Rất cao cho bounding box | 🟠 P1 (pha 3) |
| 10 | **Gộp nhiều frame** khi dựng point cloud (15–40 frame từ nhiều góc) | Cao | Cao | 🟡 P2 |
| 11 | **Hiệu chuẩn tuỳ chọn bằng Augmented Images**: người dùng in một marker có kích thước biết trước, đặt cạnh vật → app suy ra hệ số hiệu chỉnh scale | Cao | Cao nhưng chỉ cho user chuyên nghiệp | 🟡 P2 |

---

## 4. Tiêu chí chấp nhận đề xuất

> Đây là **đề xuất của bộ tài liệu này**, không phải cam kết của ARCore. Chốt lại sau vòng bench test đầu tiên.

### M1 — Đo khoảng cách 2 điểm

| Điều kiện | Sai số tuyệt đối cho phép | Sai số tương đối cho phép |
|---|---|---|
| 0.3–1.0 m, bề mặt có hoạ tiết, sáng tốt | ≤ 1.5 cm | ≤ 3 % |
| 1.0–3.0 m, bề mặt có hoạ tiết, sáng tốt | ≤ 3 cm | ≤ 2 % |
| 3.0–5.0 m, bề mặt có hoạ tiết, sáng tốt | ≤ 8 cm | ≤ 3 % |
| Bề mặt ít hoạ tiết / sáng yếu | **Không cam kết** — phải hiện cảnh báo cho người dùng | — |

**Độ lặp lại (repeatability)**: đo 5 lần cùng một đoạn → độ lệch chuẩn ≤ ½ ngưỡng sai số ở trên. *Chỉ số này quan trọng không kém độ chính xác*: người dùng phát hiện ngay nếu đo 2 lần cho 2 số khác nhau.

### M4/M5 — Bounding box vật thể

| Điều kiện | Sai số từng chiều | Sai số thể tích |
|---|---|---|
| Vật hình hộp, 20–100 cm, đặt trên mặt phẳng có hoạ tiết | ≤ 5 % hoặc ≤ 3 cm (lấy giá trị lớn hơn) | ≤ 15 % |
| Vật không phải hình hộp (bo cong, không đối xứng) | ≤ 10 % | ≤ 30 % |
| Vật < 10 cm | **Ngoài phạm vi hỗ trợ** — chặn trong UI | — |

---

## 5. Kế hoạch bench test

### 5.1. Chuẩn bị ground truth

| Hạng mục | Chi tiết |
|---|---|
| Thiết bị đối chiếu | **Thước laser** (sai số ±1.5 mm) + thước dây 5 m + thước cặp cho vật nhỏ |
| Bộ vật mẫu (10 mẫu) | Thùng carton (3 kích cỡ khác nhau), bàn, cửa, tủ, ghế, vali, cái gối (vật mềm/bo cong), lọ hình trụ |
| Bộ đoạn mẫu (12 đoạn) | 4 đoạn ở mỗi dải: 0.3–1 m, 1–3 m, 3–5 m; mỗi dải có 2 đoạn trên bề mặt hoạ tiết, 2 đoạn trên bề mặt trơn |
| Điều kiện sáng (3 mức) | Sáng ban ngày trong nhà (> 300 lux) / đèn phòng ban đêm (~100 lux) / tối yếu (< 30 lux) |
| Điều kiện bề mặt (4 loại) | Gỗ có vân / tường trắng phẳng / mặt kính / vải tối màu |

### 5.2. Ma trận thiết bị (chọn 6–8 máy)

| Vai trò | Ví dụ | Vì sao có trong ma trận |
|---|---|---|
| Cao cấp có Depth | Pixel 8 Pro / Galaxy S-series mới | Đường cơ sở tốt nhất |
| Trung cấp có Depth | Galaxy A-series, moto g 5G | **Đây là phân khúc quyết định** — đông user nhất |
| Cận đáy có Depth | Pixel 4a hoặc máy Depth cũ nhất trong top-20 của bạn | Tìm ngưỡng dưới |
| Không có Depth (PLANE_ONLY) | Pixel 1 hoặc bất kỳ máy được ARCore chứng nhận nhưng không có Depth | Verify fallback |
| Không hỗ trợ ARCore | Máy giá rẻ bất kỳ ngoài danh sách | Verify màn hình UNSUPPORTED |
| Có ToF (nếu mua được) | LG V60 ThinQ | Đo xem ToF cải thiện được bao nhiêu — tham khảo |

### 5.3. Quy trình đo cho mỗi tổ hợp

```
Với mỗi (thiết bị × đoạn/vật mẫu × điều kiện sáng):
  1. Khởi động lại app (session mới, không tái dùng tracking cũ)
  2. Lia máy theo hướng dẫn onboarding, tối đa 10 giây
  3. Đo 5 lần liên tiếp, ghi lại: giá trị, tier, HitQuality, khoảng cách camera,
     confidence trung bình, thời gian từ lúc mở đến lúc đo được
  4. Ghi lại ground truth từ thước laser
  5. Tính: sai số tuyệt đối, sai số tương đối, độ lệch chuẩn của 5 lần
```

### 5.4. Kiểm thử hồi quy tự động — dùng Recording & Playback

Đây là phần **cần đầu tư sớm**, tiết kiệm rất nhiều công về sau:

```
Bước 1 (một lần): Tại hiện trường, dùng Recording API ghi 12–20 file MP4 dataset,
                  mỗi file kèm ground truth đã đo bằng thước laser.
Bước 2:           Lưu dataset vào repo/artifact store.
Bước 3 (mỗi PR):  Trên CI hoặc device farm, dùng Playback API cho app xử lý lại
                  từng dataset và tự động xuất số đo.
Bước 4:           So với ground truth → fail build nếu sai số vượt ngưỡng chấp nhận.
```

Giá trị: mọi thay đổi thuật toán (đổi ngưỡng confidence, đổi percentile, đổi cách lọc) đều được **định lượng ngay lập tức** thay vì "cảm giác là tốt hơn". MP4 dataset chứa cả IMU nên ARCore xử lý giống hệt session thật.

### 5.5. Mẫu bảng ghi kết quả

```csv
device,android_ver,tier,sample_id,sample_type,ground_truth_m,light_lux,surface,
run1_m,run2_m,run3_m,run4_m,run5_m,mean_m,stddev_m,abs_err_m,rel_err_pct,
hit_quality,cam_distance_m,avg_confidence,time_to_first_measure_s,notes
```

---

## 6. Telemetry cần gắn từ ngày đầu

Không có dữ liệu này thì không cải thiện được độ chính xác sau khi phát hành:

| Sự kiện | Thuộc tính |
|---|---|
| `ar_measure_opened` | tier, device model, android version |
| `ar_session_ready` | thời gian từ lúc mở đến lúc đủ điều kiện đo (giây) |
| `ar_tracking_failure` | reason, thời điểm trong phiên |
| `ar_point_placed` | trackable source (DepthPoint/Plane/Point), hit quality, khoảng cách camera, confidence |
| `ar_measurement_completed` | giá trị, đơn vị, mức tin cậy, số lần undo, số lần kéo tinh chỉnh |
| `ar_measurement_abandoned` | bước bị bỏ dở, lý do suy đoán |
| `ar_measurement_adjusted` | **độ lệch giữa giá trị tự động và giá trị người dùng chỉnh** ← chỉ số vàng: cho biết thuật toán sai bao nhiêu, trên dữ liệu thật, ở quy mô lớn |

Chỉ số `ar_measurement_adjusted` là proxy tốt nhất cho độ chính xác thực tế mà không cần ground truth. Nếu người dùng liên tục kéo cạnh dài ra thêm 8 %, bạn biết ngay percentile đang cắt quá nhiều.
