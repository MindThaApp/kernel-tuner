package app.kerneltuner.hw

object HardwareDetector {
    fun detect(fs: SysFs): Hardware = Hardware(
        clusters = CpuDetector.detect(fs),
        gpu = GpuDetector.detect(fs),
        thermal = ThermalDetector.detect(fs),
        blocks = BlockDetector.detect(fs),
        memItems = Memory.available(fs),
        cpuCount = CpuDetector.cpuCount(fs),
    )
}
