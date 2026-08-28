# V1 và V2 — hai ca phải test bằng tay

Hai ca này không script được vì cần thao tác vật lý. Mỗi ca ~2 phút.

## Chuẩn bị

Cắm Pixel, rồi chạy:

    cd /Users/admin/ahndroidne/StudioProjects/ar-tape-measure
    ./gradlew :app:assembleDebug
    adb devices                                    # lấy serial, đừng dùng serial cũ
    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

Mở một terminal riêng để xem log trong lúc thao tác:

    adb -s <serial> logcat -c
    adb -s <serial> logcat | grep -iE "ARCore session update failed|anchor|Session|FATAL"

---

## V1 — điểm đo có sống sót khi camera mất tracking không?

**Nghi ngờ:** khi app tự khởi động lại AR sau khi mất tracking, nó đóng ARCore session nhưng các điểm
đã đặt vẫn trỏ vào session cũ đã chết. Nếu đúng thì sau khi camera trở lại, các điểm thành vô dụng.

**Đây là lỗi có sẵn từ trước, không phải do refactor.** Cần biết nó có thật không trước khi sửa, vì
chỗ sửa nằm trong vòng đời session ARCore — phần README §12 ghi lại lịch sử những lần sửa hỏng.

### Làm

1. Mở app → **AR Measure** → quét cho tới khi hết chữ "Move your phone to find a surface"
2. **Đặt 2–3 điểm** (quan trọng: phải có điểm rồi mới làm bước sau)
3. **Lấy tay che kín camera sau 10–15 giây** cho tới khi ảnh đứng hoặc app báo mất tracking
4. Bỏ tay ra, đợi camera bắt lại bề mặt
5. Thử **đặt thêm một điểm** và thử **bấm undo**

### Kết quả

- **ĐẠT** — các điểm cũ vẫn còn (hoặc bị xoá sạch một cách gọn gàng), đặt điểm mới được, undo được
- **LỖI CÓ THẬT** — overlay đứng im, chạm không ăn, hoặc log hiện `ARCore session update failed`

> Thư viện **nuốt exception** từ frame callback của app, nên triệu chứng có thể chỉ là overlay đứng
> im kèm đúng một dòng log. Nếu thấy lạ mà không crash, tìm dòng đó.

### Nếu lỗi có thật, làm thêm một lần nữa

Lặp lại y hệt nhưng **không đặt điểm nào** trước khi che camera. Nếu lần này bình thường, tức lỗi chỉ
xảy ra khi có điểm đang sống — đúng như chẩn đoán. Nếu vẫn hỏng, thì đó là lỗi remount nói chung,
khác với điều đang nghi.

---

## V2 — tap rồi giật máy có ăn điểm sai không?

**Nghi ngờ:** app kiểm tra "máy có đứng yên không" ở lúc chạm, nhưng sau khi đổi sang MVI thì giữa cú
chạm và lúc ghi điểm có thêm một bước trung gian. Nếu không kiểm lại lần nữa, một cú chạm lúc máy đang
yên có thể được ghi lại **sau khi** máy đã rung.

Hậu quả nếu đúng: điểm rơi lệch chỗ, và người dùng không biết vì sao.

### Làm

1. **AR Measure → Distance**, quét cho tới khi reticle ở trạng thái ổn định
2. Nhắm vào một điểm dễ nhớ trên bề mặt (góc bàn, vết bẩn...)
3. **Chạm rồi giật mạnh máy ngay lập tức** — chạm phải rơi vào lúc còn đứng yên, giật phải xảy ra
   trong một hai frame kế tiếp
4. Làm khoảng **10 lần**
5. Rồi làm **10 lần chạm bình thường**, giữ máy yên, cùng một điểm nhắm

### Kết quả

- **ĐẠT** — cú giật bị từ chối giống như chạm lúc máy đang rung, hoặc điểm vẫn rơi đúng chỗ nhắm
- **LỖI CÓ THẬT** — cú giật ghi điểm **lệch rõ** khỏi chỗ reticle đang chỉ, trong khi cùng thao tác mà
  không giật thì rơi đúng

### Ca đối chứng

Làm y hệt với **Box** hoặc **Cylinder** ở bước đo chiều cao. Bước đó *có* kiểm lại độ ổn định, nên nó
là mốc so: nếu Distance lệch mà Box không lệch, chẩn đoán được xác nhận.

---

## Ghi lại kết quả

Mỗi ca một dòng: **V1/V2 · đạt hay lỗi · thấy gì** (dòng log, hoặc điểm lệch bao xa).

Ca không làm được thì ghi **"chưa làm"** — không phải "đạt".
