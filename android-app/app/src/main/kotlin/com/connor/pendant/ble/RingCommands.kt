package com.connor.pendant.ble

/**
 * THE RING COMMAND ENCODER — the pendant's stored-audio ring protocol.
 *
 * Wire rules, from the vendor firmware itself
 * (`omi/firmware/omi/src/lib/core/storage.c`, and `custody.h`, read 2026-11-14):
 *
 *   · A command frame is just `[opcode][args…]`.  **THERE IS NO EPOCH PREFIX** — `bytes[0]` IS the
 *     opcode.  An earlier attempt prepended a 4-byte epoch and the firmware answered ACK status 6
 *     (`INVALID_COMMAND`), correctly: `parse_storage_command()` switches on `bytes[0]`.
 *   · Every multi-byte field is **big-endian** (`sys_get_be64` / `sys_get_be32`).
 *   · Lengths are ENFORCED.  `parse_storage_command()` rejects the wrong length with `INVALID_COMMAND`:
 *         RING_INFO / CLEAR   len ≥ 1
 *         RING_READ           len == 9  or  13
 *         RING_ADVANCE        len == 9
 *         RING_ADVANCE_ID     len == 17
 *         CUSTODY_ENABLE      len == 3   (and `data[1]` must equal OMI_CUSTODY_VERSION)
 *
 * These functions are deliberately pure (no Android types) so their exact byte lengths can be asserted
 * without a phone, a radio or a pendant — a wrong length is precisely what produces INVALID_COMMAND.
 */
object RingCommands {

    // ── opcodes: commands (written to the control characteristic 30295781) ────────────────────────
    const val CMD_STOP_SYNC = 0x03
    const val CMD_RING_INFO = 0x10
    const val CMD_RING_READ = 0x11
    const val CMD_RING_ADVANCE = 0x12
    const val CMD_RING_CLEAR = 0x13
    const val CMD_CUSTODY_ENABLE = 0x14
    const val CMD_RING_ADVANCE_ID = 0x15

    // ── opcodes: replies (notified on the same characteristic) ────────────────────────────────────
    const val NOTIFY_ACK = 0x01
    const val NOTIFY_INFO = 0x02
    const val NOTIFY_DATA = 0x03
    const val NOTIFY_DONE = 0x04
    const val NOTIFY_READ_BEGIN = 0x05
    const val NOTIFY_LIVE_MARK = 0x06

    // ── ACK statuses ──────────────────────────────────────────────────────────────────────────────
    const val ACK_OK = 0
    const val INVALID_COMMAND = 6
    const val STORAGE_NOT_READY = 9
    const val SEQ_OUT_OF_RANGE = 10
    const val RING_ID_MISMATCH = 11
    const val DEFERRED = 255

    // ── custody (custody.h) ───────────────────────────────────────────────────────────────────────
    const val CUSTODY_VERSION = 1
    /** Ask for every capability; the device grants only what it holds (LIVE_PERSIST 0x02). */
    const val CUSTODY_CAPS = 0x0F
    const val OMI_CAP_APP_ACK_RECLAIM = 0x01
    const val OMI_CAP_LIVE_PERSIST = 0x02
    const val OMI_CAP_ADVANCE_IDEMPOTENT = 0x04
    const val OMI_CAP_RING_ID = 0x08

    // ── sizes ─────────────────────────────────────────────────────────────────────────────────────
    const val INFO_LEN = 41              // STORAGE_INFO_LEN
    const val INFO_MIN_ATT_MTU = 44      // STORAGE_INFO_LEN + 3 — below this the INFO *notify* is truncated
    const val READ_LEN = 9
    const val READ_LEN_WITH_COUNT = 13
    const val ADVANCE_LEN = 9
    const val ADVANCE_ID_LEN = 17
    const val CUSTODY_LEN = 3

    /** `[0x10]` — 1 byte is a valid RING_INFO. */
    fun ringInfo(): ByteArray = byteArrayOf(CMD_RING_INFO.toByte())

    /** `[0x13]` — destructive; the collector never sends it. */
    fun ringClear(): ByteArray = byteArrayOf(CMD_RING_CLEAR.toByte())

    /** `[0x03]` — the stock STOP_SYNC opcode (NOT custody). */
    fun stopSync(): ByteArray = byteArrayOf(CMD_STOP_SYNC.toByte())

    /** `[0x11][BE64 start_seq]` — 9 bytes.  The firmware reads `sys_get_be64(cmd->data + 1)`. */
    fun ringRead(startSeq: Long): ByteArray = concat(CMD_RING_READ, be64(startSeq))

    /** `[0x11][BE64 start_seq][BE32 count]` — 13 bytes (bounded read). */
    fun ringRead(startSeq: Long, count: Int): ByteArray = concat(CMD_RING_READ, be64(startSeq), be32(count))

    /** `[0x12][BE64 new_read_seq]` — 9 bytes; the app owns reclamation. */
    fun ringAdvance(newReadSeq: Long): ByteArray = concat(CMD_RING_ADVANCE, be64(newReadSeq))

    /** `[0x15][BE64 ring_id][BE64 new_read_seq]` — 17 bytes. */
    fun ringAdvanceId(ringId: Long, newReadSeq: Long): ByteArray =
        concat(CMD_RING_ADVANCE_ID, be64(ringId), be64(newReadSeq))

    /** `[0x14][version][caps]` — exactly 3 bytes. */
    fun custodyEnable(version: Int = CUSTODY_VERSION, caps: Int = CUSTODY_CAPS): ByteArray =
        byteArrayOf(CMD_CUSTODY_ENABLE.toByte(), (version and 0xFF).toByte(), (caps and 0xFF).toByte())

    // ── big-endian helpers ────────────────────────────────────────────────────────────────────────
    fun be64(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * (7 - i))) and 0xFF).toByte() }

    fun be32(value: Int): ByteArray = ByteArray(4) { i -> ((value ushr (8 * (3 - i))) and 0xFF).toByte() }

    fun be16(value: Int): ByteArray = ByteArray(2) { i -> ((value ushr (8 * (1 - i))) and 0xFF).toByte() }

    private fun concat(opcode: Int, vararg parts: ByteArray): ByteArray {
        val out = ByteArray(1 + parts.sumOf { it.size })
        out[0] = (opcode and 0xFF).toByte()
        var off = 1
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }
}
