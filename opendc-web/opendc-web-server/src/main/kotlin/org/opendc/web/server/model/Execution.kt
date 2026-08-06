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
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.intellij.lang.annotations.Language
import org.opendc.web.dispatcher.ExitReason
import java.time.Instant
import java.util.UUID

/** Lifecycle of one dispatched execution and of each unit inside it. */
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

/**
 * One `(scenario, seed)` run of a submitted experiment.
 *
 * Written at submit and never removed while the experiment lives. A unit outlives the executions
 * that carry it: retrying puts it in a new one without losing where it has already been.
 */
@Entity
@Table(name = "run_units")
class RunUnit : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var experiment: Experiment

    var scenarioIndex: Int = 0

    var seed: Long = 0

    @Enumerated(EnumType.STRING)
    var state: ExecutionState = ExecutionState.QUEUED

    var totalTasks: Int = 0

    var completedTasks: Int = 0

    companion object : PanacheCompanion<RunUnit> {
        @Language("JPAQL")
        private const val BY_EXPERIMENT = """
            SELECT u FROM RunUnit u
            WHERE u.experiment.id = ?1
            ORDER BY u.scenarioIndex, u.seed
        """

        @Language("JPAQL")
        private const val QUEUED_WORK = """
            SELECT u FROM RunUnit u
            WHERE u.state = org.opendc.web.server.model.ExecutionState.QUEUED
            ORDER BY u.experiment.id, u.scenarioIndex, u.seed
        """

        fun findByExperiment(experimentId: Long): List<RunUnit> = list(BY_EXPERIMENT, experimentId)

        /** Everything submitted that no execution is carrying yet. */
        fun findQueued(): List<RunUnit> = list(QUEUED_WORK)
    }
}

/**
 * One attempt at running a bag of units together.
 *
 * [publicId] is what the platform is asked about, so a restarted server can cancel and reconcile
 * work it did not start. [state] names the variant: a running execution has no exit, a terminal one
 * has all of [exitCode], [exitReason] and [finishedAt].
 */
@Entity
@Table(name = "executions")
class Execution : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    var publicId: UUID = UUID.randomUUID()

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var experiment: Experiment

    @ManyToMany
    @JoinTable(
        name = "execution_units",
        joinColumns = [JoinColumn(name = "execution_id")],
        inverseJoinColumns = [JoinColumn(name = "unit_id")],
    )
    var units: MutableList<RunUnit> = mutableListOf()

    @Enumerated(EnumType.STRING)
    var state: ExecutionState = ExecutionState.QUEUED

    var attempt: Int = 1

    lateinit var dispatcher: String

    var parallelism: Int = 1

    var heapMb: Int = 0

    var memoryRequestMb: Int = 0

    var timeLimitSeconds: Int = 0

    var estimatedMakespanSeconds: Double = 0.0

    lateinit var createdAt: Instant

    var startedAt: Instant? = null

    var finishedAt: Instant? = null

    var exitCode: Int? = null

    @Enumerated(EnumType.STRING)
    var exitReason: ExitReason? = null

    var exitMessage: String? = null

    companion object : PanacheCompanion<Execution> {
        // The units are fetched with the execution because every caller reads across the pair:
        // settling writes their states, and the status fold reads their progress beside the
        // attempt and exit of whichever execution carried them.
        @Language("JPAQL")
        private const val BY_EXPERIMENT = """
            SELECT DISTINCT e FROM Execution e
            LEFT JOIN FETCH e.units
            WHERE e.experiment.id = ?1
            ORDER BY e.createdAt
        """

        @Language("JPAQL")
        private const val LIVE = """
            SELECT DISTINCT e FROM Execution e
            LEFT JOIN FETCH e.units
            WHERE e.state IN (
                org.opendc.web.server.model.ExecutionState.QUEUED,
                org.opendc.web.server.model.ExecutionState.RUNNING
            )
            ORDER BY e.createdAt
        """

        fun findByExperiment(experimentId: Long): List<Execution> = list(BY_EXPERIMENT, experimentId)

        /** Executions this server believes a platform is still holding. */
        fun findLive(): List<Execution> = list(LIVE)

        fun findByPublicId(publicId: UUID): Execution? = find("publicId = ?1", publicId).firstResult()
    }
}
