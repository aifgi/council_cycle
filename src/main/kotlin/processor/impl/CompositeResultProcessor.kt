package processor.impl

import orchestrator.Scheme
import processor.ResultProcessor

class CompositeResultProcessor(private val delegates: List<ResultProcessor>) : ResultProcessor {

    override fun process(councilName: String, committeeName: String, schemes: List<Scheme>) {
        for (delegate in delegates) {
            delegate.process(councilName, committeeName, schemes)
        }
    }
}
