package com.jobagent.model;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Writer Agent 的中间输出——仅供 Phase 2 使用，不对外暴露。
 *
 * <p>替代已删除的 RewriteReport。仅包含改写后的要点和原因，
 * 不包含评分或审查信息——这些由 OptimizationReport 统一组装。
 */
public record WriterDraft(
        @Description("使用 STAR 法则重写的项目经历要点")
        List<String> rewrittenBulletPoints,

        @Description("各条改写的依据说明")
        List<String> optimizationReasons
) {}