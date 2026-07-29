package com.example.ilink.application.executive;

/** 第一层确定性验证，避免空结果被直接标记完成。 */
public final class DefaultResultVerifier implements ResultVerifier {
    @Override
    public ExecutionOutcome verify(ExecutiveStep step, ExecutionOutcome outcome) {
        if (outcome.type() != ExecutionOutcome.Type.SUCCESS) return outcome;
        String output = outcome.output().trim();
        return switch (step.verificationRule()) {
            case "none" -> outcome;
            case "contains_url" -> output.matches("(?s).*https?://\\S+.*")
                    ? outcome : ExecutionOutcome.retry("结果中没有可验证的来源链接");
            case "research_report" -> validResearchReport(output)
                    ? outcome : ExecutionOutcome.retry("调研报告缺少有效分析或来源链接");
            case "job_candidates" -> validJobCandidates(output)
                    ? outcome : ExecutionOutcome.retry("搜索结果中没有有效招聘岗位");
            case "job_report" -> validJobReport(output)
                    ? outcome : ExecutionOutcome.retry("岗位报告缺少岗位详情或来源链接");
            default -> output.isBlank() ? ExecutionOutcome.retry("工具返回了空结果") : outcome;
        };
    }

    private boolean validResearchReport(String output) {
        String trimmed = output.trim();
        return trimmed.length() >= 180
                && trimmed.matches("(?s).*https?://\\S+.*")
                && !trimmed.startsWith("{")
                && !trimmed.contains("\"results\":[{");
    }

    private boolean validJobCandidates(String output) {
        String normalized = output.toLowerCase();
        return normalized.contains("\"jobs\":[{")
                && normalized.matches("(?s).*https?://\\S+.*")
                && (normalized.contains("实习") || normalized.contains("招聘") || normalized.contains("职位"))
                && (normalized.contains("java") || normalized.contains("后端"));
    }

    private boolean validJobReport(String output) {
        String normalized = output.trim();
        return normalized.length() >= 220
                && normalized.matches("(?s).*https?://\\S+.*")
                && normalized.contains("岗位详情")
                && normalized.contains("工作内容与要求")
                && normalized.contains("薪资：")
                && !normalized.startsWith("{")
                && !normalized.contains("\"jobs\":[{");
    }
}
