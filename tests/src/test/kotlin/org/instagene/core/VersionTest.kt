package org.instagene.core

import kotlin.test.Test
import kotlin.test.assertTrue

class VersionTest {

    @Test
    fun versionIsSemverLike() {
        // A release is exactly `1.0`; development builds append the git commit: `1.0+abc1234`.
        assertTrue(Regex("""\d+(\.\d+)+(\+[\w-]+)?""").matches(Version.VERSION), "got '${Version.VERSION}'")
    }
}