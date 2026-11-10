package com.eollas.sleepmonitor.tracking

/** Noms en français des sons les plus courants (index YAMNet). */
object Labels {
    private val fr = mapOf(
        0 to "Paroles", 1 to "Voix d'enfant", 2 to "Conversation", 3 to "Paroles", 4 to "Babillage",
        6 to "Cri", 7 to "Cri", 9 to "Cri", 11 to "Hurlement", 12 to "Chuchotement", 13 to "Rire",
        15 to "Rire", 18 to "Rire", 19 to "Pleurs", 20 to "Pleurs de bébé", 21 to "Gémissement",
        22 to "Plainte", 23 to "Soupir", 32 to "Fredonnement", 33 to "Gémissement", 34 to "Grognement",
        38 to "Ronflement", 41 to "Reniflement fort", 42 to "Toux", 43 to "Raclement de gorge",
        44 to "Éternuement", 67 to "Animal", 68 to "Animal domestique", 69 to "Chien", 70 to "Aboiement",
        72 to "Hurlement de chien", 74 to "Grognement", 76 to "Chat", 78 to "Miaulement", 106 to "Oiseau",
        107 to "Chant d'oiseau", 108 to "Gazouillis", 114 to "Hibou", 121 to "Insecte", 123 to "Moustique",
        125 to "Bourdonnement", 132 to "Musique", 280 to "Orage", 281 to "Tonnerre", 282 to "Eau",
        283 to "Pluie", 288 to "Mer", 294 to "Véhicule", 300 to "Véhicule", 301 to "Voiture",
        302 to "Klaxon", 304 to "Alarme de voiture", 306 to "Dérapage", 308 to "Voiture qui passe",
        310 to "Camion", 315 to "Bus", 316 to "Véhicule d'urgence", 317 to "Sirène de police",
        318 to "Ambulance", 319 to "Pompiers", 320 to "Moto", 321 to "Circulation", 323 to "Train",
        329 to "Avion", 333 to "Hélicoptère", 337 to "Moteur",
        348 to "Porte", 349 to "Sonnette", 351 to "Porte coulissante", 352 to "Claquement",
        353 to "Coups", 354 to "Tapotement", 355 to "Grincement", 356 to "Placard", 357 to "Tiroir",
        358 to "Vaisselle", 364 to "Robinet", 368 to "Chasse d'eau", 382 to "Alarme",
        383 to "Téléphone", 384 to "Sonnerie", 385 to "Sonnerie", 389 to "Réveil", 390 to "Sirène",
        392 to "Buzzer", 393 to "Détecteur de fumée", 413 to "Marteau", 418 to "Outil électrique",
        420 to "Explosion", 426 to "Feux d'artifice", 430 to "Boum", 435 to "Verre", 437 to "Bris de verre",
        442 to "Goutte", 454 to "Choc sourd", 455 to "Choc", 460 to "Bruit sec", 464 to "Casse",
        475 to "Bip", 476 to "Ping", 477 to "Ding", 478 to "Fracas", 480 to "Grincement",
        483 to "Fracas", 487 to "Grondement", 518 to "Télévision", 519 to "Radio",
    )

    /** Index des sons qui ont un nom français précis. */
    fun known(): Set<Int> = fr.keys

    fun of(index: Int): String = fr[index] ?: when (index) {
        in 132..276 -> "Musique"
        in 294..339 -> "Circulation"
        in 67..131 -> "Animal"
        else -> "Bruit"
    }
}
