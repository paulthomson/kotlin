/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.test.cases.symbols

import org.jetbrains.kotlin.analysis.api.fir.symbols.pointers.KaFirPsiSymbolPointer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForDebug
import org.jetbrains.kotlin.analysis.api.renderer.render
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.api.symbols.pointers.restoreSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiBasedTest
import org.jetbrains.kotlin.analysis.test.framework.projectStructure.KtTestModule
import org.jetbrains.kotlin.analysis.utils.printer.prettyPrint
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions

/**
 * Checks that non-PSI symbol pointers of *different* non-local callable declarations in the main file
 * are distinguishable:
 *
 * - [KaSymbolPointer.pointsToTheSameSymbolAs] must return `false` for pointers to different declarations, and
 * - [KaSymbolPointer.restoreSymbol] must restore the same declaration the pointer was created for.
 *
 * PSI-based pointers are disabled on purpose, as they identify declarations by their PSI and therefore
 * hide problems in signature-based pointers (e.g., `KaFirTopLevelFunctionSymbolPointer`).
 */
abstract class AbstractSymbolPointerEqualityTest : AbstractAnalysisApiBasedTest() {
    override fun doTestByMainFile(mainFile: KtFile, mainModule: KtTestModule, testServices: TestServices) {
        val declarations = mainFile.collectDescendantsOfType<KtCallableDeclaration> {
            (it is KtNamedFunction && !it.isLocal) || (it is KtProperty && !it.isLocal)
        }

        val renderer = KaDeclarationRendererForDebug.WITH_QUALIFIED_NAMES

        val [rendered, pointers] = analyzeForTest(mainFile) {
            val symbols = declarations.map { it.symbol }
            val rendered = symbols.map { it.render(renderer) }
            val pointers: List<KaSymbolPointer<KaSymbol>> = symbols.map { symbol ->
                // Source symbols backed by PSI create PSI-based pointers directly, so the backing PSI has to be dropped as well.
                symbol.dropBackingPsi()
                KaFirPsiSymbolPointer.withDisabledPsiBasedPointers(disable = true) { symbol.createPointer() }
            }
            rendered to pointers
        }

        configurator.doGlobalModuleStateModification(mainFile.project)

        val restoredIndices: List<Int?> = analyzeForTest(mainFile) {
            pointers.map { pointer ->
                val restoredPsi = pointer.restoreSymbol()?.psi ?: return@map null
                declarations.indexOf(restoredPsi).takeIf { it >= 0 }
            }
        }

        val collisions = mutableListOf<Pair<Int, Int>>()
        val wrongRestorations = mutableListOf<Int>()

        val actual = prettyPrint {
            appendLine("Declarations:")
            withIndent {
                rendered.forEachIndexed { index, text ->
                    appendLine("[$index] ${text.replace("\n", " ")} (${pointers[index]::class.simpleName})")
                }
            }
            appendLine()

            appendLine("pointsToTheSameSymbolAs for pointers to different declarations:")
            withIndent {
                for (i in pointers.indices) {
                    for (j in i + 1..<pointers.size) {
                        val same = pointers[i].pointsToTheSameSymbolAs(pointers[j])
                        if (same) collisions += i to j
                        appendLine("[$i] vs [$j]: $same" + if (same) " <-- COLLISION" else "")
                    }
                }
            }
            appendLine()

            appendLine("Restored:")
            withIndent {
                restoredIndices.forEachIndexed { index, restoredIndex ->
                    when (restoredIndex) {
                        index -> appendLine("[$index] -> [$restoredIndex]")
                        null -> {
                            wrongRestorations += index
                            appendLine("[$index] -> <NOT RESTORED>")
                        }
                        else -> {
                            wrongRestorations += index
                            appendLine("[$index] -> [$restoredIndex] <-- WRONG DECLARATION")
                        }
                    }
                }
            }
        }

        testServices.assertions.assertEqualsToTestOutputFile(actual)

        testServices.assertions.assertTrue(collisions.isEmpty()) {
            "Pointers to different declarations are considered equal by `pointsToTheSameSymbolAs`: " +
                    collisions.joinToString { [i, j] -> "[$i] '${rendered[i]}' and [$j] '${rendered[j]}'" }
        }

        testServices.assertions.assertTrue(wrongRestorations.isEmpty()) {
            "Pointers restored to a wrong declaration: " +
                    wrongRestorations.joinToString { "[$it] '${rendered[it]}' -> ${restoredIndices[it]}" }
        }
    }
}
