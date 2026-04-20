package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatOperation
import kotlin.test.Test
import kotlin.test.assertEquals

class CheatsConfigParserTest {
    @Test
    fun `parses ADD cheat entry`() {
        val json = """
            [
              {
                "appName": "MergeDragons",
                "field": "coins",
                "operation": "ADD",
                "amount": 1000
              }
            ]
        """.trimIndent()

        val parsed = CheatsConfigParser().parse(json)

        assertEquals(1, parsed.size)
        with(parsed.first()) {
            assertEquals("MergeDragons", appName)
            assertEquals("coins", field)
            assertEquals(CheatOperation.ADD, operation)
            assertEquals(1000L, amount)
        }
    }

    @Test
    fun `parses SUBTRACT cheat entry`() {
        val json = """
            [
              {
                "appName": "MergeDragons",
                "field": "coins",
                "operation": "SUBTRACT",
                "amount": 500
              }
            ]
        """.trimIndent()

        val parsed = CheatsConfigParser().parse(json)

        assertEquals(CheatOperation.SUBTRACT, parsed.first().operation)
        assertEquals(500L, parsed.first().amount)
    }

    @Test
    fun `parses SET cheat entry`() {
        val json = """
            [
              {
                "appName": "StardewValley",
                "field": "money",
                "operation": "SET",
                "amount": 9999
              }
            ]
        """.trimIndent()

        val parsed = CheatsConfigParser().parse(json)

        assertEquals(CheatOperation.SET, parsed.first().operation)
        assertEquals("money", parsed.first().field)
        assertEquals(9999L, parsed.first().amount)
    }

    @Test
    fun `parses multiple cheat entries`() {
        val json = """
            [
              { "appName": "Game1", "field": "coins", "operation": "ADD",      "amount": 1000 },
              { "appName": "Game1", "field": "coins", "operation": "SUBTRACT", "amount": 100  },
              { "appName": "Game2", "field": "lives", "operation": "SET",      "amount": 99   }
            ]
        """.trimIndent()

        assertEquals(3, CheatsConfigParser().parse(json).size)
    }
}
