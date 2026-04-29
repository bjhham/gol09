package org.jetbrains.game

typealias Line = Pair<Point, Point>

val Line.start get() = first
val Line.end get() = second

/**
 * Returns true if the two lines intersect.
 */
fun linesIntersect(
    line1: Line,
    line2: Line,
): Boolean {
    fun orientation(a: Point, b: Point, c: Point): Int {
        val abX = b.x - a.x
        val abY = b.y - a.y
        val acX = c.x - a.x
        val acY = c.y - a.y
        return abX * acY - abY * acX
    }

    fun onSegment(a: Point, b: Point, c: Point): Boolean =
        c.x in minOf(a.x, b.x)..maxOf(a.x, b.x) &&
                c.y in minOf(a.y, b.y)..maxOf(a.y, b.y)

    val o1 = orientation(line1.start, line1.end, line2.start)
    val o2 = orientation(line1.start, line1.end, line2.end)
    val o3 = orientation(line2.start, line2.end, line1.start)
    val o4 = orientation(line2.start, line2.end, line1.end)

    if (o1 == 0 && onSegment(line1.start, line1.end, line2.start)) return true
    if (o2 == 0 && onSegment(line1.start, line1.end, line2.end)) return true
    if (o3 == 0 && onSegment(line2.start, line2.end, line1.start)) return true
    if (o4 == 0 && onSegment(line2.start, line2.end, line1.end)) return true

    return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0)
}
