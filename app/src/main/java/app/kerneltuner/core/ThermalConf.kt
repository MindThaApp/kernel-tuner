package app.kerneltuner.core

/**
 * Edits two rules of the Qualcomm thermal-engine.conf shipped by the thermal-relax module and nothing else:
 * the cluster0 throttle ([SS-MSM-CLUSTER0]) and the hotter extra step ([SS-MSM-CLUSTER0-HOT]).
 * Both keep their distance from each other and their release (clr) offsets. Pure text in, text out.
 */
object ThermalConf {
    const val FIRST = "SS-MSM-CLUSTER0"
    const val HOT = "SS-MSM-CLUSTER0-HOT"
    const val MIN_C = 43      // the phone's original rule
    const val MAX_C = 65

    private val SECTION = Regex("^\\s*\\[([^\\]]+)]\\s*$")
    private val SET = Regex("^(\\s*set_point\\s+)(\\d+)(\\s*)$")
    private val CLR = Regex("^(\\s*set_point_clr\\s+)(\\d+)(\\s*)$")

    private fun value(text: String, section: String, re: Regex): Int? {
        var cur: String? = null
        for (l in text.lines()) {
            SECTION.matchEntire(l)?.let { cur = it.groupValues[1]; return@let }
            if (cur == section) re.matchEntire(l)?.let { return it.groupValues[2].toInt() }
        }
        return null
    }

    /** Current cluster0 throttle in whole degrees, or null if the rule is missing. */
    fun firstSetC(text: String?): Int? = text?.let { value(it, FIRST, SET) }?.let { Math.round(it / 1000.0).toInt() }

    /** True when the file has both rules with both keys, so a rewrite is safe. */
    fun editable(text: String?): Boolean = text != null &&
        listOf(FIRST, HOT).all { value(text, it, SET) != null && value(text, it, CLR) != null }

    /**
     * Shifts both rules so the first throttle sits at [firstC] (clamped to 43..65). [base] is the module's original
     * file; the shift is measured from its first rule so a rewrite always starts from the pristine text.
     * Returns null if [base] lacks the rules.
     */
    fun rewrite(base: String, firstC: Int): String? {
        if (!editable(base)) return null
        val want = firstC.coerceIn(MIN_C, MAX_C) * 1000
        val delta = want - value(base, FIRST, SET)!!
        if (delta == 0) return base
        var cur: String? = null
        val out = base.lines().map { l ->
            SECTION.matchEntire(l)?.let { cur = it.groupValues[1]; return@map l }
            if (cur == FIRST || cur == HOT) {
                SET.matchEntire(l)?.let { return@map it.groupValues[1] + (it.groupValues[2].toInt() + delta) + it.groupValues[3] }
                CLR.matchEntire(l)?.let { return@map it.groupValues[1] + (it.groupValues[2].toInt() + delta) + it.groupValues[3] }
            }
            l
        }
        return out.joinToString("\n")
    }
}
