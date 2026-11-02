package me.shirazu.dshmizar.ui

import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelOption
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.ProviderGroup
import me.shirazu.mizar.kit.ReasoningEffortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model pill's text, as both screens spell it.
 *
 * This is a characterisation test: every case below is what the composer pill and
 * the home pill printed *before* the two copies were merged, so a difference after
 * the merge is a regression rather than a matter of taste.
 */
class ModelLabelTest {

    private val catalog = ModelCatalog(
        providers = listOf(
            ProviderGroup(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    ModelOption(
                        id = "deepseek-v4",
                        name = "V4",
                        efforts = listOf(
                            ReasoningEffortOption(id = "high", name = "High"),
                            ReasoningEffortOption(id = "max", name = "Maximum"),
                        ),
                        defaultEffort = "high",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `no selection means no label`() {
        assertNull(modelLabel(null, catalog))
        assertNull(modelLabel(null, null))
    }

    @Test
    fun `a model with no effort is just the model`() {
        assertEquals("deepseek-v4", modelLabel(ModelSelection("deepseek", "deepseek-v4"), catalog))
        assertEquals("deepseek-v4", modelLabel(ModelSelection("deepseek", "deepseek-v4"), null))
    }

    @Test
    fun `an effort takes the name the catalogue gives it`() {
        assertEquals(
            "deepseek-v4 · High",
            modelLabel(ModelSelection("deepseek", "deepseek-v4", "high"), catalog),
        )
        assertEquals(
            "deepseek-v4 · Maximum",
            modelLabel(ModelSelection("deepseek", "deepseek-v4", "max"), catalog),
        )
    }

    @Test
    fun `an effort the catalogue does not know is capitalised`() {
        // The wire value need not read like a word; the fallback makes it one.
        assertEquals(
            "deepseek-v4 · Medium",
            modelLabel(ModelSelection("deepseek", "deepseek-v4", "medium"), catalog),
        )
        assertEquals(
            "deepseek-v4 · Low",
            modelLabel(ModelSelection("deepseek", "deepseek-v4", "low"), null),
        )
    }

    @Test
    fun `a catalogue that does not list this model falls back too`() {
        assertEquals(
            "other-model · High",
            modelLabel(ModelSelection("someone-else", "other-model", "high"), catalog),
        )
    }

    /**
     * Pinned as-is, not endorsed: an empty effort id still joins, leaving a
     * trailing separator. The host has never been seen sending one, and changing
     * it here would be a behaviour change smuggled into a de-duplication.
     */
    @Test
    fun `an empty effort id leaves a trailing separator, as it always did`() {
        assertEquals("deepseek-v4 · ", modelLabel(ModelSelection("deepseek", "deepseek-v4", ""), catalog))
    }
}
