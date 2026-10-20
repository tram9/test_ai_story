# Hướng dẫn cung cấp data truyện demo

Điền vào file `TEMPLATE_demo_stories.json` (sao chép khối trong `stories` cho mỗi truyện) và gửi kèm các file ảnh bìa.

| Trường | Bắt buộc | Quy tắc |
|---|---|---|
| `id` | có | chữ thường, không dấu, nối bằng `-`, **duy nhất**, không đổi về sau |
| `title` | có | tối đa ~40 ký tự (card trên Home bị cắt chữ dài) |
| `genre` | có | đúng một trong 8 giá trị trong template, viết đúng hoa/thường |
| `cover` | có | tên file ảnh gửi kèm, trùng `id` (vd `the-lost-city.png`) |
| `synopsis` | có | 1–3 câu |
| `hot` | có | `true` nếu hiện ở mục Hot story, ngược lại `false` |
| `chapters[].index` | có | bắt đầu từ 1, liên tục, không trùng |
| `chapters[].title` | có | tên chương |
| `chapters[].content` | có | văn bản thuần (không HTML, không markdown); đoạn cách nhau `\n\n` |

**Ảnh bìa:** định dạng PNG hoặc JPG, tỉ lệ dọc 2:3, tối thiểu 512×768, mỗi file dưới ~500KB.

**Truyện ngắn:** vẫn điền `chapters` với **1 phần tử**.

**Số lượng:** 3–6 truyện, mỗi truyện 2–5 chương là đủ cho demo.

**Lưu ý:** file phải là JSON hợp lệ, mã hóa UTF-8; xuống dòng trong `content` viết là `\n`, không để xuống dòng thật bên trong chuỗi.
