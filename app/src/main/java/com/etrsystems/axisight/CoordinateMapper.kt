package com.etrsystems.axisight

import kotlin.math.min

/**
 * Maps coordinates between camera image space and view (screen) space.
 *
 * The camera analysis image differs from the on-screen preview in three ways:
 *  1. Scale — image may be 640×480 while the view is 1920×1080
 *  2. Rotation — the sensor may deliver a landscape image on a portrait device
 *  3. Letterboxing — FIT_CENTER adds horizontal or vertical black bars
 *
 * Update this mapper whenever the image size, view size, or rotation changes.
 * Then use [imageToView] / [viewToImage] for all coordinate conversions.
 *
 * Thread-safe: [update] and the map functions may be called from any thread.
 */
class CoordinateMapper {

    private val lock = Any()
    private var imageWidth = 1
    private var imageHeight = 1
    private var rotationDegrees = 0
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var _valid = false

    val isValid: Boolean get() = synchronized(lock) { _valid }

    /**
     * @param imageWidth      Width of the raw ImageProxy / Bitmap (before rotation)
     * @param imageHeight     Height of the raw ImageProxy / Bitmap (before rotation)
     * @param rotationDegrees Clockwise degrees to rotate the image to match display orientation
     * @param viewWidth       Width of the on-screen preview view
     * @param viewHeight      Height of the on-screen preview view
     */
    fun update(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360

        // Logical image dimensions after rotation (what the user sees)
        val logW = if (normalizedRotation == 90 || normalizedRotation == 270) {
            imageHeight.toFloat()
        } else {
            imageWidth.toFloat()
        }
        val logH = if (normalizedRotation == 90 || normalizedRotation == 270) {
            imageWidth.toFloat()
        } else {
            imageHeight.toFloat()
        }

        val s = min(viewWidth / logW, viewHeight / logH)
        val ox = (viewWidth - logW * s) / 2f
        val oy = (viewHeight - logH * s) / 2f

        synchronized(lock) {
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
            this.rotationDegrees = normalizedRotation
            this.scale = s
            this.offsetX = ox
            this.offsetY = oy
            this._valid = true
        }
    }

    /** Maps a point from camera image space to view (screen) space. */
    fun imageToView(x: Float, y: Float): Pair<Float, Float> {
        val (lx, ly) = imageToLogical(x, y)
        return synchronized(lock) {
            (lx * scale + offsetX) to (ly * scale + offsetY)
        }
    }

    /** Maps a point from view (screen) space to camera image space. */
    fun viewToImage(x: Float, y: Float): Pair<Float, Float> {
        val lx: Float
        val ly: Float
        synchronized(lock) {
            lx = (x - offsetX) / scale
            ly = (y - offsetY) / scale
        }
        return logicalToImage(lx, ly)
    }

    /** Maps a scalar radius from image space to view space (uses the scale factor only). */
    fun imageRadiusToView(radius: Float): Float = synchronized(lock) { radius * scale }

    /** Maps a scalar radius from view space to image space. */
    fun viewRadiusToImage(radius: Float): Float = synchronized(lock) {
        if (scale > 0) radius / scale else radius
    }

    // Rotate image-space point to logical (display-oriented) space
    private fun imageToLogical(xi: Float, yi: Float): Pair<Float, Float> {
        val (imgW, imgH, rot) = synchronized(lock) {
            Triple(imageWidth, imageHeight, rotationDegrees)
        }
        return when (rot) {
            0 -> xi to yi
            90 -> (imgH - 1 - yi) to xi
            180 -> (imgW - 1 - xi) to (imgH - 1 - yi)
            270 -> yi to (imgW - 1 - xi)
            else -> xi to yi
        }
    }

    // Rotate logical (display-oriented) point back to image space
    private fun logicalToImage(lx: Float, ly: Float): Pair<Float, Float> {
        val (imgW, imgH, rot) = synchronized(lock) {
            Triple(imageWidth, imageHeight, rotationDegrees)
        }
        return when (rot) {
            0 -> lx to ly
            90 -> ly to (imgH - 1 - lx)
            180 -> (imgW - 1 - lx) to (imgH - 1 - ly)
            270 -> (imgW - 1 - ly) to lx
            else -> lx to ly
        }
    }
}
