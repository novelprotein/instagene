package org.instagene.core.io

import org.instagene.core.FeatureLocationNode
import org.instagene.core.FeatureLocationOperator
import org.instagene.core.FeatureSegment
import org.instagene.core.LocationBoundary
import org.instagene.core.Strand

/** Recursive parser and formatter for the INSDC feature-location grammar. */
internal object GenBankLocations {
    data class Parsed(val node: FeatureLocationNode, val segments: List<FeatureSegment>)

    fun parse(expression: String): Parsed {
        val parser = Parser(expression.filterNot(Char::isWhitespace))
        val node = parser.parseNode()
        if (!parser.atEnd()) parser.fail("Unexpected '${parser.peek()}' after location")
        return Parsed(node, flatten(node))
    }

    fun flatten(node: FeatureLocationNode, inheritedStrand: Strand = Strand.FORWARD): List<FeatureSegment> {
        val strand = if (node.complemented) inheritedStrand.flipped() else inheritedStrand
        return node.segment?.let { listOf(it.copy(strand = strand)) }
            ?: node.children.flatMap { flatten(it, strand) }
    }

    fun format(node: FeatureLocationNode): String {
        val body = node.segment?.let(::formatSegment) ?: node.children.joinToString(",") { format(it) }
            .let { "${node.operator?.name?.lowercase()}($it)" }
        return if (node.complemented) "complement($body)" else body
    }

    private fun formatSegment(segment: FeatureSegment): String {
        val remote = segment.remoteAccession?.let { "$it:" }.orEmpty()
        if (segment.between) return "$remote${segment.start + 1}^${segment.end + 1}"
        if (segment.end == segment.start + 1 && segment.endBoundary == LocationBoundary.EXACT) {
            return "$remote${boundaryPrefix(segment.startBoundary)}${segment.start + 1}"
        }
        val start = boundaryPrefix(segment.startBoundary) + (segment.start + 1)
        val end = boundaryPrefix(segment.endBoundary) + segment.end
        return if (segment.end == segment.start + 1 && segment.startBoundary == LocationBoundary.EXACT && segment.endBoundary == LocationBoundary.EXACT) {
            "$remote${segment.start + 1}"
        } else {
            "$remote$start..$end"
        }
    }

    private fun boundaryPrefix(boundary: LocationBoundary): String = when (boundary) {
        LocationBoundary.EXACT -> ""
        LocationBoundary.LESS_THAN -> "<"
        LocationBoundary.GREATER_THAN -> ">"
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun atEnd(): Boolean = index == source.length

        fun peek(): Char = source.getOrNull(index) ?: '\u0000'

        fun parseNode(): FeatureLocationNode {
            val tokenStart = index
            while (!atEnd() && peek() != '(' && peek() != ',' && peek() != ')') index++
            val token = source.substring(tokenStart, index)
            if (peek() == '(') {
                val operator = when (token.lowercase()) {
                    "join" -> FeatureLocationOperator.JOIN
                    "order" -> FeatureLocationOperator.ORDER
                    "bond" -> FeatureLocationOperator.BOND
                    "complement" -> null
                    else -> fail("Unsupported location operator '$token'")
                }
                index++
                val children = ArrayList<FeatureLocationNode>()
                if (peek() == ')') fail("Location operator '$token' has no children")
                while (true) {
                    children += parseNode()
                    when (peek()) {
                        ',' -> index++
                        ')' -> {
                            index++
                            break
                        }
                        else -> fail("Expected ',' or ')' in location operator '$token'")
                    }
                }
                if (operator == null) {
                    require(children.size == 1) { "complement() must contain one location" }
                    val child = children.single()
                    return child.copy(complemented = !child.complemented)
                }
                return FeatureLocationNode(operator = operator, children = children)
            }
            if (token.isEmpty()) fail("Missing location segment")
            return FeatureLocationNode(segment = parseSegment(token))
        }

        private fun parseSegment(token: String): FeatureSegment {
            val remoteSplit = token.lastIndexOf(':')
            val remote = token.takeIf { remoteSplit > 0 }?.substring(0, remoteSplit)
            val coordinates = if (remoteSplit > 0) token.substring(remoteSplit + 1) else token
            val between = coordinates.split('^')
            if (between.size == 2) {
                val left = parsePosition(between[0]).first
                val right = parsePosition(between[1]).first
                return FeatureSegment(
                    start = minOf(left, right) - 1,
                    end = maxOf(left, right) - 1,
                    remoteAccession = remote,
                    between = true,
                )
            }
            val range = coordinates.split("..")
            return when (range.size) {
                1 -> {
                    val position = parsePosition(range[0]).first
                    FeatureSegment(
                        start = position - 1,
                        end = position,
                        remoteAccession = remote,
                        startBoundary = parsePosition(range[0]).second,
                    )
                }
                2 -> {
                    val start = parsePosition(range[0])
                    val end = parsePosition(range[1])
                    FeatureSegment(
                        start = start.first - 1,
                        end = end.first,
                        remoteAccession = remote,
                        startBoundary = start.second,
                        endBoundary = end.second,
                    )
                }
                else -> fail("Malformed location segment '$token'")
            }
        }

        private fun parsePosition(value: String): Pair<Int, LocationBoundary> {
            val boundary = when (value.firstOrNull()) {
                '<' -> LocationBoundary.LESS_THAN
                '>' -> LocationBoundary.GREATER_THAN
                else -> LocationBoundary.EXACT
            }
            val number = value.trimStart('<', '>').toIntOrNull()
                ?: fail("Expected a numeric position, got '$value'")
            require(number > 0) { "INSDC positions are one-based: '$value'" }
            return number to boundary
        }

        fun fail(message: String): Nothing = throw SeqIOException("Invalid GenBank feature location '$source': $message")
    }
}
