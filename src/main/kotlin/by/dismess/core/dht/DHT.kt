package by.dismess.core.dht

import by.dismess.core.model.UserID
import java.net.InetSocketAddress

interface DHT {
    fun store(key: String, data: ByteArray)
    fun retrieve(key: String): ByteArray
    fun find(userId: UserID): InetSocketAddress
}
