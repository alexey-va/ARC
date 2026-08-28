package ru.arc.investigation

import java.nio.file.Files

internal val bundledInvestigationCatalogForTest: InvestigationStoryCatalog by lazy {
    InvestigationStoryCatalog.load(Files.createTempDirectory("arc-investigation-catalog-"))
}

internal fun investigationCaseGeneratorForTest(): InvestigationCaseGenerator =
    InvestigationCaseGenerator(bundledInvestigationCatalogForTest)
