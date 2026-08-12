package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertTrue

class VersionTest {

    @Test
    fun versionIsSemverLike() {
        // Releases use plain SemVer; development builds append the Git commit as build metadata.
        assertTrue(Regex("""\d+(\.\d+)+(\+[\w-]+)?""").matches(Version.VERSION), "got '${Version.VERSION}'")
    }
}
