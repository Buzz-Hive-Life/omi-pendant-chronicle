package com.connor.pendant.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The unit arm of the ring port: a WRONG LENGTH is what the firmware answers with INVALID_COMMAND, so
 * the lengths — and the big-endian field order — are asserted here rather than discovered on a pendant.
 */
class RingCommandsTest {

    // The device reports codec byte 21 = OPUS_24K-class Opus; the ring read start is a 64-bit
    // BIG-ENDIAN sequence.
    private val seq = 0x0102030405060708L

    @Test
    fun ringRead_is_exactly_9_bytes_opcode_then_BE64() {
        val c = RingCommands.ringRead(seq)
        assertEquals("RING_READ is 9 bytes (firmware: len == 9 || 13)", 9, c.size)
        assertEquals(0x11, c[0].toInt() and 0xFF)
        // sys_get_be64(cmd->data + 1) — bytes 1..8 are the sequence, most-significant first
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            c.copyOfRange(1, 9)
        )
    }

    @Test
    fun ringRead_with_count_is_13_bytes() {
        val c = RingCommands.ringRead(seq, 36)
        assertEquals(13, c.size)
        assertEquals(0x11, c[0].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x24), c.copyOfRange(9, 13))
    }

    @Test
    fun ringAdvance_is_exactly_9_bytes_opcode_then_BE64() {
        val c = RingCommands.ringAdvance(seq)
        assertEquals("RING_ADVANCE is 9 bytes (firmware: len == 9)", 9, c.size)
        assertEquals(0x12, c[0].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            c.copyOfRange(1, 9)
        )
    }

    @Test
    fun custodyEnable_is_exactly_3_bytes() {
        val c = RingCommands.custodyEnable()
        assertEquals("CUSTODY_ENABLE is exactly 3 bytes (firmware: len != 3 ⇒ INVALID_COMMAND)", 3, c.size)
        assertEquals(0x14, c[0].toInt() and 0xFF)
        assertEquals(RingCommands.CUSTODY_VERSION, c[1].toInt() and 0xFF)
        assertEquals(0x0F, c[2].toInt() and 0xFF)
    }

    @Test
    fun ringAdvanceId_is_exactly_17_bytes() {
        val c = RingCommands.ringAdvanceId(0x1122334455667788L, seq)
        assertEquals(17, c.size)
        assertEquals(0x15, c[0].toInt() and 0xFF)
    }

    @Test
    fun ringInfo_is_one_byte_and_clear_and_stop_are_one_byte() {
        assertEquals(1, RingCommands.ringInfo().size)
        assertEquals(0x10, RingCommands.ringInfo()[0].toInt() and 0xFF)
        assertEquals(1, RingCommands.ringClear().size)
        assertEquals(1, RingCommands.stopSync().size)
        assertEquals(0x03, RingCommands.stopSync()[0].toInt() and 0xFF)
    }

    @Test
    fun there_is_no_epoch_prefix_byte0_IS_the_opcode() {
        // The two commands we send on every drain must lead with their opcode, not a length or an epoch.
        assertEquals(0x10, RingCommands.ringInfo()[0].toInt() and 0xFF)
        assertEquals(0x11, RingCommands.ringRead(seq)[0].toInt() and 0xFF)
        assertEquals(0x12, RingCommands.ringAdvance(seq)[0].toInt() and 0xFF)
        assertEquals(0x14, RingCommands.custodyEnable()[0].toInt() and 0xFF)
    }

    @Test
    fun sequence_survives_full_unsigned_range() {
        // A ring sequence is an unsigned 64-bit counter — the top bit must round-trip.
        val high = -1L // 0xFFFF_FFFF_FFFF_FFFF as unsigned
        assertArrayEquals(
            ByteArray(8) { 0xFF.toByte() },
            RingCommands.ringRead(high).copyOfRange(1, 9)
        )
    }
}
