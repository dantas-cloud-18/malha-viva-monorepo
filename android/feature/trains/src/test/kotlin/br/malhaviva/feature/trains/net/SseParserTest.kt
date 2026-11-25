package br.malhaviva.feature.trains.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun parse(vararg chunks: String): List<SseEvent> {
        val parser = SseParser()
        return chunks.flatMap { parser.feed(it) }
    }

    @Test
    fun `complete event in one chunk`() {
        val events = parse("event: train\ndata: {\"id\":\"a\"}\n\n")
        assertEquals(listOf(SseEvent("train", "{\"id\":\"a\"}")), events)
    }

    @Test
    fun `event cut at every possible byte boundary gives the same result`() {
        val raw = "event: train\ndata: {\"id\":\"a\"}\n\nevent: train\ndata: {\"id\":\"b\"}\n\n"
        val expected = parse(raw)
        assertEquals(2, expected.size)
        for (cut in 1 until raw.length) {
            assertEquals("cut=$cut", expected, parse(raw.substring(0, cut), raw.substring(cut)))
        }
    }

    @Test
    fun `partial line is held until its terminator arrives`() {
        val parser = SseParser()
        assertTrue(parser.feed("event: tr").isEmpty())
        assertTrue(parser.feed("ain\ndata: x").isEmpty())
        assertTrue(parser.feed("\n").isEmpty())
        assertEquals(listOf(SseEvent("train", "x")), parser.feed("\n"))
    }

    @Test
    fun `heartbeat comments produce no event and do not break the next one`() {
        val events = parse(": heartbeat\n\n", ":heartbeat\n", "\n", "event: train\ndata: 1\n\n")
        assertEquals(listOf(SseEvent("train", "1")), events)
    }

    @Test
    fun `comment between fields of an event is ignored`() {
        assertEquals(listOf(SseEvent("train", "1")), parse("event: train\n: ping\ndata: 1\n\n"))
    }

    @Test
    fun `crlf and lone cr terminators, even split across chunks`() {
        assertEquals(listOf(SseEvent("train", "1")), parse("event: train\r\ndata: 1\r\n\r\n"))
        assertEquals(listOf(SseEvent("train", "1")), parse("event: train\rdata: 1\r\r"))
        assertEquals(listOf(SseEvent("train", "1")), parse("event: train\r", "\ndata: 1\r", "\n\r", "\n"))
    }

    @Test
    fun `multiple data lines are joined with newline`() {
        assertEquals(listOf(SseEvent("message", "a\nb")), parse("data: a\ndata: b\n\n"))
    }

    @Test
    fun `only one leading space is stripped and a missing space is fine`() {
        assertEquals(listOf(SseEvent("train", " x")), parse("event:train\ndata:  x\n\n"))
    }

    @Test
    fun `event without data is dropped and does not leak its type`() {
        assertEquals(listOf(SseEvent("message", "1")), parse("event: train\n\ndata: 1\n\n"))
    }

    @Test
    fun `unknown fields and retry are ignored`() {
        assertEquals(listOf(SseEvent("train", "1")), parse("id: 7\nretry: 1000\nfoo: bar\nevent: train\ndata: 1\n\n"))
    }

    @Test
    fun `leading BOM is skipped`() {
        assertEquals(listOf(SseEvent("train", "1")), parse("﻿event: train\ndata: 1\n\n"))
    }

    @Test
    fun `multibyte text survives chunking`() {
        val events = parse("event: train\ndata: Esta", "ção\n\n")
        assertEquals("Estação", events.single().data)
    }

    @Test
    fun `unfinished event at end of stream is not emitted`() {
        assertTrue(parse("event: train\ndata: 1\n").isEmpty())
    }
}
