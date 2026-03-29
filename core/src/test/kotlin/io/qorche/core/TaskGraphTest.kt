package io.qorche.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskGraphTest {

    @Test
    fun `linear chain executes in order`() {
        val graph = TaskGraph(listOf(
            TaskDefinition("a", "first"),
            TaskDefinition("b", "second", dependsOn = listOf("a")),
            TaskDefinition("c", "third", dependsOn = listOf("b"))
        ))

        val order = graph.topologicalSort()
        assertTrue(order.indexOf("a") < order.indexOf("b"))
        assertTrue(order.indexOf("b") < order.indexOf("c"))
    }

    @Test
    fun `diamond dependency resolves correctly`() {
        val graph = TaskGraph(listOf(
            TaskDefinition("a", "root"),
            TaskDefinition("b", "left", dependsOn = listOf("a")),
            TaskDefinition("c", "right", dependsOn = listOf("a")),
            TaskDefinition("d", "join", dependsOn = listOf("b", "c"))
        ))

        val order = graph.topologicalSort()
        assertTrue(order.indexOf("a") < order.indexOf("b"))
        assertTrue(order.indexOf("a") < order.indexOf("c"))
        assertTrue(order.indexOf("b") < order.indexOf("d"))
        assertTrue(order.indexOf("c") < order.indexOf("d"))
    }

    @Test
    fun `cycle detection throws`() {
        assertFailsWith<CycleDetectedException> {
            TaskGraph(listOf(
                TaskDefinition("a", "first", dependsOn = listOf("b")),
                TaskDefinition("b", "second", dependsOn = listOf("a"))
            ))
        }
    }

    @Test
    fun `readyTasks returns only tasks with completed dependencies`() {
        val graph = TaskGraph(listOf(
            TaskDefinition("a", "first"),
            TaskDefinition("b", "second", dependsOn = listOf("a"))
        ))

        val ready = graph.readyTasks()
        assertEquals(1, ready.size)
        assertEquals("a", ready[0].definition.id)
    }

    @Test
    fun `parallelGroups identifies independent tasks`() {
        val graph = TaskGraph(listOf(
            TaskDefinition("a", "root"),
            TaskDefinition("b", "left", dependsOn = listOf("a")),
            TaskDefinition("c", "right", dependsOn = listOf("a")),
            TaskDefinition("d", "join", dependsOn = listOf("b", "c"))
        ))

        val groups = graph.parallelGroups()
        assertEquals(3, groups.size)
        assertEquals(listOf("a"), groups[0])
        assertTrue(groups[1].containsAll(listOf("b", "c")))
        assertEquals(listOf("d"), groups[2])
    }

    @Test
    fun `unknown dependency throws`() {
        assertFailsWith<IllegalArgumentException> {
            TaskGraph(listOf(
                TaskDefinition("a", "first", dependsOn = listOf("nonexistent"))
            ))
        }
    }
}
