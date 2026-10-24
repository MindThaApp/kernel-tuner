package app.kerneltuner.hw

object HardwareDetector {
    /** [cachedClusters]: clusters seen earlier. Cores that are hotplugged off hide their cpufreq files, so a cluster can vanish. */
    fun detect(fs: SysFs, cachedClusters: List<CpuCluster> = emptyList()): Hardware = Hardware(
        clusters = CpuDetector.detect(fs, cachedClusters),
        gpu = GpuDetector.detect(fs),
        thermal = ThermalDetector.detect(fs),
        blocks = BlockDetector.detect(fs),
        memItems = Memory.available(fs),
        cpuCount = CpuDetector.cpuCount(fs),
        tempGroups = TempLimitDetector.detect(fs),
    )
}
