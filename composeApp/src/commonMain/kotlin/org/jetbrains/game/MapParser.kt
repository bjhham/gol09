package org.jetbrains.game

/**
 * Parses a map file describing a single stage and produces a [GameGrid].
 *
 * The map file is a plain-text file where each non-blank line has the form:
 *
 *     OBJECT_REF X,Y
 *
 * `OBJECT_REF` identifies what kind of object lives in the grid cell `(X, Y)`.
 * The origin `(0, 0)` is the top-left corner of the grid.
 *
 * Supported object references:
 *  - `START` — the cell where the golem starts. The golem initially faces [Direction.SOUTH].
 *
 * Lines that are blank or that begin with `#` are ignored.
 */
class MapParser {

    /**
     * Parse the contents of a map file into a [GameGrid].
     *
     * @throws MapParseException if the input is malformed or required objects are missing.
     */
    fun parse(text: String): GameGrid {
        var start: Position? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val entry = parseLine(line, lineNumber = index + 1)
            when (entry.ref) {
                "START" -> {
                    if (start != null) {
                        throw MapParseException(
                            "Duplicate START entry on line ${index + 1}",
                        )
                    }
                    start = entry.position
                }
                else -> throw MapParseException(
                    "Unknown object reference '${entry.ref}' on line ${index + 1}",
                )
            }
        }

        val startPos = start
            ?: throw MapParseException("Map is missing a START entry")

        validatePosition(startPos)

        return GameGrid(
            golem = Golem(position = startPos, facing = Direction.SOUTH),
        )
    }

    private fun parseLine(line: String, lineNumber: Int): Entry {
        val parts = line.split(Regex("\\s+"))
        if (parts.size != 2) {
            throw MapParseException(
                "Expected '<REF> <X>,<Y>' on line $lineNumber, got: '$line'",
            )
        }
        val ref = parts[0]
        val coords = parts[1].split(",")
        if (coords.size != 2) {
            throw MapParseException(
                "Expected coordinates of the form 'X,Y' on line $lineNumber, got: '${parts[1]}'",
            )
        }
        val x = coords[0].toIntOrNull()
            ?: throw MapParseException("Invalid X coordinate on line $lineNumber: '${coords[0]}'")
        val y = coords[1].toIntOrNull()
            ?: throw MapParseException("Invalid Y coordinate on line $lineNumber: '${coords[1]}'")
        return Entry(ref, Position(x, y))
    }

    private fun validatePosition(position: Position) {
        if (position.x !in 0 until GRID_SIZE || position.y !in 0 until GRID_SIZE) {
            throw MapParseException(
                "Position (${position.x}, ${position.y}) is outside the " +
                    "${GRID_SIZE}x${GRID_SIZE} grid",
            )
        }
    }

    private data class Entry(val ref: String, val position: Position)
}

/**
 * Thrown when a map file cannot be parsed.
 */
class MapParseException(message: String) : RuntimeException(message)
