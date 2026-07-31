/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.server.model

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanion
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.intellij.lang.annotations.Language
import java.time.Instant

/** Lifecycle of one dispatched execution (a bag) and of each unit inside it. */
enum class ExecutionState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** Platform-observed classification of a terminal execution, mirrored by the frontend. */
enum class ExitReason {
    OK,
    SIMULATION_ERROR,
    INVALID_SPEC,
    OOM,
    TIMEOUT,
    WALLTIME,
    CANCELLED,
    UNKNOWN,
}

@Entity
@Table(name = "bag_executions")
class BagExecution : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var experiment: Experiment

    var bagIndex: Int = 0

    @Enumerated(EnumType.STRING)
    var state: ExecutionState = ExecutionState.QUEUED

    var attempt: Int = 1

    var dispatcher: String? = null

    var parallelism: Int = 1

    var estimatedSeconds: Double = 0.0

    var memoryLimitMb: Int? = null

    var timeLimitSeconds: Int? = null

    var startedAt: Instant? = null

    var finishedAt: Instant? = null

    var exitCode: Int? = null

    @Enumerated(EnumType.STRING)
    var exitReason: ExitReason? = null

    var exitMessage: String? = null

    companion object : PanacheCompanion<BagExecution> {
        fun findByExperiment(experimentId: Long): List<BagExecution> = list("experiment.id = ?1 order by bagIndex", experimentId)
    }
}

@Entity
@Table(name = "run_units")
class RunUnit : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var bag: BagExecution

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var experiment: Experiment

    var scenarioIndex: Int = 0

    var seed: Long = 0

    @Enumerated(EnumType.STRING)
    var state: ExecutionState = ExecutionState.QUEUED

    var totalTasks: Int = 0

    var completedTasks: Int = 0

    var resultLocation: String? = null

    companion object : PanacheCompanion<RunUnit> {
        // The bag is fetched with its units because folding a scenario's status reads the attempt
        // and the exit information, and only the bag carries either. Left lazy, that fold costs a
        // query per bag on an endpoint a live experiment polls every couple of seconds.
        @Language("JPAQL")
        private const val BY_EXPERIMENT = """
            SELECT u FROM RunUnit u
            JOIN FETCH u.bag
            WHERE u.experiment.id = ?1
            ORDER BY u.scenarioIndex, u.seed
        """

        fun findByExperiment(experimentId: Long): List<RunUnit> = list(BY_EXPERIMENT, experimentId)
    }
}
