package dev.ryuya.galaxybar

import java.util.Properties
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Protocol 1: a short-lived request in SystemUI's private, owner-only storage. */
@JvmRecord
data class ReloadTicket(val boot: Int, val user: Int, val created: Long, val nonce: String?) {
    fun accepts(
        currentBoot: Int,
        currentUser: Int,
        now: Long,
        interactive: Boolean,
        userUnlocked: Boolean,
    ): Boolean {
        return boot >= 0 &&
            boot == currentBoot &&
            user == 0 &&
            user == currentUser &&
            created >= 0 &&
            now >= created &&
            now - created <= ReloadTicket.REQUEST_LIFETIME_MS &&
            interactive &&
            userUnlocked
    }

    fun properties(): Properties {
        val result = Properties()
        result.setProperty("protocol", ReloadTicket.PROTOCOL.toString())
        result.setProperty("boot", boot.toString())
        result.setProperty("user", user.toString())
        result.setProperty("created", created.toString())
        result.setProperty("nonce", nonce)
        return result
    }

    companion object {
        const val PROTOCOL: Int = 1
        const val REQUEST_LIFETIME_MS: Long = 12000
        const val REQUEST: String = "galaxy-reload-request.properties"
        const val SUPPORT: String = "galaxy-reload-support.properties"
        const val RESULT: String = "galaxy-reload-result.properties"
        const val CANCEL: String = "galaxy-reload-cancel.properties"

        @JvmStatic
        fun parse(properties: Properties): ReloadTicket? {
            try {
                if (ReloadTicket.PROTOCOL.toString() != properties.getProperty("protocol"))
                    return null
                val nonce = properties.getProperty("nonce", "")
                if (!nonce.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}".toRegex()))
                    return null
                return ReloadTicket(
                    properties.getProperty("boot").toInt(),
                    properties.getProperty("user").toInt(),
                    properties.getProperty("created").toLong(),
                    nonce,
                )
            } catch (malformed: RuntimeException) {
                return null
            }
        }
    }

    override fun toString(): String =
        "ReloadTicket[boot=$boot, user=$user, created=$created, nonce=$nonce]"
}
