# engine

Couche Kotlin pure de RavenEmu.

Les modules Gradle `:engine:*` portent les contrats et l'orchestration d'émulation. Pendant la migration V2, `settings.gradle.kts` peut encore les rattacher à des dossiers historiques afin de séparer le changement d'architecture d'un déplacement mécanique massif des sources.

Cette couche ne dépend jamais d'Android.
