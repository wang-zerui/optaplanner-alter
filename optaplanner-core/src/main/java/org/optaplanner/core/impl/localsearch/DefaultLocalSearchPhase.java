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

package org.optaplanner.core.impl.localsearch;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.score.Score;
import org.optaplanner.core.api.score.constraint.ConstraintMatchTotal;
import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.config.solver.monitoring.SolverMetric;
import org.optaplanner.core.impl.constructionheuristic.placer.EntityPlacer;
import org.optaplanner.core.impl.constructionheuristic.placer.Placement;
import org.optaplanner.core.impl.heuristic.move.Move;
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelector;
import org.optaplanner.core.impl.localsearch.decider.LocalSearchDecider;
import org.optaplanner.core.impl.localsearch.event.LocalSearchPhaseLifecycleListener;
import org.optaplanner.core.impl.localsearch.scope.LocalSearchPhaseScope;
import org.optaplanner.core.impl.localsearch.scope.LocalSearchStepScope;
import org.optaplanner.core.impl.phase.AbstractPhase;
import org.optaplanner.core.impl.score.definition.ScoreDefinition;
import org.optaplanner.core.impl.score.director.InnerScoreDirector;
import org.optaplanner.core.impl.solver.scope.SolverScope;
import org.optaplanner.core.impl.solver.termination.Termination;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

/**
 * Default implementation of {@link LocalSearchPhase}.
 *
 * @param <Solution_> the solution type, the class with the {@link PlanningSolution} annotation
 */
public class DefaultLocalSearchPhase<Solution_> extends AbstractPhase<Solution_> implements LocalSearchPhase<Solution_>,
        LocalSearchPhaseLifecycleListener<Solution_> {

    protected LocalSearchDecider<Solution_> decider;
    protected final AtomicLong acceptedMoveCountPerStep = new AtomicLong(0);
    protected final AtomicLong selectedMoveCountPerStep = new AtomicLong(0);
    protected final Map<Tags, AtomicLong> constraintMatchTotalTagsToStepCount = new ConcurrentHashMap<>();
    protected final Map<Tags, AtomicLong> constraintMatchTotalTagsToBestCount = new ConcurrentHashMap<>();
    protected final Map<Tags, List<AtomicReference<Number>>> constraintMatchTotalStepScoreMap = new ConcurrentHashMap<>();
    protected final Map<Tags, List<AtomicReference<Number>>> constraintMatchTotalBestScoreMap = new ConcurrentHashMap<>();

    public DefaultLocalSearchPhase(int phaseIndex, String logIndentation, Termination<Solution_> termination) {
        super(phaseIndex, logIndentation, termination);
    }

    public LocalSearchDecider<Solution_> getDecider() {
        return decider;
    }

    public void setDecider(LocalSearchDecider<Solution_> decider) {
        this.decider = decider;
    }

    @Override
    public String getPhaseTypeString() {
        return "Local Search";
    }

    // ************************************************************************
    // Worker methods
    // ************************************************************************

    protected MoveSelector<Solution_> moveSelector;
    public void setMoveSelector(MoveSelector<Solution_> moveSelector) {
        this.moveSelector = moveSelector;
    }



    @Override
    public void solve(SolverScope<Solution_> solverScope) {
        LocalSearchPhaseScope<Solution_> phaseScope = new LocalSearchPhaseScope<>(solverScope);
        phaseStarted(phaseScope); // 算法开始
        if (solverScope.isMetricEnabled(SolverMetric.MOVE_COUNT_PER_STEP)) {
            Metrics.gauge(SolverMetric.MOVE_COUNT_PER_STEP.getMeterId() + ".accepted",
                    solverScope.getMonitoringTags(), acceptedMoveCountPerStep);
            Metrics.gauge(SolverMetric.MOVE_COUNT_PER_STEP.getMeterId() + ".selected",
                    solverScope.getMonitoringTags(), selectedMoveCountPerStep);
        }
        // 主循环(步骤二三的循环)
        while (!phaseTermination.isPhaseTerminated(phaseScope)) {
            LocalSearchStepScope<Solution_> stepScope = new LocalSearchStepScope<>(phaseScope);
            stepScope.setTimeGradient(phaseTermination.calculatePhaseTimeGradient(phaseScope)); // 设置时间梯度，模拟退火中可能会使用
            // 算法步骤二开始标志
            stepStarted(stepScope);
            // 调用decider决定下一步
            // 下一步通过stepScope.getStep()获取
            // 比如：将 process1 分配到 computer2 上
            decider.decideNextStep(stepScope);
            // 处理没有得到下一步的情况，会throw一些错误信息
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
                            + ") has accepted/selected move count (" + stepScope.getAcceptedMoveCount() + "/"
                            + stepScope.getSelectedMoveCount()
                            + ") but failed to pick a nextStep (" + stepScope.getStep() + ").");
                }
                // Although stepStarted has been called, stepEnded is not called for this step
                break;
            }
            // 真正进行这一步
            doStep(stepScope);
            stepEnded(stepScope);
            phaseScope.setLastCompletedStepScope(stepScope);
        }
        phaseEnded(phaseScope);
    }

    protected void doStep(LocalSearchStepScope<Solution_> stepScope) {
        Move<Solution_> step = stepScope.getStep();
        Move<Solution_> undoStep = step.doMove(stepScope.getScoreDirector());
        stepScope.setUndoStep(undoStep);
        predictWorkingStepScore(stepScope, step);
        solver.getBestSolutionRecaller().processWorkingSolutionDuringStep(stepScope);
    }

    @Override
    public void solvingStarted(SolverScope<Solution_> solverScope) {
        super.solvingStarted(solverScope);
        decider.solvingStarted(solverScope);
    }

    @Override
    public void phaseStarted(LocalSearchPhaseScope<Solution_> phaseScope) {
        super.phaseStarted(phaseScope);
        decider.phaseStarted(phaseScope);
        moveSelector.phaseStarted(phaseScope);
        // TODO maybe this restriction should be lifted to allow LocalSearch to initialize a solution too?
        assertWorkingSolutionInitialized(phaseScope);
    }

    @Override
    public void stepStarted(LocalSearchStepScope<Solution_> stepScope) {
        super.stepStarted(stepScope);
        decider.stepStarted(stepScope);
    }

    @Override
    public void stepEnded(LocalSearchStepScope<Solution_> stepScope) {
        super.stepEnded(stepScope);
        decider.stepEnded(stepScope);
        collectMetrics(stepScope);
        LocalSearchPhaseScope<Solution_> phaseScope = stepScope.getPhaseScope();
        if (logger.isDebugEnabled()) {
            logger.debug("{}    LS step ({}), time spent ({}), score ({}), {} best score ({})," +
                    " accepted/selected move count ({}/{}), picked move ({}).",
                    logIndentation,
                    stepScope.getStepIndex(),
                    phaseScope.calculateSolverTimeMillisSpentUpToNow(),
                    stepScope.getScore(),
                    (stepScope.getBestScoreImproved() ? "new" : "   "), phaseScope.getBestScore(),
                    stepScope.getAcceptedMoveCount(),
                    stepScope.getSelectedMoveCount(),
                    stepScope.getStepString());
        }
    }

    private void collectMetrics(LocalSearchStepScope<Solution_> stepScope) {
        LocalSearchPhaseScope<Solution_> phaseScope = stepScope.getPhaseScope();
        SolverScope<Solution_> solverScope = phaseScope.getSolverScope();
        if (solverScope.isMetricEnabled(SolverMetric.MOVE_COUNT_PER_STEP)) {
            acceptedMoveCountPerStep.set(stepScope.getAcceptedMoveCount());
            selectedMoveCountPerStep.set(stepScope.getSelectedMoveCount());
        }
        if (solverScope.isMetricEnabled(SolverMetric.CONSTRAINT_MATCH_TOTAL_STEP_SCORE)
                || solverScope.isMetricEnabled(SolverMetric.CONSTRAINT_MATCH_TOTAL_BEST_SCORE)) {
            InnerScoreDirector<Solution_, ?> scoreDirector = stepScope.getScoreDirector();
            ScoreDefinition<?> scoreDefinition = solverScope.getScoreDefinition();
            if (scoreDirector.isConstraintMatchEnabled()) {
                for (ConstraintMatchTotal<?> constraintMatchTotal : scoreDirector.getConstraintMatchTotalMap()
                        .values()) {
                    Tags tags = solverScope.getMonitoringTags().and(
                            "constraint.package", constraintMatchTotal.getConstraintPackage(),
                            "constraint.name", constraintMatchTotal.getConstraintName());
                    collectConstraintMatchTotalMetrics(SolverMetric.CONSTRAINT_MATCH_TOTAL_BEST_SCORE, tags,
                            constraintMatchTotalTagsToBestCount,
                            constraintMatchTotalBestScoreMap, constraintMatchTotal, scoreDefinition, solverScope);
                    collectConstraintMatchTotalMetrics(SolverMetric.CONSTRAINT_MATCH_TOTAL_STEP_SCORE, tags,
                            constraintMatchTotalTagsToStepCount,
                            constraintMatchTotalStepScoreMap, constraintMatchTotal, scoreDefinition, solverScope);
                }
            }
        }
    }

    private void collectConstraintMatchTotalMetrics(SolverMetric metric, Tags tags, Map<Tags, AtomicLong> countMap,
            Map<Tags, List<AtomicReference<Number>>> scoreMap, ConstraintMatchTotal<?> constraintMatchTotal,
            ScoreDefinition<?> scoreDefinition, SolverScope<Solution_> solverScope) {
        if (solverScope.isMetricEnabled(metric)) {
            if (countMap.containsKey(tags)) {
                countMap.get(tags).set(constraintMatchTotal.getConstraintMatchCount());
            } else {
                AtomicLong count = new AtomicLong(constraintMatchTotal.getConstraintMatchCount());
                countMap.put(tags, count);
                Metrics.gauge(metric.getMeterId() + ".count",
                        tags, count);
            }
            SolverMetric.registerScoreMetrics(metric,
                    tags, scoreDefinition, scoreMap, constraintMatchTotal.getScore());
        }
    }

    @Override
    public void phaseEnded(LocalSearchPhaseScope<Solution_> phaseScope) {
        super.phaseEnded(phaseScope);
        decider.phaseEnded(phaseScope);
        moveSelector.phaseEnded(phaseScope);
        phaseScope.endingNow();
        logger.info("{}Local Search phase ({}) ended: time spent ({}), best score ({}),"
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
        decider.solvingEnded(solverScope);
    }

}
