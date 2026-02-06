package processor

import orchestrator.Scheme

fun interface ResultProcessor {
    fun process(councilName: String, committeeName: String, schemes: List<Scheme>)
}
