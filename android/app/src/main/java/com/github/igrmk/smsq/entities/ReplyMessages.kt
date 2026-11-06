package com.github.igrmk.smsq.entities

import kotlinx.serialization.Serializable

@Serializable
class PendingReply {
    var id: Long = 0
    var recipient: String = ""
    var text: String = ""
}

@Serializable
class ReplyPollRequest {
    var key: String = ""
}

@Serializable
class ReplyAckRequest {
    var key: String = ""
    var id: Long = 0
    var success: Boolean = false
}

@Serializable
class ReplyPollResponse : BasicResponse<List<PendingReply>> {
    override var error: String? = null
    override var result: List<PendingReply>? = null
}

@Serializable
class ReplyAckResponse : BasicResponse<String> {
    override var error: String? = null
    override var result: String? = null
}
