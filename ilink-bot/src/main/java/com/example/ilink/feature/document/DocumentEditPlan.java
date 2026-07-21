package com.example.ilink.feature.document;

import java.util.List;

public record DocumentEditPlan(List<DocumentService.TextEdit> edits) {
}
