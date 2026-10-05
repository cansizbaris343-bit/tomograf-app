package com.tomograph.app.tomography

import kotlin.math.max
import kotlin.math.sqrt

class SirtInversion(
    private val grid: VoxelGrid,
    private val iterations: Int = 150,
    private val relaxationFactor: Double = 0.15,
    private val regularizationWeight: Double = 0.08
) {

    data class RayPath(
        val sourceIdx: Int,
        val receiverIdx: Int,
        val sourcePos: DoubleArray,
        val receiverPos: DoubleArray,
        val measuredTravelTimeSec: Double
    )

    fun invert(rays: List<RayPath>): VoxelGrid {
        val rayVoxelWeights = rays.map { computeRayVoxelIntersections(it) }

        // ISIN YOGUNLUGU: her vokselden kac BAGIMSIZ ray gectigini say.
        // Bu, inversion matematiginden bagimsiz, sadece geometrik bir
        // kayittir - Tomography3DView bunu dusuk-guven bolgelerini
        // soluklastirmak/elemek icin kullanir.
        grid.rayHitCount.fill(0)
        for (weights in rayVoxelWeights) {
            for ((voxelIdx, pathLength) in weights) {
                if (pathLength > 0.0) grid.rayHitCount[voxelIdx]++
            }
        }

        repeat(iterations) {
            val correction = DoubleArray(grid.velocities.size)
            val weightSum = DoubleArray(grid.velocities.size)

            for ((rayIdx, ray) in rays.withIndex()) {
                val weights = rayVoxelWeights[rayIdx]
                if (weights.isEmpty()) continue

                var predictedTime = 0.0
                for ((voxelIdx, pathLength) in weights) {
                    val v = max(grid.velocities[voxelIdx], 1.0)
                    predictedTime += pathLength / v
                }

                val residual = ray.measuredTravelTimeSec - predictedTime
                val totalPathLength = weights.sumOf { it.second }
                if (totalPathLength <= 0.0) continue

                for ((voxelIdx, pathLength) in weights) {
                    val share = pathLength / totalPathLength
                    correction[voxelIdx] += residual * share
                    weightSum[voxelIdx] += 1.0
                }
            }

            for (i in grid.velocities.indices) {
                if (weightSum[i] > 0.0) {
                    val v = grid.velocities[i]
                    val avgCorrection = correction[i] / weightSum[i]
                    grid.velocities[i] = max(50.0, v + relaxationFactor * v * v * avgCorrection)
                }
            }

            applySmoothingRegularization()
        }

        return grid
    }

    private fun computeRayVoxelIntersections(ray: RayPath): List<Pair<Int, Double>> {
        val steps = 200
        val dx = (ray.receiverPos[0] - ray.sourcePos[0]) / steps
        val dy = (ray.receiverPos[1] - ray.sourcePos[1]) / steps
        val dz = (ray.receiverPos[2] - ray.sourcePos[2]) / steps
        val segmentLength = sqrt(dx * dx + dy * dy + dz * dz)

        val accum = HashMap<Int, Double>()
        for (s in 0 until steps) {
            val px = ray.sourcePos[0] + (s + 0.5) * dx
            val py = ray.sourcePos[1] + (s + 0.5) * dy
            val pz = ray.sourcePos[2] + (s + 0.5) * dz

            val ix = ((px - grid.originX) / grid.voxelSizeMeters).toInt()
            val iy = ((py - grid.originY) / grid.voxelSizeMeters).toInt()
            val iz = ((pz - grid.originZ) / grid.voxelSizeMeters).toInt()

            if (grid.inBounds(ix, iy, iz)) {
                val idx = grid.index(ix, iy, iz)
                accum[idx] = (accum[idx] ?: 0.0) + segmentLength
            }
        }
        return accum.toList()
    }

    private fun applySmoothingRegularization() {
        val original = grid.velocities.copyOf()
        for (iz in 0 until grid.nz) for (iy in 0 until grid.ny) for (ix in 0 until grid.nx) {
            val idx = grid.index(ix, iy, iz)
            var sum = 0.0
            var count = 0
            for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                val nx2 = ix + dx; val ny2 = iy + dy; val nz2 = iz + dz
                if (grid.inBounds(nx2, ny2, nz2)) {
                    sum += original[grid.index(nx2, ny2, nz2)]
                    count++
                }
            }
            val neighborAvg = sum / count
            grid.velocities[idx] = (1 - regularizationWeight) * original[idx] +
                    regularizationWeight * neighborAvg
        }
    }
}
