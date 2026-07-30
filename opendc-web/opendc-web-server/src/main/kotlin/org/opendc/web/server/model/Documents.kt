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
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * A project-scoped document row. Both concrete documents store their canonical body as jsonb next
 * to a content hash; the columns for those differ per subclass.
 *
 * [id] never leaves the server: it keeps foreign keys narrow, while [publicId] is what the API and
 * its URLs carry, so identifiers cannot be walked or counted from outside.
 */
@MappedSuperclass
abstract class SpecDocument : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    var publicId: UUID = UUID.randomUUID()

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    lateinit var project: Project

    lateinit var name: String

    lateinit var createdAt: Instant

    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "topology_templates")
class TopologyTemplate : SpecDocument() {
    @JdbcTypeCode(SqlTypes.JSON)
    lateinit var topology: String

    lateinit var topologyHash: String

    @JdbcTypeCode(SqlTypes.JSON)
    var layout: String? = null

    companion object : PanacheCompanion<TopologyTemplate> {
        fun findByProject(projectId: Long): List<TopologyTemplate> = list("project.id = ?1 order by createdAt", projectId)

        fun findByPublicId(publicId: UUID): TopologyTemplate? = find("publicId = ?1", publicId).firstResult()
    }
}

@Entity
@Table(name = "experiments")
class Experiment : SpecDocument() {
    @JdbcTypeCode(SqlTypes.JSON)
    lateinit var spec: String

    lateinit var specHash: String

    var scenarioCount: Int = 0

    var estimatedSimulationSeconds: Double = 0.0

    var estimatedBudgetSeconds: Double = 0.0

    var submittedAt: Instant? = null

    val isDraft: Boolean
        get() = submittedAt == null

    companion object : PanacheCompanion<Experiment> {
        fun findByProject(projectId: Long): List<Experiment> = list("project.id = ?1 order by createdAt", projectId)

        fun findByPublicId(publicId: UUID): Experiment? = find("publicId = ?1", publicId).firstResult()
    }
}
