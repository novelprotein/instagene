package org.instagene.app.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgsTest {

    @Test
    fun parsesFlagsOptionsAndPositionals() {
        val args = Args(listOf("--verbose", "--out", "o.fa", "input.fa", "extra"))
        assertTrue(args.flag("verbose"))
        assertEquals("o.fa", args.opt("out"))
        assertEquals(listOf("input.fa", "extra"), args.positionals)
        assertEquals("input.fa", args.positional(0))
        assertNull(args.positional(5))
    }

    @Test
    fun equalsFormAndShortOut() {
        val args = Args(listOf("--key=value", "-o", "out.gb", "pos"))
        assertEquals("value", args.opt("key"))
        assertEquals("out.gb", args.opt("out"))
        assertEquals("pos", args.positional(0))
    }

    @Test
    fun hasTrueForFlagAndOption() {
        val args = Args(listOf("--flag", "--opt", "1"))
        assertTrue(args.has("flag"))
        assertTrue(args.has("opt"))
        assertFalse(args.has("missing"))
    }

    @Test
    fun flagAcceptsTruthyOptionValues() {
        assertTrue(Args(listOf("--x", "true")).flag("x"))
        assertTrue(Args(listOf("--x", "YES")).flag("x"))
        assertTrue(Args(listOf("--x", "1")).flag("x"))
        assertFalse(Args(listOf("--x", "no")).flag("x"))
    }

    @Test
    fun requireAndNumericParsing() {
        val args = Args(listOf("--n", "7", "--d", "1.5"))
        assertEquals("7", args.require("n"))
        assertEquals(7, args.requireInt("n"))
        assertEquals(3, args.int("missing", 3))
        assertEquals(1.5, args.double("d", 0.0))
        assertEquals(9.0, args.double("missing", 9.0))
        assertFailsWith<CliException> { args.require("nope") }
        assertFailsWith<CliException> { Args(listOf("--n", "x")).int("n", 0) }
        assertFailsWith<CliException> { Args(listOf("--d", "x")).double("d", 0.0) }
        assertFailsWith<CliException> { Args(emptyList()).requireInt("n") }
    }

    @Test
    fun loneOptionBecomesFlagWhenNoValue() {
        val args = Args(listOf("--lonely", "--next"))
        assertTrue(args.flag("lonely"))
        assertTrue(args.flag("next"))
    }

    @Test
    fun shortOutDoesNotConsumeFollowingFlag() {
        val trailing = Args(listOf("-o", "--fasta", "input.fa"))
        assertNull(trailing.opt("out"))
        // The flag consumes the file as its value, as in normal --key value parsing.
        assertEquals("input.fa", trailing.opt("fasta"))
        assertEquals(emptyList(), trailing.positionals)

        val leading = Args(listOf("-o", "out.gb", "--fasta"))
        assertEquals("out.gb", leading.opt("out"))
        assertTrue(leading.flag("fasta"))

        val bare = Args(listOf("-o"))
        assertNull(bare.opt("out"))
    }
}
