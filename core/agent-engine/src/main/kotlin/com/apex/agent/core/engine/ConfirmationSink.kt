package com.apex.agent.core.engine

/**
 * Engine capability for resuming plan/spec confirmation gates.
 *
 * [com.apex.agent.core.engine.orchestrator.DefaultTaskOrchestrator] wraps a
 * delegate [AgentEngine] for PLAN / SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM
 * modes. When the UI confirms or rejects a generated plan/spec, the
 * orchestrator forwards the decision to its delegate — previously done via
 * `javaClass.getMethod(...)` reflection, which is fragile (renames break at
 * runtime instead of compile time) and invisible to the type system.
 *
 * Engines that expose confirmation gates (notably [ApexAgentEngine]) implement
 * this interface; the orchestrator now forwards through a plain
 * `delegate as? ConfirmationSink` check.
 */
interface ConfirmationSink {

    /**
     * User confirmed or rejected the plan awaiting confirmation
     * ([AgentEvent.PlanAwaitingConfirmation]).
     */
    fun submitPlanConfirmation(confirmed: Boolean)

    /**
     * User confirmed or rejected the spec awaiting confirmation
     * ([AgentEvent.SpecAwaitingConfirmation]).
     */
    fun submitSpecConfirmation(confirmed: Boolean)
}
