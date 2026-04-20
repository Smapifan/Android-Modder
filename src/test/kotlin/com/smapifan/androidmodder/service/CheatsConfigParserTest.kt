package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals

class CheatsConfigParserTest {
    @Test
    fun `parses known cheat entries`() {
        val json = """
            [
              {
                "appName": "MergeDragons",
                "saveFileRelativePath": "files/savegame.dat",
                "saveAddress": "0x0010FFAA",
                "memoryAddress": "0x1000ABCD"
              }
            ]
        """.trimIndent()

        val parsed = CheatsConfigParser().parse(json)

        assertEquals(1, parsed.size)
        assertEquals("MergeDragons", parsed.first().appName)
        assertEquals("files/savegame.dat", parsed.first().saveFileRelativePath)
    }
}
