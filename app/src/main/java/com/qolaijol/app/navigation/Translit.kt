// Translit - Cyrillic place names and addresses in Latin letters, for the English voice
//
// 2GIS returns names like "Мир здоровья" and addresses like "улица Аз Наурыз, 32"; an English
// text-to-speech voice can't read Cyrillic. Russian and Kazakh letters are spelled the way they
// sound, and the common street words become English ("Az Nauryz Street, 32"). Plain Kotlin, unit
// tested on the JVM.

package com.qolaijol.app.navigation

object Translit {

  private val letters: Map<Char, String> =
      mapOf(
          'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
          'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
          'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
          'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "",
          'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
          // Kazakh.
          'ә' to "a", 'ғ' to "gh", 'қ' to "q", 'ң' to "ng", 'ө' to "o", 'ұ' to "u", 'ү' to "u",
          'һ' to "h", 'і' to "i",
      )

  // Street type word -> English word put after the name.
  private val streetTypes: List<Pair<Regex, String>> =
      listOf(
          Regex("^(?:улица|ул\\.)\\s+(.+)$", RegexOption.IGNORE_CASE) to "Street",
          Regex("^(?:проспект|пр-т|пр\\.)\\s+(.+)$", RegexOption.IGNORE_CASE) to "Avenue",
          Regex("^(?:бульвар|б-р)\\s+(.+)$", RegexOption.IGNORE_CASE) to "Boulevard",
          Regex("^(?:переулок|пер\\.)\\s+(.+)$", RegexOption.IGNORE_CASE) to "Lane",
          Regex("^(?:площадь|пл\\.)\\s+(.+)$", RegexOption.IGNORE_CASE) to "Square",
          Regex("^(?:шоссе)\\s+(.+)$", RegexOption.IGNORE_CASE) to "Highway",
      )

  /** "Мир здоровья" -> "Mir zdorovya". Text without Cyrillic is returned unchanged. */
  fun toLatin(text: String): String {
    val out = StringBuilder(text.length)
    for ((i, c) in text.withIndex()) {
      val latin = letters[c.lowercaseChar()]
      when {
        latin == null -> out.append(c)
        !c.isUpperCase() -> out.append(latin)
        // All-caps words ("АРС" -> "ARS") stay all caps; otherwise only the first letter is.
        text.getOrNull(i - 1)?.isUpperCase() == true ||
            text.getOrNull(i + 1)?.isUpperCase() == true -> out.append(latin.uppercase())
        else -> out.append(latin.replaceFirstChar { it.uppercase() })
      }
    }
    return out.toString()
  }

  private val ordinalSuffix = Regex("(\\d+)-(?:й|я|е|ый|ой|ая)(?!\\p{L})")
  private val microdistrict =
      Regex("(?<!\\p{L})(?:микрорайон|мкр\\.?)(?!\\p{L})", RegexOption.IGNORE_CASE)

  /** "улица Аз Наурыз, 32" -> "Az Nauryz Street, 32"; "12-й микрорайон" -> "12 microdistrict". */
  fun address(text: String): String =
      text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { part ->
        val street =
            streetTypes.firstNotNullOfOrNull { (regex, english) ->
              regex.find(part)?.let { "${it.groupValues[1]} $english" }
            }
        toLatin(
            (street ?: part).replace(ordinalSuffix, "$1").replace(microdistrict, "microdistrict"),
        )
      }
}
