package com.llamaaistudio.app.data.cloud

import com.llamaaistudio.app.engine.ChatTurn
import com.llamaaistudio.app.engine.GenerationParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeRepositoryTest {

    // Shapes copied from https://opencode.ai/zen/v1/models.
    private val catalogue = """
        {"object":"list","data":[
          {"id":"big-pickle","name":"Big Pickle","type":"chat","description":"Reasoning model",
           "modalities":{"input":["text"],"output":["text"]},"limit":{"context":200000},
           "cost":{"text":{"input":{"price":0,"cache":{"read":0,"write":0}},"output":{"price":0}}},
           "api":{"chat-completions":{"base_url":"https://opencode.ai/inference/openai/v1"}}},
          {"id":"nemotron-vl-free","name":"Nemotron VL Free","type":"chat",
           "modalities":{"input":["text","image/jpeg","image/png"],"output":["text"]},"limit":{"context":131072},
           "cost":{"text":{"input":{"price":0},"output":{"price":0}}},
           "api":{"chat-completions":{"base_url":"https://opencode.ai/inference/openai/v1"}}},
          {"id":"paid-vision","name":"Paid Vision","type":"chat",
           "modalities":{"input":["text","image"],"output":["text"]},
           "cost":{"text":{"input":{"price":3},"output":{"price":15}}},
           "api":{"chat-completions":{"base_url":"https://opencode.ai/zen/v1"}}},
          {"id":"responses-only-free","name":"Responses Only","type":"chat",
           "modalities":{"input":["text"],"output":["text"]},
           "cost":{"text":{"input":{"price":0},"output":{"price":0}}},
           "api":{"responses":{"base_url":"https://opencode.ai/zen/v1"}}},
          {"id":"no-cost","name":"No Cost Info","type":"chat","modalities":{"input":["text"],"output":["text"]}}
        ]}
    """.trimIndent()

    @Test
    fun parsesFreeAndVisionFlags() {
        val models = OpenCodeRepository.parseModels(catalogue).associateBy { it.id }
        assertTrue(models.getValue("big-pickle").free)
        assertFalse(models.getValue("big-pickle").acceptsImages)
        assertEquals(200000, models.getValue("big-pickle").contextLength)
        assertTrue(models.getValue("nemotron-vl-free").free)
        assertTrue(models.getValue("nemotron-vl-free").acceptsImages)
        assertFalse(models.getValue("paid-vision").free)
        assertTrue(models.getValue("paid-vision").acceptsImages)
        // Free but no chat-completions endpoint: not usable by this app.
        assertFalse(models.getValue("responses-only-free").usable)
        // Missing prices are never treated as free.
        assertFalse(models.getValue("no-cost").free)
    }

    @Test
    fun chatBodyAttachesImageToLastUserTurnOnly() {
        val body = OpenCodeRepository.buildChatBody(
            "nemotron-vl-free",
            listOf(ChatTurn("system", "sys"), ChatTurn("user", "first"), ChatTurn("assistant", "a"), ChatTurn("user", "edit this")),
            GenerationParams(maxTokens = 123, temperature = 0.5f),
            imageJpeg = byteArrayOf(1, 2, 3),
        )
        assertEquals("nemotron-vl-free", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(123, body.getInt("max_tokens"))
        val msgs = body.getJSONArray("messages")
        assertEquals("first", msgs.getJSONObject(1).getString("content"))
        val parts = msgs.getJSONObject(3).getJSONArray("content")
        assertEquals("edit this", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun freeDetectionWalksNestedPrices() {
        assertTrue(OpenCodeRepository.isFree(JSONObject("""{"text":{"input":{"price":0},"output":{"price":0}},"audio":{"input":{"price":0}}}""")))
        assertFalse(OpenCodeRepository.isFree(JSONObject("""{"text":{"input":{"price":0},"output":{"price":0.4}}}""")))
        assertFalse(OpenCodeRepository.isFree(JSONObject("{}")))
        assertFalse(OpenCodeRepository.isFree(null))
    }

    @Test
    fun parsesStreamingContentSkipsReasoningAndReadsUsage() {
        val sse = listOf(
            ": keep-alive",
            "",
            """data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"thinking..."}}]}""",
            """data: {"choices":[{"index":0,"delta":{"content":"Hel"}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{"content":"lo!"}}]}""",
            """data: {"choices":[{"index":0,"delta":{"content":null},"finish_reason":"length"}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}""",
            "data: [DONE]",
        )
        val out = StringBuilder()
        val r = OpenCodeRepository.parseChatStream(sse.iterator()) { out.append(it) }
        assertEquals("Hello!", out.toString())
        assertNull(r.error)
        assertEquals(12, r.promptTokens)
        assertEquals(2, r.completionTokens)
        assertEquals("length", r.finish)
    }

    @Test
    fun streamErrorsAndNonStreamedRepliesAreHandled() {
        val err = OpenCodeRepository.parseChatStream(
            listOf("""data: {"choices":[{"delta":{"content":"a"}}]}""", """data: {"error":{"message":"rate limited"}}""").iterator(),
        ) {}
        assertEquals("rate limited", err.error)

        val jsonError = OpenCodeRepository.parseChatStream(
            listOf("""{"type":"error","error":{"type":"FreeUsageLimitError","message":"Free usage limit reached."}}""").iterator(),
        ) {}
        assertEquals("Free usage limit reached.", jsonError.error)

        val out = StringBuilder()
        val plain = OpenCodeRepository.parseChatStream(
            listOf("""{"choices":[{"message":{"role":"assistant","content":"Hi there"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""").iterator(),
        ) { out.append(it) }
        assertNull(plain.error)
        assertEquals("Hi there", out.toString())
        assertEquals(2, plain.completionTokens)

        assertEquals("OpenCode returned no reply", OpenCodeRepository.parseChatStream(emptyList<String>().iterator()) {}.error)
    }

    @Test
    fun parsesOpenRouterFreeTextModels() {
        // Shape copied from https://openrouter.ai/api/v1/models.
        val json = """{"data":[
          {"id":"google/gemma-4-31b-it:free","name":"Gemma 4 31B (free)","context_length":131072,
           "pricing":{"prompt":"0","completion":"0"},
           "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
          {"id":"openai/gpt-5","name":"GPT-5","context_length":400000,
           "pricing":{"prompt":"0.00000125","completion":"0.00001"},
           "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
          {"id":"google/lyria-3-pro-preview","name":"Lyria","pricing":{"prompt":"0","completion":"0"},
           "architecture":{"input_modalities":["text"],"output_modalities":["text","audio"]}}
        ]}"""
        val models = OpenCodeRepository.parseOpenRouterModels(json).associateBy { it.id }
        assertEquals(2, models.size) // music model dropped
        val gemma = models.getValue("google/gemma-4-31b-it:free")
        assertTrue(gemma.free && gemma.acceptsImages && gemma.usable)
        assertEquals(131072, gemma.contextLength)
        assertFalse(models.getValue("openai/gpt-5").free)
    }

    @Test
    fun reasoningOnlyStreamIsAnError() {
        val lines = listOf(
            """data: {"choices":[{"delta":{"content":"","reasoning":"Let me think..."}}]}""",
            """data: {"choices":[{"delta":{"content":null},"finish_reason":"length"}]}""",
            "data: [DONE]",
        )
        val r = OpenCodeRepository.parseChatStream(lines.iterator()) { }
        assertTrue(r.error!!.contains("thinking"))
    }
}
