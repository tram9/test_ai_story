# Tổng hợp API & code test — AI Story Generator (12.4.1)


## 1. Tổng quan

App test gồm 2 tab:

| Tab | Endpoint | Việc làm |
|---|---|---|
| **Truyện ngắn** | `generate_story_v2` | 1 request → truyện + ảnh bìa |
| **Nhiều chương** | `split_chapter` → `gen_chapter` | tạo dàn ý + bìa, rồi sinh nội dung từng chương |

Dưới mỗi kết quả có nút **"Dịch sang…"** (chọn 1 trong 12 ngôn ngữ) và **"Xem bản gốc"**.

---

## 2. Server, key, timeout

| Endpoint | Base URL | Key AES | Timeout |
|---|---|---|---|
| `api/generate_story_v2` | `http://storygenapi.breakerai.com` | `K1k2hH2uZF96ik9M` | 60s |
| `api/split_chapter` | `http://novelv2.breakerai.com` | `YxJstNNI1kuCFP0v` | **300s** |
| `api/gen_chapter` | `http://novelv2.breakerai.com` | `YxJstNNI1kuCFP0v` | 60s |
| `api/ask` (chatbot) | `http://gptchatapi.breakerai.com` | key riêng | *(không dùng)* |

- Tất cả là **http** (cleartext) → Manifest cần `INTERNET` + `android:usesCleartextTraffic="true"` (đã có).
- `split_chapter` sinh lâu → timeout < 300s sẽ bị `SocketTimeoutException` dù server vẫn chạy.
- Dùng nhầm key (vd key cũ `K1k2…` cho `split_chapter`) → lỗi "Malformed UTF-8", không phải lỗi code.

---

## 3. Request

Tất cả: `POST`, `Content-Type: application/x-www-form-urlencoded` (không phải JSON).

### 3a. Truyện ngắn — `generate_story_v2` (8 field)

| Field | Giá trị |
|---|---|
| `story_type` | `short` \| `long` |
| `character_desc` | text |
| `character_name` | text |
| `story_desc` | text |
| `cover_image_style` | 3D Character, Anime Portrait, Anime World, Character, Comics Portrait, Cyberpunk Portrait, Cyberpunk World, Fantasy Portrait, Fantasy World, Fantasy, Pixel Art, Pop Art, Renaissance, Steampunk, Street Art, Surreal Portrait, Watercolor |
| `story_genre` | Adventure, Story for kid, Romance, Fantasy, Sci-fi, Mythology |
| `ending_mode` | Happy Ending, Sad Ending, Open Ending |
| `story_style` | **mới ở 12.4.1** — vd `First person`, `3rd person` (danh sách đầy đủ chưa rõ → app dùng ô nhập tự do, mặc định `3rd person`) |

### 3b. Tạo truyện nhiều chương — `split_chapter` (12 field)

UI chỉ hiện **4 ô**; 8 field còn lại luôn được gửi với giá trị ẩn:

| Field | Nguồn | Giá trị |
|---|---|---|
| `title` | người dùng nhập | |
| `num_chapter` | người dùng nhập | 1–15 (app tự ép về khoảng này) |
| `topic` | người dùng nhập | *(app có thể gắn thêm chỉ dẫn ngôn ngữ, xem mục 7)* |
| `genre` | nhập tự do + chip gợi ý | Fantasy, Romance, Sci-fi, Adventure, Horror, Mystery, Story for kid, Mythology |
| `writing_style`, `characters`, `world`, `plot`, `prompt`, `negative_prompt` | ẩn | `""` |
| `width` | ẩn | `512` |
| `height` | ẩn | `768` |

### 3c. Sinh 1 chương — `gen_chapter`

| Field | Giá trị |
|---|---|
| `user_id` | `userId` nhận từ response của `split_chapter` |
| `title` | tiêu đề chương lấy từ dàn ý |

Luồng đúng: `split_chapter` trước → lấy `userId` + danh sách chương → mỗi chương gọi `gen_chapter`.

---

## 4. Giải mã response

Giống nhau cho mọi endpoint, chỉ khác **key** (mục 2):

1. Body là **chuỗi JSON có ngoặc kép bao ngoài** → bóc `"` (`stripJsonString`).
2. 16 ký tự đầu = **IV** (UTF-8).
3. Phần còn lại = **Base64** ciphertext.
4. `AES/CBC/NoPadding`, key UTF-8 16 byte.
5. Cắt các ký tự đệm cuối (`\u0000`, khoảng trắng, xuống dòng) rồi parse JSON.

Khi HTTP 500, server vẫn trả body → app đọc cả `errorStream`.

---

## 5. Response

Tên khóa lỗi **không đồng nhất** giữa các endpoint → app đọc cả hai kiểu (`readErrorCode` / `readErrorMsg`):

| Endpoint | Mã lỗi | Thông điệp |
|---|---|---|
| `generate_story_v2` | `error_code` | `error_message` |
| `split_chapter`, `gen_chapter` | `errorCode` | `errorMessage` |

`error_code == 0` là thành công. `999` = lỗi tạm thời phía server, thử lại sau.

**generate_story_v2**
```json
{ "error_code": 0, "error_message": "",
  "data": { "result": "…truyện…", "cover_image": "/static/….png" } }
```
Ảnh bìa: tải từ `BASE_STORY + cover_image`.

**split_chapter**
```json
{ "errorCode": 0, "errorMessage": "",
  "data": { …dàn ý các chương… },
  "userId": "…", "url": "/static/….png" }
```
Ảnh bìa: `BASE_NOVEL + url`. **Cấu trúc `data` chưa được tài liệu mô tả** (xem mục 9).

**gen_chapter**
```json
{ "errorCode": 0, "errorMessage": "", "data": { "content": "…nội dung chương…" } }
```

---

## 6. Cấu trúc code

```
app/src/main/
├── AndroidManifest.xml          INTERNET + usesCleartextTraffic
└── java/com/example/test/
    ├── MainActivity.kt          Scaffold + HomeScreen (edge-to-edge, TestTheme)
    ├── data/
    │   ├── ApiConfig.kt         BASE_STORY/BASE_NOVEL, KEY_STORY_V2/KEY_NOVEL, timeout
    │   ├── HttpUtil.kt          postForm, postEncrypted, readErrorCode/Msg, downloadImage
    │   ├── StoryCrypto.kt       stripJsonString, decrypt(cipherText, key)
    │   ├── StoryApi.kt          generateStory()
    │   ├── NovelApi.kt          buildNovelFields, splitChapter, genChapter, parseChapterTitles
    │   ├── StoryModels.kt       danh sách giá trị hợp lệ, StoryResult, NovelOutline, ChapterResult
    │   ├── OutputLanguage.kt    outputLanguageName, withLanguageHint
    │   ├── StoryTranslate.kt    translateTo() — dịch bằng Google Translate online
    │   └── TranslateLanguages.kt  danh sách 12 ngôn ngữ dịch
    └── ui/
        ├── HomeScreen.kt        2 tab
        ├── StoryGenScreen.kt    màn truyện ngắn
        ├── NovelScreen.kt       màn nhiều chương (4 ô + chip genre)
        └── Components.kt        DropdownField, TranslatableText
```

Chỉ dùng `HttpURLConnection`, `javax.crypto`, `org.json` — **không** cần Retrofit/Coil/Gson.
Không có dependency dịch nào (ML Kit đã gỡ).

---

## 7. Ngôn ngữ đầu vào / đầu ra

- **Đầu vào tiếng Việt: server hiểu được** (bạn xác nhận khi test).
- **Kết quả nhận được khi test đều là tiếng Anh** — chưa kiểm chứng có hệ thống; có thể server không đổi theo ngôn ngữ nhập, hoặc chỉ dẫn ngôn ngữ chưa được gửi (app chỉ gắn khi máy không để tiếng Anh).
- `withLanguageHint()` gắn câu `(Write the entire story in <ngôn ngữ>.)` vào `story_desc` (truyện ngắn)
  và `topic` (nhiều chương), ngôn ngữ lấy từ `Locale.getDefault()`; máy để tiếng Anh thì không gắn.
  **Chưa xác nhận server có làm theo** — nếu kết quả vẫn tiếng Anh thì coi như không hiệu quả.
  Có thể thử: viết chỉ dẫn bằng chính ngôn ngữ đích, đặt ở đầu thay vì cuối, hoặc đưa vào `writing_style`.

---

## 8. Tính năng dịch kết quả

Nút **"Dịch sang…"** dưới mỗi kết quả (truyện ngắn và từng chương) → menu 12 ngôn ngữ:

Vietnamese, Indonesian, Spanish, Urdu, Tagalog, Portuguese, Hindi, Korean, Japanese, German, French, Italian.

- Mã theo app giữ dạng Android (`in` = Indonesian); khi gọi dịch đổi sang mã Google (`id`).
- Cờ dùng emoji (chưa dùng `R.drawable.flag_*`).
- Bản dịch từng ngôn ngữ được **cache** trong màn hình; nút **"Xem bản gốc"** quay về tiếng Anh.
- Văn bản dài được chia khối ≤ 3000 ký tự, dịch lần lượt.

### Endpoint dịch
`POST https://translate.googleapis.com/translate_a/single` với `client=gtx&sl=auto&tl=<mã>&dt=t&q=<text>`.
Response là mảng lồng nhau, nối `[0][i][0]` để ra bản dịch.

- **Không chính thức**, không key, không tải model, không thêm thư viện; Google có thể chặn/ngừng bất kỳ lúc nào.
- Bắt buộc gửi **User-Agent giống trình duyệt** (app đã đặt) — nếu không dễ bị `429` hoặc trang "Sorry…".
- Đã từng gặp `HTTP 429` trên mạng của bạn; sau đó gọi lại từ cùng máy thì hoạt động với User-Agent trình duyệt.
  Lỗi hiện dưới nút bằng chữ đỏ, truyện gốc vẫn giữ.

### Các phương án dịch khác đã cân nhắc
| Cách | Ưu | Nhược |
|---|---|---|
| Google gtx online *(đang dùng)* | nhẹ nhất, không tải gì | không chính thức, có thể bị chặn |
| ML Kit Translate | offline, ổn định, không key | tải ~30MB model (theo ngôn ngữ, tải 1 lần) |
| Google Cloud Translation | chính thức, ổn định | cần API key + tài khoản Cloud |
| Bảo AI viết thẳng ngôn ngữ đích | không dịch, không tốn thêm gì | chưa xác nhận server làm theo |

> `strings.xml` theo ngôn ngữ (như dự án sp149) chỉ dịch được **chữ cố định của giao diện**,
> không dịch được nội dung truyện sinh lúc chạy.

---

## 9. Chưa được kiểm chứng

Mọi thứ dưới đây chỉ dựa vào tài liệu bạn đưa; **chưa gọi server thật từ code này**:

1. Cấu trúc `data` của `split_chapter` → `parseChapterTitles()` đọc "mềm" (mảng chuỗi / mảng object có
   `title`/`chapter_title`/`name`/`chapter` / object chứa mảng ở `chapters`/`outline`/`chapter_list`/`list`).
   Không nhận ra thì app hiện **JSON gốc** để đối chiếu và chỉnh lại.
2. `genre` tự nhập có được server nhận không (tài liệu: "nhiều khả năng", chưa xác nhận). Gặp `999` với giá trị lạ → dùng chip chuẩn.
3. Server có làm theo chỉ dẫn "viết bằng ngôn ngữ X" không (mục 7), và `gen_chapter` có theo ngôn ngữ đặt lúc `split_chapter` không.
4. Danh sách đầy đủ của `story_style` và `writing_style`.
5. Build đã `compileDebugKotlin` thành công, nhưng **chưa chạy trên máy/emulator**.

---

## 10. Test nhanh bằng Postman

- Body **x-www-form-urlencoded** (đừng tự set `Content-Type: application/json`).
- Tăng Request timeout cho `split_chapter` (≥ 300s).
- Script giải mã (Tests / After response), đổi key theo endpoint:

```javascript
const body = JSON.parse(pm.response.text());               // bóc dấu " bao ngoài
const key = CryptoJS.enc.Utf8.parse("YxJstNNI1kuCFP0v");   // K1k2hH2uZF96ik9M cho generate_story_v2
const iv  = CryptoJS.enc.Utf8.parse(body.substring(0, 16));
const cp  = CryptoJS.lib.CipherParams.create({ ciphertext: CryptoJS.enc.Base64.parse(body.substring(16)) });
const dec = CryptoJS.AES.decrypt(cp, key, { iv, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.NoPadding });
console.log("DECRYPTED:", dec.toString(CryptoJS.enc.Utf8).replace(/\0+$/, "").trim());
```
