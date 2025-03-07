/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
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

package org.optaplanner.core.impl.constructionheuristic;

import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.impl.constructionheuristic.decider.ConstructionHeuristicDecider;
import org.optaplanner.core.impl.constructionheuristic.placer.EntityPlacer;
import org.optaplanner.core.impl.constructionheuristic.placer.Placement;
import org.optaplanner.core.impl.constructionheuristic.scope.ConstructionHeuristicPhaseScope;
import org.optaplanner.core.impl.constructionheuristic.scope.ConstructionHeuristicStepScope;
import org.optaplanner.core.impl.heuristic.move.Move;
import org.optaplanner.core.impl.phase.AbstractPhase;
import org.optaplanner.core.impl.solver.scope.SolverScope;
import org.optaplanner.core.impl.solver.termination.Termination;

/**
 * Default implementation of {@link ConstructionHeuristicPhase}.
 *
 * @param <Solution_> the solution type, the class with the {@link PlanningSolution} annotation
 */
public class DefaultConstructionHeuristicPhase<Solution_> extends AbstractPhase<Solution_>
        implements ConstructionHeuristicPhase<Solution_> {

    protected EntityPlacer<Solution_> entityPlacer;
    protected ConstructionHeuristicDecider<Solution_> decider;

    public DefaultConstructionHeuristicPhase(int phaseIndex, String logIndentation, Termination<Solution_> termination) {
        super(phaseIndex, logIndentation, termination);
    }

    public void setEntityPlacer(EntityPlacer<Solution_> entityPlacer) {
        this.entityPlacer = entityPlacer;
    }

    public void setDecider(ConstructionHeuristicDecider<Solution_> decider) {
        this.decider = decider;
    }

    @Override
    public String getPhaseTypeString() {
        return "Construction Heuristics";
    }

    // ************************************************************************
    // Worker methods
    // ************************************************************************

    @Override
    public void solve(SolverScope<Solution_> solverScope) {
        ConstructionHeuristicPhaseScope<Solution_> phaseScope = new ConstructionHeuristicPhaseScope<>(solverScope);
        // 算法开始
        phaseStarted(phaseScope);

        // 遍历PlanningEntity
        for (Placement<Solution_> placement : entityPlacer) {
            ConstructionHeuristicStepScope<Solution_> stepScope = new ConstructionHeuristicStepScope<>(phaseScope);
            stepStarted(stepScope);
            // 决定下一步
            decider.decideNextStep(stepScope, placement);
            // 错误分析和输出
            if (stepScope.getStep() == null) {
                if (phaseTermination.isPhaseTerminated(phaseScope)) {
                    logger.trace("{}    Step index ({}), time spent ({}) terminated without picking a nextStep.",
                            logIndentation,
                            stepScope.getStepIndex(),
                            stepScope.getPhaseScope().calculateSolverTimeMillisSpentUpToNow());
                } else if (stepScope.getSelectedMoveCount() == 0L) {
                    logger.warn("{}    No doable selected move at step index ({}), time spent ({})."
                            + " Terminating phase early.",
                            logIndentation,
                            stepScope.getStepIndex(),
                            stepScope.getPhaseScope().calculateSolverTimeMillisSpentUpToNow());
                } else {
                    throw new IllegalStateException("The step index (" + stepScope.getStepIndex()
                            + ") has selected move count (" + stepScope.getSelectedMoveCount()
                            + ") but failed to pick a nextStep (" + stepScope.getStep() + ").");
                }
                // Although stepStarted has been called, stepEnded is not called for this step
                break;
            }
            // （真正）进行这一步
            doStep(stepScope);
            stepEnded(stepScope);
            phaseScope.setLastCompletedStepScope(stepScope);
            if (phaseTermination.isPhaseTerminated(phaseScope)) {
                break;
            }
        }
        phaseEnded(phaseScope);
    }

    private void doStep(ConstructionHeuristicStepScope<Solution_> stepScope) {
        Move<Solution_> step = stepScope.getStep();
        Move<Solution_> undoStep = step.doMove(stepScope.getScoreDirector());
        stepScope.setUndoStep(undoStep);
        predictWorkingStepScore(stepScope, step);
        solver.getBestSolutionRecaller().processWorkingSolutionDuringConstructionHeuristicsStep(stepScope);
    }

    @Override
    public void solvingStarted(SolverScope<Solution_> solverScope) {
        super.solvingStarted(solverScope);
        entityPlacer.solvingStarted(solverScope);
        decider.solvingStarted(solverScope);
    }

    public void phaseStarted(ConstructionHeuristicPhaseScope<Solution_> phaseScope) {
        super.phaseStarted(phaseScope);
        entityPlacer.phaseStarted(phaseScope);
        decider.phaseStarted(phaseScope);
    }

    public void stepStarted(ConstructionHeuristicStepScope<Solution_> stepScope) {
        super.stepStarted(stepScope);
        entityPlacer.stepStarted(stepScope);
        decider.stepStarted(stepScope);
    }

    public void stepEnded(ConstructionHeuristicStepScope<Solution_> stepScope) {
        super.stepEnded(stepScope);
        entityPlacer.stepEnded(stepScope);
        decider.stepEnded(stepScope);
        if (logger.isDebugEnabled()) {
            long timeMillisSpent = stepScope.getPhaseScope().calculateSolverTimeMillisSpentUpToNow();
            logger.debug("{}    CH step ({}), time spent ({}), score ({}), selected move count ({}),"
                    + " picked move ({}).",
                    logIndentation,
                    stepScope.getStepIndex(), timeMillisSpent,
                    stepScope.getScore(),
                    stepScope.getSelectedMoveCount(),
                    stepScope.getStepString());
        }
    }

    public void phaseEnded(ConstructionHeuristicPhaseScope<Solution_> phaseScope) {
        super.phaseEnded(phaseScope);
        solver.getBestSolutionRecaller().updateBestSolutionAndFire(phaseScope.getSolverScope());
        entityPlacer.phaseEnded(phaseScope);
        decider.phaseEnded(phaseScope);
        phaseScope.endingNow();
        logger.info("{}Construction Heuristic phase ({}) ended: time spent ({}), best score ({}),"
                + " score calculation speed ({}/sec), step total ({}).",
                logIndentation,
                phaseIndex,
                phaseScope.calculateSolverTimeMillisSpentUpToNow(),
                phaseScope.getBestScore(),
                phaseScope.getPhaseScoreCalculationSpeed(),
                phaseScope.getNextStepIndex());
    }

    @Override
    public void solvingEnded(SolverScope<Solution_> solverScope) {
        super.solvingEnded(solverScope);
        entityPlacer.solvingEnded(solverScope);
        decider.solvingEnded(solverScope);
    }

}
