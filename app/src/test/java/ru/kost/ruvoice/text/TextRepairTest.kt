package ru.kost.ruvoice.text

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.kost.ruvoice.TestData
import java.io.File

class TextRepairTest {
    private var previousMorph: Morph? = null

    @Before fun loadMorphology() {
        previousMorph = Normalizer.morph
        Normalizer.morph = Morph.open(File(TestData.root(), "app/src/main/assets/morph.bin"))
    }

    @After fun restoreMorphology() {
        Normalizer.morph = previousMorph
    }

    @Test fun repairsReviewedHyphenSplits() {
        assertEquals("техниками", TextRepair.repair("техника- ми", RuLayer))
        assertEquals("информации", TextRepair.repair("ин-формации", RuLayer))
        assertEquals("столько", TextRepair.repair("столь-ко", RuLayer))
        assertEquals("запланированного", TextRepair.repair("за-планированного", RuLayer))
        // Общий путь по AOT-морфологии, не только вручную подтверждённые исключения.
        assertEquals("службы", TextRepair.repair("служ-бы", RuLayer))
    }

    @Test fun repairsReviewedSpaceSplit() {
        assertEquals("энергоинформационным", TextRepair.repair("энергоинформацион ным", RuLayer))
    }

    @Test fun preservesRealHyphenatedWords() {
        for (word in listOf("кто-то", "по-прежнему", "из-за", "во-первых", "кое-кто")) {
            assertEquals(word, TextRepair.repair(word, RuLayer))
        }
    }

    @Test fun preservesUnknownOrAmbiguousSplits() {
        assertEquals("дом- книга", TextRepair.repair("дом- книга", RuLayer))
        assertEquals("абракадабра-слово", TextRepair.repair("абракадабра-слово", RuLayer))
        assertEquals("стол и книга", TextRepair.repair("стол и книга", RuLayer))
    }

    @Test fun keepsCapitalizationAndPunctuation() {
        assertEquals("Информации, столько!", TextRepair.repair("Ин-формации, столь-ко!", RuLayer))
        assertEquals("ИНФОРМАЦИИ", TextRepair.repair("ИН-ФОРМАЦИИ", RuLayer))
    }

    @Test fun doesNotTouchOtherLanguages() {
        assertEquals("техника- ми", TextRepair.repair("техника- ми", BeLayer))
        assertEquals("ін-фармацыі", TextRepair.repair("ін-фармацыі", BeLayer))
    }

    @Test fun repairedTextKeepsOriginalHighlightOffsets() {
        val mapped = TextRepair.mapped("техника- ми", RuLayer)
        assertEquals("техниками", mapped.text)
        assertEquals(0, mapped.orig(0))
        assertEquals(9, mapped.orig(7))
        assertEquals(10, mapped.orig(8))
    }

}
