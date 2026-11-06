package com.github.igrmk.smsq

import com.github.igrmk.smsq.entities.DeliveryResult
import com.github.igrmk.smsq.entities.SmsResponse
import com.github.igrmk.smsq.helpers.NopLogger
import com.github.igrmk.smsq.helpers.RestTalker
import org.junit.Test
import org.junit.Assert.*

class ParseUnitTest {
    private var rest = RestTalker(NopLogger(), "", null)

    @Test
    fun parsingCorrect() {
        val parsed = rest.readReply(SmsResponse.serializer(), """{ "result": "delivered" }""")
        assertEquals(DeliveryResult.Delivered, parsed.first)
        assertEquals(true, parsed.second)
    }

    @Test
    fun parsingUnknownEnum() {
        val parsed = rest.readReply(SmsResponse.serializer(), """{ "result": "absolutely_unknown" }""")
        assertEquals(DeliveryResult.Unknown, parsed.first)
        assertEquals(true, parsed.second)
    }

    @Test
    fun parsingNumberAsEnum() {
        val parsed = rest.readReply(SmsResponse.serializer(), """{ "result": 1 }""")
        assertEquals(null, parsed.first)
        assertEquals(false, parsed.second)
    }

    @Test
    fun parsingObjectAsEnum() {
        val parsed = rest.readReply(SmsResponse.serializer(), """{ "result": { result: "" } }""")
        assertEquals(null, parsed.first)
        assertEquals(false, parsed.second)
    }

    @Test
    fun parsingNetworkFailure() {
        val parsed = rest.readReply(SmsResponse.serializer(), null)
        assertNull(parsed.first)
        assertFalse(parsed.second)
    }
}
