package org.jetbrains.game

/**
 * Parses a map file describing a single stage and produces a [GameGrid].
 *
 * A map file optionally begins with a header section containing one or
 * more `Header: value` entries (one per line), terminated by a blank
 * line. Supported headers:
 *  - `Target` — the integer score target for the level (used by
 *    [GameGrid.target]).
 *
 * After the header (and the blank line separating it from the body), each
 * non-blank, non-comment line in the body has the form:
 *
 *     OBJECT_REF X,Y
 *
 * `OBJECT_REF` identifies what kind of object lives in the grid cell `(X, Y)`.
 * The origin `(0, 0)` is the top-left corner of the grid.
 *
 * Supported object references:
 *  - `START` — the cell where the golem starts. The golem initially faces [Direction.SOUTH].
 *  - `CHEESE` — a cheese pickup. Reaching this cell completes the level.
 *  - `WALL` — an axis-aligned wall segment spanning a range of cells. One
 *    of the coordinates is given as a range `A-B` (with `A <= B`); the
 *    other is a single integer. For example, `WALL 2,2-9` is a vertical
 *    wall at column `2` running from row `2` to row `9` inclusive, and
 *    `WALL 0-5,7` is a horizontal wall at row `7` running from column `0`
 *    to column `5` inclusive.
 *
 * Lines that are blank or that begin with `#` are ignored in the body.
 */
class MapParser {

    /**
     * Parse the contents of a map file into a [GameGrid].
     *
     * @throws MapParseException if the input is malformed or required objects are missing.
     */
    fun parse(text: String): GameGrid {
        var start: Point? = null
        val cheeses = mutableListOf<Cheese>()
        val walls = mutableListOf<Wall>()

        val lines = text.lines()
        val (headers, bodyStart) = parseHeaders(lines)
        val target = headers["Target"]?.let { value ->
            value.toIntOrNull()
                ?: throw MapParseException("Invalid Target header value: '$value'")
        } ?: 0

        for (index in bodyStart until lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val lineNumber = index + 1
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 2) {
                throw MapParseException(
                    "Expected '<REF> <COORDS>' on line $lineNumber, got: '$line'",
                )
            }
            val ref = parts[0]
            val coordsText = parts[1]

            when (ref) {
                "START" -> {
                    if (start != null) {
                        throw MapParseException(
                            "Duplicate START entry on line $lineNumber",
                        )
                    }
                    val position = parsePosition(coordsText, lineNumber)
                    start = position
                }
                "CHEESE" -> {
                    val position = parsePosition(coordsText, lineNumber)
                    validatePosition(position, lineNumber = lineNumber)
                    cheeses += Cheese(position = position)
                }
                "WALL" -> {
                    walls += parseWall(coordsText, lineNumber)
                }
                else -> throw MapParseException(
                    "Unknown object reference '$ref' on line $lineNumber",
                )
            }
        }

        val startPos = start
            ?: throw MapParseException("Map is missing a START entry")

        validatePosition(startPos)

        return GameGrid(
            tokens = listOf(Golem(position = startPos, facing = Direction.SOUTH)) + cheeses,
            walls = walls,
            target = target,
        )
    }

    /**
     * Parse the optional header section at the start of a map file.
     *
     * Header lines have the form `Header: value` and are terminated by
     * the first blank line. If the file does not contain a blank line
     * separating a header section from the body (i.e. it has no headers),
     * an empty header map is returned and the body starts at line `0`.
     *
     * Returns the parsed headers along with the index of the first line
     * of the body (the line immediately following the blank separator,
     * or `0` if there is no header section).
     */
    private fun parseHeaders(lines: List<String>): Pair<Map<String, String>, Int> {
        // No headers if the file does not start with a `Header: value` line.
        val firstLine = lines.firstOrNull()?.trim().orEmpty()
        if (!firstLine.contains(':')) return emptyMap<String, String>() to 0

        val headers = mutableMapOf<String, String>()
        for (index in lines.indices) {
            val rawLine = lines[index]
            val line = rawLine.trim()
            if (line.isEmpty()) {
                // Blank line terminates the header section; body starts after it.
                return headers to index + 1
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                throw MapParseException(
                    "Expected '<Header>: <value>' on line ${index + 1}, got: '$line'",
                )
            }
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }
        // Header section was never terminated by a blank line.
        throw MapParseException(
            "Header section must be terminated by a blank line",
        )
    }

    private fun parsePosition(coordsText: String, lineNumber: Int): Point {
        val coords = coordsText.split(",")
        if (coords.size != 2) {
            throw MapParseException(
                "Expected coordinates of the form 'X,Y' on line $lineNumber, got: '$coordsText'",
            )
        }
        val x = coords[0].toIntOrNull()
            ?: throw MapParseException("Invalid X coordinate on line $lineNumber: '${coords[0]}'")
        val y = coords[1].toIntOrNull()
            ?: throw MapParseException("Invalid Y coordinate on line $lineNumber: '${coords[1]}'")
        return Point(x, y)
    }

    private fun parseWall(coordsText: String, lineNumber: Int): Wall {
        val coords = coordsText.split(",")
        if (coords.size != 2) {
            throw MapParseException(
                "Expected wall coordinates of the form 'X,Y' on line $lineNumber, got: '$coordsText'",
            )
        }
        val xRange = parseAxis(coords[0], axis = "X", lineNumber = lineNumber)
        val yRange = parseAxis(coords[1], axis = "Y", lineNumber = lineNumber)
        if (xRange.first != xRange.second && yRange.first != yRange.second) {
            throw MapParseException(
                "Wall must be axis-aligned: only one of X or Y may be a range on line $lineNumber",
            )
        }
        if (xRange.first == xRange.second && yRange.first == yRange.second) {
            throw MapParseException(
                "Wall must span more than one cell: one of X or Y must be a range on line $lineNumber",
            )
        }
        val start = Point(xRange.first, yRange.first)
        val end = Point(xRange.second, yRange.second)
        validatePosition(start, lineNumber = lineNumber)
        validatePosition(end, lineNumber = lineNumber)
        return Wall(start, end)
    }

    private fun parseAxis(text: String, axis: String, lineNumber: Int): Pair<Int, Int> {
        val parts = text.split("-")
        return when (parts.size) {
            1 -> {
                val value = parts[0].toIntOrNull()
                    ?: throw MapParseException(
                        "Invalid $axis coordinate on line $lineNumber: '$text'",
                    )
                value to value
            }
            2 -> {
                val from = parts[0].toIntOrNull()
                    ?: throw MapParseException(
                        "Invalid $axis range on line $lineNumber: '$text'",
                    )
                val to = parts[1].toIntOrNull()
                    ?: throw MapParseException(
                        "Invalid $axis range on line $lineNumber: '$text'",
                    )
                if (from > to) {
                    throw MapParseException(
                        "$axis range must be ascending on line $lineNumber: '$text'",
                    )
                }
                from to to
            }
            else -> throw MapParseException(
                "Invalid $axis coordinate on line $lineNumber: '$text'",
            )
        }
    }

    private fun validatePosition(position: Point, lineNumber: Int? = null) {
        if (position.x !in 0 until GRID_SIZE || position.y !in 0 until GRID_SIZE) {
            val suffix = lineNumber?.let { " on line $it" } ?: ""
            throw MapParseException(
                "Position (${position.x}, ${position.y}) is outside the " +
                    "${GRID_SIZE}x${GRID_SIZE} grid$suffix",
            )
        }
    }
}

/**
 * Thrown when a map file cannot be parsed.
 */
class MapParseException(message: String) : RuntimeException(message)
