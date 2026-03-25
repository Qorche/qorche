package io.qorche.core

class CycleDetectedException(val cycle: List<String>) :
    IllegalArgumentException("Cycle detected in task graph: ${cycle.joinToString(" -> ")}")

class TaskGraph(definitions: List<TaskDefinition>) {

    private val nodes: Map<String, TaskNode> =
        definitions.associate { it.id to TaskNode(it) }

    private val adjacency: Map<String, List<String>> =
        definitions.associate { it.id to it.dependsOn }

    init {
        for (def in definitions) {
            for (dep in def.dependsOn) {
                require(dep in nodes) { "Task '${def.id}' depends on unknown task '$dep'" }
            }
        }
        detectCycles()
    }

    fun topologicalSort(): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            visited.add(id)
            for (dep in adjacency[id].orEmpty()) {
                visit(dep)
            }
            result.add(id)
        }

        for (id in nodes.keys.sorted()) {
            visit(id)
        }
        return result
    }

    fun readyTasks(): List<TaskNode> =
        nodes.values.filter { node ->
            node.status == TaskStatus.PENDING &&
                adjacency[node.definition.id].orEmpty().all { depId ->
                    nodes[depId]?.status == TaskStatus.COMPLETED
                }
        }

    fun parallelGroups(): List<List<String>> {
        val remaining = nodes.keys.toMutableSet()
        val completed = mutableSetOf<String>()
        val groups = mutableListOf<List<String>>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { id ->
                adjacency[id].orEmpty().all { it in completed }
            }
            if (ready.isEmpty()) break
            groups.add(ready)
            remaining.removeAll(ready.toSet())
            completed.addAll(ready)
        }
        return groups
    }

    operator fun get(id: String): TaskNode? = nodes[id]

    fun allNodes(): Collection<TaskNode> = nodes.values

    private fun detectCycles() {
        val white = nodes.keys.toMutableSet() // unvisited
        val gray = mutableSetOf<String>()      // in progress
        val black = mutableSetOf<String>()     // finished

        fun dfs(id: String, path: MutableList<String>) {
            white.remove(id)
            gray.add(id)
            path.add(id)

            for (dep in adjacency[id].orEmpty()) {
                if (dep in gray) {
                    val cycleStart = path.indexOf(dep)
                    throw CycleDetectedException(path.subList(cycleStart, path.size) + dep)
                }
                if (dep in white) {
                    dfs(dep, path)
                }
            }

            path.removeAt(path.lastIndex)
            gray.remove(id)
            black.add(id)
        }

        for (id in nodes.keys.sorted()) {
            if (id in white) {
                dfs(id, mutableListOf())
            }
        }
    }
}
