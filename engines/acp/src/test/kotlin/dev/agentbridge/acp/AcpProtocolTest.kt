package dev.agentbridge.acp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AcpProtocolTest {
    @Test
    fun parsesV2SessionAndImageCapabilities() {
        val result = buildJsonObject {
            put("protocolVersion", 2)
            put("capabilities", buildJsonObject {
                put("session", buildJsonObject {
                    put("prompt", buildJsonObject { put("image", buildJsonObject {}) })
                })
            })
            put("info", buildJsonObject {
                put("name", "opencode")
                put("version", "1.18.26")
            })
        }
        val info = AcpProtocol.parseInitializeResult(result)
        assertEquals(2, info.protocolVersion)
        assertEquals("opencode", info.name)
        assertTrue(info.supportsSessions)
        assertTrue(info.supportsResume)
        assertTrue(info.supportsImages)
    }

    @Test
    fun parsesV1Capabilities() {
        val result = buildJsonObject {
            put("protocolVersion", 1)
            put("agentCapabilities", buildJsonObject {
                put("promptCapabilities", buildJsonObject { put("image", true) })
            })
        }
        val info = AcpProtocol.parseInitializeResult(result)
        assertTrue(info.supportsSessions)
        assertTrue(info.supportsImages)
    }

    @Test
    fun rejectsUnknownProtocolVersion() {
        assertFailsWith<IllegalArgumentException> {
            AcpProtocol.parseInitializeResult(buildJsonObject { put("protocolVersion", 99) })
        }
    }

    @Test
    fun buildsVersionSpecificTextPrompt() {
        val v1 = AcpProtocol.textPromptParams(1, "s1", "hello")
        val v2 = AcpProtocol.textPromptParams(2, "s2", "world")
        assertEquals("hello", v1.getValue("prompt").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("world", v2.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun buildsPermissionResponses() {
        assertEquals(
            "allow-1",
            AcpProtocol.permissionResponse("allow-1").getValue("outcome")
                .jsonObject.getValue("optionId").jsonPrimitive.content,
        )
        assertEquals(
            "cancelled",
            AcpProtocol.cancelledPermissionResponse().getValue("outcome")
                .jsonObject.getValue("outcome").jsonPrimitive.content,
        )
    }
}
