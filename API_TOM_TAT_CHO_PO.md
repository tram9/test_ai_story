# Tóm tắt tích hợp API sinh truyện AI — dành cho PO / Head

> Tài liệu ngắn, không có code. Chi tiết kỹ thuật: `API_TICH_HOP.md` (cách tích hợp) và `API_AI_STORY.md` (tổng hợp API).

---

## 1. Đây là gì

Ứng dụng test cho phép người dùng **nhập ý tưởng → nhận truyện do AI viết** kèm **ảnh bìa**, theo 2 chế độ:

| Chế độ | Người dùng nhập | Nhận về |
|---|---|---|
| **Truyện ngắn** | nhân vật, mô tả truyện, thể loại, kiểu kết, phong cách bìa, văn phong | 1 truyện hoàn chỉnh + ảnh bìa |
| **Truyện nhiều chương** | tiêu đề, số chương (1–15), chủ đề, thể loại | dàn ý các chương + ảnh bìa; bấm từng chương để sinh nội dung |

Ngoài ra có nút **"Dịch sang…"** dưới kết quả, hỗ trợ 12 ngôn ngữ (Việt, Indonesia, Tây Ban Nha, Urdu, Tagalog, Bồ Đào Nha, Hindi, Hàn, Nhật, Đức, Pháp, Ý).

---

## 2. Tích hợp như thế nào (mức tổng quan)

1. App gửi thông tin người dùng nhập lên **máy chủ sinh truyện** (3 lời gọi: truyện ngắn / tạo dàn ý / sinh từng chương).
2. Máy chủ trả về kết quả **đã mã hóa**; app giải mã rồi hiển thị.
3. Truyện nhiều chương gọi 2 bước: tạo dàn ý trước, rồi sinh nội dung từng chương theo yêu cầu.
4. Dịch: app gọi thêm một dịch vụ dịch bên ngoài khi người dùng bấm nút.

Không cần thư viện bên thứ ba; phần tích hợp gọn, gồm khoảng 6 file dữ liệu và 4 màn hình.

---

## 3. Trải nghiệm người dùng cần lưu ý

| Điểm | Ảnh hưởng |
|---|---|
| **Tạo dàn ý nhiều chương có thể mất tới ~5 phút** | Cần màn chờ rõ ràng, không được để người dùng tưởng app treo |
| **Kết quả nhận được khi test đều là tiếng Anh** (chưa kiểm chứng kỹ) | Người dùng Việt phải bấm "Dịch sang…" để đọc tiếng Việt |
| Nhập tiếng Việt vẫn được (server hiểu) | Nhưng đầu ra không tự đổi ngôn ngữ theo đầu vào |
| Sinh nội dung theo từng chương | Mỗi chương là một lần chờ riêng |
| Lỗi tạm thời từ server (mã 999) | Cần nút "Thử lại" |

---

## 4. Rủi ro — cần cân nhắc trước khi quyết định

| # | Rủi ro | Mức độ | Ghi chú |
|---|---|---|---|
| 1 | **Máy chủ thuộc bên thứ ba, không phải của mình** | **Cao** | Địa chỉ và khóa giải mã được lấy từ bản cài đặt của một ứng dụng đã phát hành (`com.bf.aistory`), không phải API được công bố cho đối tác. Chưa có thỏa thuận sử dụng; có thể vi phạm điều khoản/bản quyền và có thể bị chặn hoặc đổi bất kỳ lúc nào |
| 2 | **Không có xác thực, không có cam kết dịch vụ (SLA)** | Cao | Không biết giới hạn số lượt gọi, chi phí hay độ ổn định |
| 3 | Giao tiếp không mã hóa (http, không phải https) | Trung bình | Dữ liệu người dùng nhập đi qua đường truyền không bảo mật |
| 4 | **Dịch vụ dịch không chính thức** | Trung bình | Đang dùng cổng dịch miễn phí không công bố của Google; đã từng bị chặn (lỗi 429) trong lúc test |
| 5 | Nội dung do AI sinh | Trung bình | Chưa có bước kiểm duyệt nội dung; cần xác định chính sách với truyện trẻ em / nội dung nhạy cảm |
| 6 | Cấu trúc dữ liệu dàn ý chương chưa được tài liệu hóa | Thấp | Đang đọc "mềm"; có thể cần chỉnh khi test với server thật |

---

## 5. Hiện trạng

**Đã có:** bản app test chạy được ở mức mã nguồn (đã biên dịch thành công) với cả 2 chế độ và nút dịch.

**Chưa kiểm chứng** (chưa chạy thử đầy đủ với server thật):
- Định dạng dàn ý chương thực tế server trả về.
- Ô thể loại nhập tự do có được server chấp nhận không.
- Server có viết theo ngôn ngữ được yêu cầu không (hiện coi như **không**, kết quả ra tiếng Anh).
- Hiệu năng và tỷ lệ lỗi khi dùng thật.

---

## 6. Đề xuất

| Mục đích | Đề xuất |
|---|---|
| **Chỉ tìm hiểu / demo nội bộ** | Dùng được như hiện tại; không phát hành ra người dùng |
| **Đưa vào sản phẩm thật** | **Không nên dùng nguyên trạng.** Chọn một trong: (a) liên hệ nhà cung cấp để có hợp đồng và khóa chính thức; (b) tự dựng dịch vụ sinh truyện bằng mô hình AI có API chính thức (có hợp đồng, xác thực, https, kiểm soát chi phí) |
| **Phần dịch** | Thay cổng dịch không chính thức bằng dịch vụ chính thức (Google Cloud Translation cần khóa API), hoặc dịch ngay trên máy (~30MB tải một lần theo ngôn ngữ) |
| **Ngôn ngữ đầu ra** | Nếu dựng dịch vụ riêng, có thể yêu cầu AI viết thẳng bằng ngôn ngữ người dùng — không cần bước dịch |

**Việc cần PO/Head quyết:**
1. Có tiếp tục đi theo API này cho sản phẩm thật không, hay chuyển sang phương án (a) / (b)?
2. Ngôn ngữ mục tiêu ưu tiên là gì (để chọn cách dịch phù hợp)?
3. Chính sách nội dung AI sinh ra (kiểm duyệt, độ tuổi)?
