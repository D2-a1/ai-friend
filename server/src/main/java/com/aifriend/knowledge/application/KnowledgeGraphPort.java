package com.aifriend.knowledge.application;

import java.util.List;

import com.aifriend.knowledge.domain.GraphEdge;
import com.aifriend.knowledge.domain.GraphNode;

/**
 * 知识图谱投影端口。
 *
 * <p>MySQL 始终是业务与权限真相；图谱只能作为可重建投影辅助检索和理解。
 * 当前不提供默认适配器，也不部署图数据库。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeGraphPort {

    /**
     * 投影一批去标识化节点和关系。
     *
     * @param nodes 节点集合
     * @param edges 关系集合
     */
    void project(List<GraphNode> nodes, List<GraphEdge> edges);
}
