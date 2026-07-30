package com.example.ilink.application.executive;

/** 工具执行风险；外部写入及不可逆操作必须审批。 */
public enum RiskLevel {
    READ_ONLY,
    LOCAL_WRITE,
    DATA_EGRESS,
    EXTERNAL_WRITE,
    IRREVERSIBLE;

    public boolean requiresApproval() {
        return this == DATA_EGRESS || this == EXTERNAL_WRITE || this == IRREVERSIBLE;
    }
}
