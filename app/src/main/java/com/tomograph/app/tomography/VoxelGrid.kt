package com.tomograph.app.tomography

class VoxelGrid(
    val nx: Int, val ny: Int, val nz: Int,
    val originX: Double, val originY: Double, val originZ: Double,
    val voxelSizeMeters: Double,
    initialVelocity: Double = 1500.0
) {
    val velocities = DoubleArray(nx * ny * nz) { initialVelocity }

    fun index(ix: Int, iy: Int, iz: Int) = (iz * ny + iy) * nx + ix

    fun voxelCenter(ix: Int, iy: Int, iz: Int): DoubleArray = doubleArrayOf(
        originX + (ix + 0.5) * voxelSizeMeters,
        originY + (iy + 0.5) * voxelSizeMeters,
        originZ + (iz + 0.5) * voxelSizeMeters
    )

    fun inBounds(ix: Int, iy: Int, iz: Int) =
        ix in 0 until nx && iy in 0 until ny && iz in 0 until nz
}
