package com.example.tvlistesi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistParserTest {

    @Test
    fun `hls master playlist tek kanal olarak okunur`() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1920x1080
            tv8_1080p.m3u8?&sid=abc&app=xyz&ce=3
            #EXT-X-STREAM-INF:BANDWIDTH=1050000,RESOLUTION=1280x720
            tv8_720p.m3u8?&sid=abc&app=xyz&ce=3
        """.trimIndent()
        val url = "https://tv8.daioncdn.net/tv8/tv8.m3u8?app=xyz&ce=3"

        val channels = PlaylistParser.parse(master, url)

        assertEquals(1, channels.size)
        assertEquals(url, channels[0].url)
        assertEquals("tv8", channels[0].name)
    }

    @Test
    fun `dogrudan HLS linki tek kanal olarak acilir`() {
        val channel = PlaylistParser.directChannel(
            "http://57.129.0.61:1990/stream/117/index.m3u8"
        )

        assertEquals("http://57.129.0.61:1990/stream/117/index.m3u8", channel?.url)
        assertEquals("Kanal 1", channel?.name)
        assertEquals("HLS", channel?.group)
    }

    @Test
    fun `uzantisiz HLS adresi tek kanal olarak taninir`() {
        val channel = PlaylistParser.directChannel(
            "http://dygvideo.dygdigital.com/live/hls/kralpop"
        )

        assertEquals("HLS", channel?.group)
        assertEquals("http://dygvideo.dygdigital.com/live/hls/kralpop", channel?.url)
    }

    @Test
    fun `dizi ve film adresleri tek kanal olarak taninir`() {
        val dizi = PlaylistParser.directChannel("https://ornek.com/stream/117/dizi")
        val film = PlaylistParser.directChannel("https://ornek.com/stream/118/film?token=abc")

        assertEquals("https://ornek.com/stream/117/dizi", dizi?.url)
        assertEquals("Tek kanal", dizi?.group)
        assertEquals("https://ornek.com/stream/118/film?token=abc", film?.url)
    }

    @Test
    fun `goreli m3u8 adresi taban url ile cozulur`() {
        val body = """
            #EXTM3U
            #EXTINF:-1,TRT 1
            trt1/index.m3u8
        """.trimIndent()

        val channels = PlaylistParser.parse(body, "https://ornek.com/liste/canli.m3u")

        assertEquals(1, channels.size)
        assertEquals("https://ornek.com/liste/trt1/index.m3u8", channels[0].url)
        assertEquals("TRT 1", channels[0].name)
    }
}
