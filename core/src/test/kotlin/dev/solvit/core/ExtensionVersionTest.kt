package dev.solvit.core

import kotlin.test.*

class ExtensionVersionTest {
    @Test fun numericComponentsTakePrecedenceOverTextOrdering() {
        assertTrue(compareExtensionVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareExtensionVersions("2.0.0", "1.99.99") > 0)
        assertTrue(compareExtensionVersions("1.4.1", "1.4.2") < 0)
        assertEquals(0, compareExtensionVersions("1.4.2", "1.4.2"))
    }
    @Test fun validManifestVersionsDoNotOverflowMachineIntegers() {
        assertTrue(compareExtensionVersions("1.99999999999999999999999.0", "1.2147483647.9") > 0)
    }
    @Test fun rejectVersionsOutsideTheManifestFormat() {
        for (invalid in listOf("1.2", "1.2.3-beta", "01.2.3", "-1.2.3", "1.a.3")) {
            assertFailsWith<IllegalArgumentException> { compareExtensionVersions(invalid, "1.2.3") }
        }
    }
}
