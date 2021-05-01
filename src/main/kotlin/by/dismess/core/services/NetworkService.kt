package by.dismess.core.services

import by.dismess.core.klaxon
import by.dismess.core.network.MessageType
import by.dismess.core.network.NetworkMessage
import by.dismess.core.outer.NetworkInterface
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * NetworkService is an implementation of L7 Dismess protocol
 * There are four types of messages - GET requests, POST requests, responses and approves
 * Each message has tag to determine message handler
 * GET request expects response (data)
 * POST request expects approve (just signal that request has been received)
 * Request has verificationTag - tag of response or approve
 */
class NetworkService(
    private val networkInterface: NetworkInterface
) {
    /**
     * Tag to list of registered handlers
     * @note You can use several handlers with one tag for debugging
     */
    private val requestHandlers = mutableMapOf<String, MutableList<MessageHandler>>()
    private val responseHandlers = mutableMapOf<String, MutableList<MessageHandler>>()
    init {
        networkInterface.setMessageReceiver { sender, data ->
            val message = klaxon.parse<NetworkMessage>(String(data)) ?: return@setMessageReceiver
            if (message.type == MessageType.POST) {
                GlobalScope.launch { // send approve
                    message.verificationTag?.run {
                        sendMessage(sender, NetworkMessage(MessageType.APPROVE, this))
                    }
                }
            }
            message.sender = sender
            val handlers = if (message.isRequest()) requestHandlers else responseHandlers
            for (handler in handlers[message.tag] ?: emptyList()) {
                handler(message)
            }
        }
    }
    /**
     * Each part of Core, that logically has its own handler, must have its own tag
     * @example DHT has tag "DHT"
     */
    fun registerHandler(tag: String, handler: MessageHandler): MessageHandler {
        requestHandlers.getOrPut(tag) { mutableListOf() }.add(handler)
        return handler
    }
    fun forgetHandler(tag: String, handler: MessageHandler) {
        requestHandlers[tag]?.remove(handler)
    }
    suspend fun sendPost(address: InetSocketAddress, tag: String, data: Any, timeout: Long = 1000): Boolean =
        sendPost(address, tag, klaxon.toJsonString(data), timeout)

    suspend fun sendPost(address: InetSocketAddress, tag: String, data: String, timeout: Long = 1000): Boolean =
        sendMessage(address, NetworkMessage(MessageType.POST, tag, data), timeout) != null

    suspend fun sendGet(address: InetSocketAddress, tag: String, data: Any, timeout: Long = 1000): String? =
        sendGet(address, tag, klaxon.toJsonString(data), timeout)

    suspend fun sendGet(address: InetSocketAddress, tag: String, data: String, timeout: Long = 1000): String? =
        sendMessage(address, NetworkMessage(MessageType.GET, tag, data), timeout)?.data

    /**
     * Returns response if request delivered successfully, null if not
     */

    suspend fun sendMessage(address: InetSocketAddress, message: NetworkMessage, timeout: Long = 1000): NetworkMessage? {
        message.verificationTag = randomTag()
        networkInterface.sendRawMessage(address, klaxon.toJsonString(message).toByteArray())
        return waitForAResponse(message.verificationTag!!, timeout)
    }

    /**
     * Waits for a message, but
     * returns null if timeout
     */
    private suspend fun waitForAResponse(tag: String, timeout: Long = 1000L): NetworkMessage? {
        var handler: MessageHandler? = null
        val result = withTimeoutOrNull<NetworkMessage>(timeout) {
            suspendCoroutine { continuation ->
                handler = { message: NetworkMessage ->
                    continuation.resume(message)
                }.also { responseHandlers.getOrPut(tag) { mutableListOf() }.add(it) }
            }
        }
        responseHandlers[tag]?.remove(handler)
        return result
    }

    companion object {
        fun randomTag() = UUID.randomUUID().toString()
    }
}

typealias MessageHandler = (message: NetworkMessage) -> Unit
