package com.winsmediatama.photolocker.windows.core.session

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStateTest {
    @Test
    fun defaultKeyPathUsesVaultFolderAndDpapiExtension() {
        val root = Path.of("C:/Users/Test/AppData/Local/PhotoLocker")
        assertEquals(root.resolve("vault").resolve("vault.key.dpapi"), UnlockedVaultSession.defaultKeyPath(root))
    }
}
