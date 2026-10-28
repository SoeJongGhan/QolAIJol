// PlaceQuery - English place words -> 2GIS search text
//
// The user speaks English, but the 2GIS catalog in Kazakhstan only understands Russian (checked
// 2026-10-01: "pharmacy" near Aktobe -> 404, "аптека" -> results; locale en_KZ is rejected).
// Common place types are translated here, offline; NavigationManager asks the LLM for anything
// else. Plain Kotlin, unit tested on the JVM.

package com.qolaijol.app.navigation

object PlaceQuery {

  private val terms: Map<String, String> =
      mapOf(
          "pharmacy" to "аптека",
          "drugstore" to "аптека",
          "chemist" to "аптека",
          "bus stop" to "остановка",
          "stop" to "остановка",
          "bus station" to "автовокзал",
          "train station" to "вокзал",
          "railway station" to "вокзал",
          "station" to "вокзал",
          "atm" to "банкомат",
          "cash machine" to "банкомат",
          "bank" to "банк",
          "hospital" to "больница",
          "clinic" to "поликлиника",
          "polyclinic" to "поликлиника",
          "doctor" to "поликлиника",
          "dentist" to "стоматология",
          "emergency room" to "приемное отделение",
          "shop" to "магазин",
          "store" to "магазин",
          "grocery" to "продукты",
          "grocery store" to "продукты",
          "supermarket" to "супермаркет",
          "market" to "рынок",
          "mall" to "торговый центр",
          "shopping mall" to "торговый центр",
          "shopping center" to "торговый центр",
          "bakery" to "пекарня",
          "cafe" to "кафе",
          "coffee" to "кофейня",
          "coffee shop" to "кофейня",
          "restaurant" to "ресторан",
          "canteen" to "столовая",
          "fast food" to "фастфуд",
          "toilet" to "туалет",
          "restroom" to "туалет",
          "bathroom" to "туалет",
          "school" to "школа",
          "university" to "университет",
          "college" to "колледж",
          "kindergarten" to "детский сад",
          "library" to "библиотека",
          "park" to "парк",
          "hotel" to "гостиница",
          "post office" to "почта",
          "post" to "почта",
          "police" to "полиция",
          "police station" to "полиция",
          "mosque" to "мечеть",
          "church" to "церковь",
          "gas station" to "АЗС",
          "petrol station" to "АЗС",
          "parking" to "парковка",
          "parking lot" to "парковка",
          "airport" to "аэропорт",
          "cinema" to "кинотеатр",
          "movie theater" to "кинотеатр",
          "theater" to "театр",
          "museum" to "музей",
          "gym" to "фитнес",
          "hairdresser" to "парикмахерская",
          "barber" to "парикмахерская",
          "optician" to "оптика",
          "bookstore" to "книжный магазин",
          "phone store" to "салон связи",
          "vet" to "ветеринарная клиника",
          "veterinarian" to "ветеринарная клиника",
          "laundry" to "прачечная",
          "taxi" to "такси",
      )

  private val leadingWords = Regex("^(?:(?:the|a|an|some|any|nearest|closest|near)\\s+)+")

  /** 2GIS search text for a common English place word ("the nearest pharmacies"), or null. */
  fun translate(english: String): String? {
    val n = english.lowercase().trim().replace(leadingWords, "").trim()
    if (n.isEmpty()) return null
    terms[n]?.let { return it }
    val singular =
        when {
          n.endsWith("ies") -> n.dropLast(3) + "y"
          n.endsWith("ches") || n.endsWith("shes") -> n.dropLast(2)
          n.endsWith("s") && !n.endsWith("ss") -> n.dropLast(1)
          else -> return null
        }
    return terms[singular]
  }
}
