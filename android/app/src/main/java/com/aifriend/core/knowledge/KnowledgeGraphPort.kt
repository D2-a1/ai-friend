package com.aifriend.core.knowledge

/**
 * 可重建知识图谱节点。
 *
 * @property nodeType 节点类型
 * @property businessKey 去标识化业务键
 * @property properties 不含消息正文、音频、token、openId 的属性
 */
data class GraphNode(
    val nodeType: String,
    val businessKey: String,
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(nodeType.isNotBlank()) { "nodeType must not be blank" }
        require(businessKey.isNotBlank()) { "businessKey must not be blank" }
    }
}

/**
 * 可重建知识图谱关系。
 */
data class GraphEdge(
    val relationType: String,
    val fromBusinessKey: String,
    val toBusinessKey: String,
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(relationType.isNotBlank()) { "relationType must not be blank" }
        require(fromBusinessKey.isNotBlank()) { "fromBusinessKey must not be blank" }
        require(toBusinessKey.isNotBlank()) { "toBusinessKey must not be blank" }
    }
}

/**
 * 知识图谱投影端口。
 *
 * MySQL 是权限和业务真相；当前不提供实现，也不部署图数据库。
 */
interface KnowledgeGraphPort {
    /** 投影一批去标识化节点和关系。 */
    suspend fun project(nodes: List<GraphNode>, edges: List<GraphEdge>)
}
