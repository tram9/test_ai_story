package com.example.test.data

const val BASE_STORY = "http://storygenapi.breakerai.com"
const val BASE_CHAT = "http://gptchatapi.breakerai.com"

const val KEY_STORY_V2 = "K1k2hH2uZF96ik9M"   // generate_story_v2
const val KEY_CHAT = "K1k2hH2uZF96ik5n"       // api/ask (chatbot)

const val TIMEOUT_DEFAULT_MS = 60_000
const val TIMEOUT_CHAPTER_MS = 90_000         // sinh 1 chương qua proxy, API gốc phía sau timeout 60s

// Server trung gian của người dùng (server_api.md) — truyện nhiều chương đi qua đây,
// không gọi thẳng novelv2.breakerai.com nữa. Điền token thật vào đây nếu server yêu cầu
// `Authorization: Bearer <token>`; để trống thì không gửi header này.
const val PROXY_BASE_URL = "https://sp155-ai-story.pandaglobal.top"
const val PROXY_TOKEN = ""

const val POLL_INTERVAL_MS = 4_000L           // tần suất hỏi GET /v1/jobs/{id}, theo gợi ý 3–5s của server_api.md
const val JOB_TIMEOUT_MS = 12 * 60 * 1000L    // client tự bỏ cuộc sớm hơn mốc 10 phút server tự hủy job
