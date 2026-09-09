package com.kwebmn.dvripcam.dvrip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 20-байтный заголовок DVRIP (little-endian):
 * [0]=0xFF [1]=0x00 [2..3]=0 [4..7]=SessionID [8..11]=Sequence
 * [12]=0 [13]=0 [14..15]=MsgID [16..19]=BodyLen
 */
object DvripHeader {
    const val SIZE = 20

    fun build(sessionId: Int, sequence: Int, msgId: Int, bodyLen: Int): ByteArray {
        val bb = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0xFF.toByte())
        bb.put(0)
        bb.putShort(0)
        bb.putInt(sessionId)
        bb.putInt(sequence)
        bb.put(0)
        bb.put(0)
        bb.putShort((msgId and 0xFFFF).toShort())
        bb.putInt(bodyLen)
        return bb.array()
    }

    data class Parsed(val msgId: Int, val bodyLen: Int)

    fun parse(header: ByteArray): Parsed {
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val msgId = bb.getShort(14).toInt() and 0xFFFF
        val bodyLen = bb.getInt(16)
        return Parsed(msgId, bodyLen)
    }
}
