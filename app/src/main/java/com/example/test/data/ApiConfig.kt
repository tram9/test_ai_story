package com.example.test.data

const val BASE_STORY = "http://storygenapi.breakerai.com"
const val BASE_NOVEL = "http://novelv2.breakerai.com"
const val BASE_CHAT = "http://gptchatapi.breakerai.com"

const val KEY_STORY_V2 = "K1k2hH2uZF96ik9M"   // generate_story_v2
const val KEY_NOVEL = "YxJstNNI1kuCFP0v"      // split_chapter, gen_chapter
const val KEY_CHAT = "K1k2hH2uZF96ik5n"       // api/ask (chatbot)

const val TIMEOUT_DEFAULT_MS = 60_000
const val TIMEOUT_SPLIT_MS = 300_000          // split_chapter sinh lâu, cần >= 300s
