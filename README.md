# Kopek Android

L'application Android de Kopek et son SDK **KopekKit**.

## Prérequis

- Android Studio (AGP 9), JDK 21
- Un émulateur ou un téléphone Android 7.0+ (API 24)

## Compiler et tester

```bash
./gradlew assembleDebug test   # compile l'appli et lance tous les tests unitaires
./gradlew :kopekkit:test       # tests du SDK seul
./gradlew :app:installDebug    # installe l'appli sur l'appareil branché
```

## Structure

| Dossier | Rôle |
|---|---|
| `app/` | L'application Compose (`com.lteixeira.kopek`) |
| `kopekkit/` | Le SDK KopekKit, bibliothèque Android sans UI (`com.lteixeira.kopekkit`) |
| `docs/API.md` | L'API publique de KopekKit, commune avec iOS |
| `gradle/libs.versions.toml` | Toutes les versions (bibliothèques et plugins) |

`app` dépend de `kopekkit`, jamais l'inverse.

## Version

KopekKit suit la version de Kopek web : **0.1.0**.
