package com.example.tvlistesi.data

import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramDiscoveryTest {

    @Test
    fun `type Film sorgulu PHP adresi kesfedilir`() {
        val found = linkedSetOf<String>()

        TelegramDiscovery.collectUrls(
            "http://129.151.197.83/YouTube.php?ID=@Sinemania&type=Film",
            found
        )

        assertTrue(
            "Adres bulunanlar arasında yok: $found",
            found.any {
                it.equals(
                    "http://129.151.197.83/YouTube.php?ID=@Sinemania&type=Film",
                    ignoreCase = true
                )
            }
        )
    }

    @Test
    fun `type Dizi sorgulu adres kesfedilir`() {
        val found = linkedSetOf<String>()

        TelegramDiscovery.collectUrls(
            "https://ornek.com/YouTube.php?ID=@Diziler&type=Dizi",
            found
        )

        assertTrue(found.any { it.contains("type=Dizi", ignoreCase = true) })
    }

    @Test
    fun `m3u dosya eki indirme adresi olusturulur`() {
        val url = TelegramDiscovery.m3uDocumentUrl("TvListesi", "157")
        assertTrue(url.endsWith("/media/TvListesi/157"))
    }
}
