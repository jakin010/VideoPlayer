package co.liebi.videoplayer.ui

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every translation must have the same strings and plurals as the English `values/strings.xml`, with the same
 * placeholders. Runs on the JVM, where the resource files can be read from the source tree.
 */
class TranslationsTest {

    private val resources = File("src/commonMain/composeResources")
    private val english = parse(File(resources, "values/strings.xml"))
    private val translations = resources.listFiles { file -> file.name.startsWith("values-") }.orEmpty()
        .associate { it.name.removePrefix("values-") to parse(File(it, "strings.xml")) }

    @Test
    fun everyRequestedLanguageIsPresent() {
        val expected = setOf(
            "nl", "fr", "es", "de", "it", "zh", "hi", "ar", "pt", "bn", "ru", "ur", "id", "ja", "tr", "pl", "is", "nb",
            "sv", "fi", "am",
        )
        assertTrue(translations.keys.containsAll(expected), "missing: ${expected - translations.keys}")
    }

    @Test
    fun everyTranslationHasEveryStringAndPlural() {
        translations.forEach { (language, strings) ->
            assertEquals(english.strings.keys, strings.strings.keys, "strings in values-$language")
            assertEquals(english.plurals.keys, strings.plurals.keys, "plurals in values-$language")
        }
    }

    @Test
    fun placeholdersMatchEnglish() {
        translations.forEach { (language, strings) ->
            english.strings.forEach { (name, text) ->
                assertEquals(placeholders(text), placeholders(strings.strings.getValue(name)), "$name in values-$language")
            }
            english.plurals.forEach { (name, forms) ->
                val allowed = placeholders(forms.getValue("other"))
                strings.plurals.getValue(name).forEach { (quantity, text) ->
                    // A form may spell out its number ("one hour"), but must not need anything English doesn't pass.
                    assertTrue(placeholders(text).all { it in allowed }, "$name/$quantity in values-$language")
                }
            }
        }
    }

    @Test
    fun everyPluralHasTheOtherForm() {
        translations.forEach { (language, strings) ->
            strings.plurals.forEach { (name, forms) -> assertTrue("other" in forms, "$name in values-$language has no 'other'") }
        }
    }

    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun parse(file: File): Strings {
        if (!file.exists()) fail("$file is missing")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string").elements().associate { it.getAttribute("name") to it.textContent }
        val plurals = document.getElementsByTagName("plurals").elements().associate { plural ->
            plural.getAttribute("name") to plural.getElementsByTagName("item").elements()
                .associate { it.getAttribute("quantity") to it.textContent }
        }
        return Strings(strings, plurals)
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[ds]""").findAll(text).map { it.value }.toSet()
}
