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

package org.optaplanner.core.impl.phase.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.score.Score;
import org.optaplanner.core.api.score.director.ScoreDirector;
import org.optaplanner.core.impl.heuristic.move.Move;
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelector;
import org.optaplanner.core.impl.localsearch.scope.LocalSearchStepScope;
import org.optaplanner.core.impl.phase.AbstractPhase;
import org.optaplanner.core.impl.phase.custom.scope.CustomPhaseScope;
import org.optaplanner.core.impl.phase.custom.scope.CustomStepScope;
import org.optaplanner.core.impl.score.director.InnerScoreDirector;
import org.optaplanner.core.impl.solver.scope.SolverScope;
import org.optaplanner.core.impl.solver.termination.Termination;

/**
 * Default implementation of {@link CustomPhase}.
 *
 * @param <Solution_> the solution type, the class with the {@link PlanningSolution} annotation
 */
public class DefaultCustomPhase<Solution_> extends AbstractPhase<Solution_> implements CustomPhase<Solution_> {

    protected MoveSelector<Solution_> moveSelector;

    public DefaultCustomPhase(int phaseIndex, String logIndentation, Termination<Solution_> termination) {
        super(phaseIndex, logIndentation, termination);
    }

    @Override
    public String getPhaseTypeString() {
        return "Custom";
    }

    public MoveSelector<Solution_> getMoveSelector() {
        return moveSelector;
    }

    public void setMoveSelector(MoveSelector<Solution_> moveSelector){
        this.moveSelector = moveSelector;
    }
    // ************************************************************************
    // Worker methods
    // ************************************************************************

    @Override
    public void solve(SolverScope<Solution_> solverScope) {
        CustomPhaseScope<Solution_> phaseScope = new CustomPhaseScope<>(solverScope);
        phaseStarted(phaseScope);
        CustomStepScope<Solution_> stepScope = new CustomStepScope<>(phaseScope);

        InnerScoreDirector<Solution_, ?> scoreDirector = solverScope.getScoreDirector();
        while(!phaseTermination.isPhaseTerminated(phaseScope)){
            List<Move<Solution_>> moves = new ArrayList<>();
            List<Score> scores = new ArrayList<>();

            stepStarted(stepScope);

            for(Move<Solution_> move : moveSelector){
                Move<Solution_> undoMove = move.doMove(scoreDirector);
                Score score = scoreDirector.calculateScore();
                moves.add(move);
                scores.add(score);
                undoMove.doMove(scoreDirector);
            }

            Score maxScore = Collections.max(scores);
            int i = scores.indexOf(maxScore);
            Move<Solution_> nextStep = moves.get(i);
            stepScope.setScore(maxScore);

            Score bestScore = solverScope.getBestScore();
            if( maxScore.compareTo(bestScore) <= 0 ) {
                break;
            }else{
                doStep(stepScope, nextStep);
                stepEnded(stepScope);
            }
        }

        phaseEnded(phaseScope);
    }

    public void phaseStarted(CustomPhaseScope<Solution_> phaseScope) {
        super.phaseStarted(phaseScope);
        moveSelector.phaseStarted(phaseScope);
    }

    public void stepStarted(CustomStepScope<Solution_> stepScope) {
        super.stepStarted(stepScope);
    }

    protected void doStep(CustomStepScope<Solution_> stepScope, Move<Solution_> step) {
        Move<Solution_> undoStep = step.doMove(stepScope.getScoreDirector());
        predictWorkingStepScore(stepScope, step);
        solver.getBestSolutionRecaller().processWorkingSolutionDuringStep(stepScope);
    }

//    private void doStep(CustomStepScope<Solution_> stepScope, CustomPhaseCommand<Solution_> customPhaseCommand) {
//        InnerScoreDirector<Solution_, ?> scoreDirector = stepScope.getScoreDirector();
//        customPhaseCommand.changeWorkingSolution(scoreDirector);
//        calculateWorkingStepScore(stepScope, customPhaseCommand);
//        solver.getBestSolutionRecaller().processWorkingSolutionDuringStep(stepScope);
//    }

    public void stepEnded(CustomStepScope<Solution_> stepScope) {
        super.stepEnded(stepScope);
        boolean bestScoreImproved = stepScope.getBestScoreImproved();
        if (!bestScoreImproved) {
            solver.getBestSolutionRecaller().updateBestSolutionAndFire(stepScope.getPhaseScope().getSolverScope());
        }
        CustomPhaseScope<Solution_> phaseScope = stepScope.getPhaseScope();
        if (logger.isDebugEnabled()) {
            logger.debug("{}    Custom step ({}), time spent ({}), score ({}), {} best score ({}).",
                    logIndentation,
                    stepScope.getStepIndex(),
                    phaseScope.calculateSolverTimeMillisSpentUpToNow(),
                    stepScope.getScore(),
                    bestScoreImproved ? "new" : "   ",
                    phaseScope.getBestScore());
        }
    }

    public void phaseEnded(CustomPhaseScope<Solution_> phaseScope) {
        super.phaseEnded(phaseScope);
        phaseScope.endingNow();

        moveSelector.phaseStarted(phaseScope);

        logger.info("{}Custom phase ({}) ended: time spent ({}), best score ({}),"
                + " score calculation speed ({}/sec), step total ({}).",
                logIndentation,
                phaseIndex,
                phaseScope.calculateSolverTimeMillisSpentUpToNow(),
                phaseScope.getBestScore(),
                phaseScope.getPhaseScoreCalculationSpeed(),
                phaseScope.getNextStepIndex());
    }

}
