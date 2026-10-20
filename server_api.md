# Đặc tả server trung gian (proxy) — Truyện nhiều chương


## 2. Kiến trúc

```
App ──https JSON──▶ Server của mình ──http form + AES──▶ API gốc (novelv2.breakerai.com)
                         │
                         ├─ DB: novel, chương, job
                         ├─ Lưu ảnh bìa (tải từ API gốc → storage/CDN của mình)
                         └─ (tùy chọn) dịch bằng dịch vụ chính thức
```
---

## 3. Hợp đồng API giữa App ↔ Server

Base URL do đội backend cấp (bắt buộc **https**). Mọi request có header `Authorization: Bearer <token>`.
Body/response là **JSON UTF-8** (không phải form, không mã hóa).

### 3.1. Tạo truyện (bất đồng bộ)

`POST /v1/novels`

Header tùy chọn: `Idempotency-Key: <uuid>` — cùng key gửi lại thì trả lại job cũ, **không** gọi API gốc lần nữa (tránh tốn tiền khi app retry).

```json
{
  "title": "The Last Moonlight",
  "numChapter": 5,
  "topic": "A young knight sets out to save a hidden village",
  "genre": "Fantasy"
}
```

| Field | Bắt buộc | Quy tắc |
|---|---|---|
| `title` | có | không rỗng |
| `numChapter` | có | số nguyên **1–15**; ngoài khoảng → 400 |
| `topic` | có | không rỗng (có thể nhập tiếng Việt (theo quan sát khi test, chưa kiểm chứng)) |
| `genre` | có | chuỗi tự do; gợi ý: Fantasy, Romance, Sci-fi, Adventure, Horror, Mystery, Story for kid, Mythology |
Response `202 Accepted`:
```json
{ "jobId": "job_8f2c", "status": "pending" }
```

### 3.2. Hỏi trạng thái

`GET /v1/jobs/{jobId}`

```json
{ "jobId": "job_8f2c", "status": "pending" }
```
```json
{
  "jobId": "job_8f2c",
  "status": "done",
  "novel": {
    "id": "nov_31ab",
    "title": "The Last Moonlight",
    "genre": "Fantasy",
    "coverUrl": "https://cdn.example.com/covers/nov_31ab.png",
    "chapters": [
      { "index": 1, "title": "…", "status": "empty" },
      { "index": 2, "title": "…", "status": "empty" }
    ]
  }
}
```
```json
{ "jobId": "job_8f2c", "status": "failed", "error": { "code": "UPSTREAM_TEMPORARY", "message": "…", "retryable": true } }
```

- `status`: `pending` | `done` | `failed`.
- App hỏi lại mỗi **3–5 giây**; server nên hỗ trợ `Retry-After`. Job quá hạn (vd 10 phút) → `failed` với `TIMEOUT`.
- `chapters[].index` bắt đầu từ **1**. `status` của chương: `empty` | `ready`.

### 3.3. Sinh nội dung một chương

`POST /v1/novels/{novelId}/chapters/{index}/generate`

Response `200` (đồng bộ, API gốc timeout 60s):
```json
{ "index": 1, "title": "…", "content": "…nội dung chương…", "status": "ready" }
```

- Chương đã `ready` → **trả từ cache**, không gọi API gốc.
- Hai request đồng thời cho cùng một chương → chỉ gọi API gốc **một lần**, request sau chờ kết quả đó.
- Nếu sinh lâu/không ổn định, có thể đổi sang dạng job giống 3.1 (giữ nguyên cách hỏi trạng thái).

### 3.4. Lấy lại truyện đã tạo

`GET /v1/novels/{novelId}` → cùng dạng `novel` ở 3.2, kèm `content` cho các chương đã `ready`.

### 3.5. Dạng lỗi thống nhất

HTTP status + body:
```json
{ "error": { "code": "VALIDATION", "message": "numChapter must be 1..15", "retryable": false } }
```

| `code` | HTTP | `retryable` | Khi nào |
|---|---|---|---|
| `VALIDATION` | 400 | không | sai/thiếu field |
| `UNAUTHORIZED` | 401 | không | thiếu/sai token |
| `NOT_FOUND` | 404 | không | sai `jobId`/`novelId`/`index` |
| `RATE_LIMITED` | 429 | có | vượt hạn mức của người dùng |
| `UPSTREAM_TEMPORARY` | 503 | **có** | API gốc trả `errorCode = 999` |
| `UPSTREAM_ERROR` | 502 | không | API gốc trả lỗi khác / dữ liệu không đọc được |
| `TIMEOUT` | 504 | có | quá thời gian chờ |

---

## 4. Server ↔ API gốc (phần ẩn sau server)

### 4.1. Endpoint và key

| | Giá trị |
|---|---|
| Base URL | `http://novelv2.breakerai.com` (chỉ **http**) |
| Tạo dàn ý | `POST /api/split_chapter` — timeout **300s** |
| Sinh chương | `POST /api/gen_chapter` — timeout 60s |
| Key giải mã AES (16 ký tự) | `YxJstNNI1kuCFP0v` — dùng cho cả `split_chapter` và `gen_chapter` |

> **Tài liệu này chứa key — chỉ chia sẻ nội bộ đội backend.** Khi triển khai, đặt key trong secret manager / biến môi trường `NOVEL_AES_KEY`, **không** hard-code vào mã nguồn/repo.

### 4.2. Request tới API gốc

`Content-Type: application/x-www-form-urlencoded` (không phải JSON).

**`split_chapter`** — 12 field:

| Field gửi đi | Lấy từ |
|---|---|
| `title` | `title` |
| `num_chapter` | `numChapter` |
| `topic` | `topic` (xem mục 7 nếu có `language`) |
| `genre` | `genre` |
| `writing_style`, `characters`, `world`, `plot`, `prompt`, `negative_prompt` | luôn `""` |
| `width` | `512` |
| `height` | `768` |

**`gen_chapter`**

| Field | Lấy từ |
|---|---|
| `user_id` | `upstreamUserId` — do `split_chapter` trả về, server lưu cùng novel |
| `title` | tiêu đề chương (từ dàn ý) |

Luồng: `split_chapter` → lấy `userId` + danh sách chương → mỗi chương gọi `gen_chapter` khi app yêu cầu.

### 4.3. Giải mã response

1. Body là chuỗi JSON có ngoặc kép bao ngoài → bóc `"`.
2. 16 ký tự đầu = **IV** (UTF-8).
3. Phần còn lại = **Base64** ciphertext.
4. `AES/CBC/NoPadding`, key UTF-8 (16 byte).
5. Cắt ký tự đệm cuối (`\0`, khoảng trắng, xuống dòng), parse JSON.

Đọc cả body khi HTTP 500 (server gốc vẫn trả nội dung).

### 4.4. Cấu trúc response gốc

```json
{ "errorCode": 0, "errorMessage": "", "data": { "…dàn ý…" }, "userId": "…", "url": "/static/….png" }
```
```json
{ "errorCode": 0, "errorMessage": "", "data": { "content": "…nội dung chương…" } }
```

- Tên khóa lỗi có thể là `errorCode`/`errorMessage` **hoặc** `error_code`/`error_message` → đọc cả hai.
- `errorCode == 0` là thành công; `999` = lỗi tạm thời.
- Ảnh bìa: tải từ `http://novelv2.breakerai.com` + `url`, rồi **lưu về storage/CDN của mình** và trả `coverUrl` https cho app.

> ⚠️ **Chưa kiểm chứng:** cấu trúc `data` của `split_chapter` ("dàn ý các chương") chưa được tài liệu mô tả.
> Backend cần **gọi thử một lần, xem JSON thật**, rồi viết bước chuẩn hóa thành `chapters: [{ index, title }]`.
> Nên lưu JSON gốc (đã giải mã) trong DB để đối chiếu khi cần.

### 4.5. Quy tắc gọi API gốc

| Vấn đề | Quy tắc đề xuất |
|---|---|
| Timeout | `split_chapter` ≥ 300s; `gen_chapter` 60s |
| Retry | Chỉ retry khi `999` hoặc lỗi kết nối, tối đa 2 lần, backoff tăng dần. **Không** retry khi timeout đọc (có thể đã tốn lượt sinh) |
| Đồng thời | Giới hạn số request song song tới API gốc (hàng đợi); giới hạn thật chưa rõ |
| Trùng lặp | Dùng `Idempotency-Key` (3.1) và khóa theo `novelId + index` (3.3) |
| Nhật ký | Ghi `jobId`, thời gian, mã lỗi; **không** ghi key. Cân nhắc không lưu log nội dung truyện |

---

## 6. Bảo mật & vận hành

- Bắt buộc **https** giữa app ↔ server; token cho từng người dùng/thiết bị; **giới hạn lượt** theo người dùng (chi phí và giới hạn của API gốc chưa rõ).
- Key giải mã chỉ ở server (secret manager), không trả về app, không ghi log.
- Server → API gốc vẫn là **http**, không mã hóa đường truyền; đặt server trong hạ tầng tin cậy, hạn chế dữ liệu cá nhân trong `topic`/`title`.
- Nên có kiểm duyệt nội dung đầu vào/đầu ra (đặc biệt thể loại "Story for kid").
- Theo dõi: tỷ lệ lỗi theo `code`, thời gian `split_chapter`, số lượt/ngày, chi phí nếu có.

---

## 8. Rủi ro cần lưu ý

Chuyển sang server trung gian **giấu được key và làm app ổn định hơn**, nhưng **không xóa** các rủi ro sau:

- API gốc là **backend của bên thứ ba** (`com.bf.aistory`); địa chỉ và key được lấy từ bản cài đặt ứng dụng, **chưa có thỏa thuận sử dụng**.
- Có thể vi phạm điều khoản/bản quyền, có thể bị chặn hoặc đổi bất kỳ lúc nào, và server của mình sẽ là nơi gọi hàng loạt thay cho từng thiết bị.
- Không có cam kết dịch vụ, không rõ chi phí/giới hạn.