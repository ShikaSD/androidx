/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.PathDirection
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.PathOp

actual fun Path(): Path = SkiaBackedPath()

/**
 * Convert the [org.jetbrains.skia.Path] instance into a Compose-compatible Path
 */
fun org.jetbrains.skia.Path.asComposePath(): Path = SkiaBackedPath(this)

/**
 * Obtain a reference to the [org.jetbrains.skia.Path]
 *
 * @Throws UnsupportedOperationException if this Path is not backed by an org.jetbrains.skia.Path
 */
fun Path.asSkiaPath(): org.jetbrains.skia.Path =
    if (this is SkiaBackedPath) {
        internalPath
    } else {
        throw UnsupportedOperationException("Unable to obtain org.jetbrains.skia.Path")
    }

internal class SkiaBackedPath(
    internalPath: org.jetbrains.skia.Path = org.jetbrains.skia.Path()
) : Path {
    var internalPath = internalPath
        private set

    override var fillType: PathFillType
        get() {
            if (internalPath.fillMode == PathFillMode.EVEN_ODD) {
                return PathFillType.EvenOdd
            } else {
                return PathFillType.NonZero
            }
        }

        set(value) {
            internalPath.fillMode =
                if (value == PathFillType.EvenOdd) {
                    PathFillMode.EVEN_ODD
                } else {
                    PathFillMode.WINDING
                }
        }

    override fun moveTo(x: Float, y: Float) {
        internalPath.moveTo(x, y)
    }

    override fun relativeMoveTo(dx: Float, dy: Float) {
        internalPath.rMoveTo(dx, dy)
    }

    override fun lineTo(x: Float, y: Float) {
        internalPath.lineTo(x, y)
    }

    override fun relativeLineTo(dx: Float, dy: Float) {
        internalPath.rLineTo(dx, dy)
    }

    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        internalPath.quadTo(x1, y1, x2, y2)
    }

    override fun relativeQuadraticBezierTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float) {
        internalPath.rQuadTo(dx1, dy1, dx2, dy2)
    }

    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        internalPath.cubicTo(
            x1, y1,
            x2, y2,
            x3, y3
        )
    }

    override fun relativeCubicTo(
        dx1: Float,
        dy1: Float,
        dx2: Float,
        dy2: Float,
        dx3: Float,
        dy3: Float
    ) {
        internalPath.rCubicTo(
            dx1, dy1,
            dx2, dy2,
            dx3, dy3
        )
    }

    override fun arcTo(
        rect: Rect,
        startAngleDegrees: Float,
        sweepAngleDegrees: Float,
        forceMoveTo: Boolean
    ) {
        internalPath.arcTo(
            rect.toSkiaRect(),
            startAngleDegrees,
            sweepAngleDegrees,
            forceMoveTo
        )
    }

    override fun addRect(rect: Rect) {
        internalPath.addRect(rect.toSkiaRect(), PathDirection.COUNTER_CLOCKWISE)
    }

    override fun addOval(oval: Rect) {
        internalPath.addOval(oval.toSkiaRect(), PathDirection.COUNTER_CLOCKWISE)
    }

    override fun addArcRad(oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float) {
        addArc(oval, degrees(startAngleRadians), degrees(sweepAngleRadians))
    }

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float) {
        internalPath.addArc(oval.toSkiaRect(), startAngleDegrees, sweepAngleDegrees)
    }

    override fun addRoundRect(roundRect: RoundRect) {
        internalPath.addRRect(roundRect.toSkiaRRect(), PathDirection.COUNTER_CLOCKWISE)
    }

    override fun addPath(path: Path, offset: Offset) {
        internalPath.addPath(path.asSkiaPath(), offset.x, offset.y)
    }

    override fun close() {
        internalPath.closePath()
    }

    override fun reset() {
        // preserve fillType to match the Android behavior
        // see https://cs.android.com/android/_/android/platform/frameworks/base/+/d0f379c1976c600313f1f4c39f2587a649e3a4fc
        val fillType = this.fillType
        internalPath.reset()
        this.fillType = fillType
    }

    override fun rewind() {
        internalPath.rewind()
    }

    override fun translate(offset: Offset) {
        internalPath.transform(Matrix33.makeTranslate(offset.x, offset.y))
    }

    override fun getBounds(): Rect {
        val bounds = internalPath.bounds
        return Rect(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom
        )
    }

    override fun op(
        path1: Path,
        path2: Path,
        operation: PathOperation
    ): Boolean {
        val path = org.jetbrains.skia.Path.makeCombining(
            path1.asSkiaPath(),
            path2.asSkiaPath(),
            operation.toSkiaOperation()
        )

        internalPath = path ?: internalPath
        return path != null
    }

    private fun PathOperation.toSkiaOperation() = when (this) {
        PathOperation.Difference -> PathOp.DIFFERENCE
        PathOperation.Intersect -> PathOp.INTERSECT
        PathOperation.Union -> PathOp.UNION
        PathOperation.Xor -> PathOp.XOR
        PathOperation.ReverseDifference -> PathOp.REVERSE_DIFFERENCE
        else -> PathOp.XOR
    }

    override val isConvex: Boolean get() = internalPath.isConvex

    override val isEmpty: Boolean get() = internalPath.isEmpty
}