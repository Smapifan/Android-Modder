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
                "appName": "com.gram.mergedragons",
                "field": "CoinCount",
                "operation": "ADD",
                "amount": 1000
              }
            ]
        """.trimIndent()

        val parsed = CheatsConfigParser().parse(json)

        assertEquals(1, parsed.size)
        with(parsed.first()) {
            assertEquals("com.gram.mergedragons", appName)
            assertEquals("CoinCount", field)
            assertEquals(CheatOperation.ADD, operation)
            assertEquals(1000L, amount)
        }
    }

    @Test
    fun `parses SUBTRACT cheat entry`() {
        val json = """
            [
              {
                "appName": "com.gram.mergedragons",
                "field": "CoinCount",
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
                "appName": "net.stardewvalley",
                "field": "money",
                "operation": "SET",
                "amount": 50000
              }
            ]
        """.trimIndent()

        val parsed = CheatsConfigParser().parse(json)

        assertEquals(CheatOperation.SET, parsed.first().operation)
        assertEquals("money", parsed.first().field)
        assertEquals(50000L, parsed.first().amount)
    }

    @Test
    fun `parses multiple cheat entries`() {
        val json = """
            [
              { "appName": "com.gram.mergedragons",  "field": "CoinCount",  "operation": "ADD",      "amount": 1000 },
              { "appName": "com.gram.mergedragons",  "field": "GemCount",   "operation": "SET",      "amount": 999  },
              { "appName": "com.kiloo.subwaysurf",   "field": "coins",      "operation": "ADD",      "amount": 10000 },
              { "appName": "net.stardewvalley",      "field": "stamina",    "operation": "SET",      "amount": 270  }
            ]
        """.trimIndent()

        assertEquals(4, CheatsConfigParser().parse(json).size)
    }
}
