package com.winsmediatama.photolocker.windows.core.session

import com.winsmediatama.photolocker.windows.platform.dpapi.WindowsVaultKeyStore
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the unwrapped vault key and exposes it only for short synchronous operations.
 * Locking performs best-effort zeroization of the JVM byte array.
 */
class UnlockedVaultSession(private val keyStore: WindowsVaultKeyStore) {
    private val mutex = ReentrantLock()
    private var activeKey: ByteArray? = null

    val isUnlocked: Boolean
        get() = mutex.withLock { activeKey != null }

    fun initialize() = mutex.withLock {
        check(activeKey == null) { "Vault is already unlocked." }
        activeKey = keyStore.createVaultKey()
    }

    fun unlock() = mutex.withLock {
        check(activeKey == null) { "Vault is already unlocked." }
        activeKey = keyStore.unlock()
    }

    fun lock() = mutex.withLock {
        activeKey?.fill(0)
        activeKey = null
    }

    /** The key must not escape the callback. Do not retain it or copy it. */
    fun <T> withKey(block: (ByteArray) -> T): T = mutex.withLock {
        val key = activeKey ?: error("Vault is locked.")
        block(key)
    }

    companion object {
        fun defaultKeyPath(appDataDirectory: Path): Path =
            appDataDirectory.resolve("vault").resolve("vault.key.dpapi")
    }
}
