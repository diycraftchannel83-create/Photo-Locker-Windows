package com.winsmediatama.photolocker.windows.platform.dpapi
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** Current-user Windows DPAPI wrapper. Output buffers are released with LocalFree. */
object WindowsDpapiKeyProtector {
    private const val UI_FORBIDDEN = 1
    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null
    }
    private interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(input: DataBlob, description: Pointer?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, output: DataBlob): Boolean
        fun CryptUnprotectData(input: DataBlob, description: Pointer?, entropy: DataBlob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, output: DataBlob): Boolean
    }
    private interface Kernel32 : StdCallLibrary { fun LocalFree(ptr: Pointer?): Pointer? }
    private val crypt32 by lazy { Native.load("Crypt32", Crypt32::class.java, W32APIOptions.UNICODE_OPTIONS) }
    private val kernel32 by lazy { Native.load("Kernel32", Kernel32::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    fun protect(rawKey: ByteArray, entropy: ByteArray? = null): ByteArray {
        requireWindows(); require(rawKey.isNotEmpty())
        val inMem = Memory(rawKey.size.toLong()).apply { write(0, rawKey, 0, rawKey.size) }
        val inBlob = DataBlob().apply { cbData = rawKey.size; pbData = inMem }
        val entMem = entropy?.let { Memory(it.size.toLong()).apply { write(0, it, 0, it.size) } }
        val entBlob = entMem?.let { DataBlob().apply { cbData = entropy!!.size; pbData = it } }
        val out = DataBlob()
        if (!crypt32.CryptProtectData(inBlob, null, entBlob, null, null, UI_FORBIDDEN, out))
            throw IllegalStateException("CryptProtectData failed, Win32 error ${Native.getLastError()}")
        return try { out.pbData!!.getByteArray(0, out.cbData) } finally { kernel32.LocalFree(out.pbData) }
    }

    fun unprotect(blob: ByteArray, entropy: ByteArray? = null): ByteArray {
        requireWindows(); require(blob.isNotEmpty())
        val inMem = Memory(blob.size.toLong()).apply { write(0, blob, 0, blob.size) }
        val inBlob = DataBlob().apply { cbData = blob.size; pbData = inMem }
        val entMem = entropy?.let { Memory(it.size.toLong()).apply { write(0, it, 0, it.size) } }
        val entBlob = entMem?.let { DataBlob().apply { cbData = entropy!!.size; pbData = it } }
        val out = DataBlob()
        if (!crypt32.CryptUnprotectData(inBlob, null, entBlob, null, null, UI_FORBIDDEN, out))
            throw IllegalStateException("CryptUnprotectData failed, Win32 error ${Native.getLastError()}")
        return try { out.pbData!!.getByteArray(0, out.cbData) } finally { kernel32.LocalFree(out.pbData) }
    }
    private fun requireWindows() {
        check(System.getProperty("os.name").startsWith("Windows", true)) { "DPAPI is Windows-only." }
    }
}