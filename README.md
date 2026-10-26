# anime-extension-tester

CLI headless et suite de tests JVM pure pour extensions Aniyomi / AniZen / Tachiyomi.

Permet de tester les extensions compilées (`.class` / `.jar`) directement sur la machine hôte sans passer par `d8`/`r8`, sans lancer d'émulateur Android et sans le surcoût de Robolectric.

```
+-------------------------------------------------------------+
|                     anime-extension-tester                  |
|                                                             |
|   +-------------------+    +----------------------------+   |
|   |  ExtensionLoader  |    |     TachiyomiMock Engine   |   |
|   |  - Core classes   |    |  - SharedPreferences (mem) |   |
|   |  - Extension build|    |  - Injekt Application / ctx|   |
|   |  - lib-multisrc   |    |  - NetworkHelper & OkHttp  |   |
|   +---------+---------+    +--------------+-------------+   |
|             |                             |                 |
|             +--------------+--------------+                 |
|                            v                                |
|                 [ TestRunner / Coroutines ]                 |
|                            |                                |
|       +--------------------+--------------------+           |
|       v                                         v           |
|  Live Network Mode                     Offline Replay Mode  |
|  - Per-host Rate Limiter               - Fixtures / JSON    |
|  - HTTP 429 Retry-After                - Exact Snapshots    |
|  - SOCKS5 / HTTP Proxy                                      |
+-------------------------------------------------------------+
```

---

## Fonctionnalités

- **Exécution JVM pure** : Charge directement les classes Android/Kotlin depuis `build/intermediates/built_in_kotlinc/` ou les `.jar`.
- **Environnement Tachiyomi isolé** : Stubs in-memory pour `Injekt`, `Application`, `SharedPreferences`, et `OkHttpClient`.
- **Support complet du cycle de vie Source** :
  - Méthodes suspendantes : `getPopularAnime`, `getLatestUpdates`, `getSearchAnime`, `getAnimeDetails`, `getSeasonList`, `getEpisodeList`, `getHosterList`, `getVideoList`, `fetchRelatedAnimeList`.
  - Paires Request / Parse : injection automatique de requêtes fraîches pour éviter les fuites de stream OkHttp et préserver les URLs de base dans Jsoup.
  - Filtres dynamiques : population automatique de `AnimeFilterList` (Select, CheckBox, TriState, Text, Group).
- **Contrôle réseau fin** :
  - Limitation de débit par domaine (`--rate-limit-ms`) pour éviter les bans IP.
  - Gestion automatique des retries sur HTTP 429 avec parsing du header `Retry-After`.
  - Support proxy HTTP et SOCKS5 (`--proxy`).
- **Assertions de contrat & Snapshots** :
  - Validation structurelle (`minCount`, `maxCount`, `containsTitles`, `requireNonBlankFields`, `disallowedThumbnails`).
  - Mode mise à jour des snapshots (`--update-snapshots`).

---

## Installation & Prérequis

- **JDK 21+**
- **Gradle 8.5+**

```bash
git clone https://github.com/bluecxt/anime-extension-tester.git
cd anime-extension-tester
./gradlew build
```

---

## Utilisation CLI

```
Usage: anime-extension-tester [<options>]

Options:
  -s, --specs=<path|glob>     Chemin ou glob des suites de tests JSON (ex: 'samples/*.json') [requis]
  -e, --extension-dir=<path>  Dossier racine des extensions ou fichier build.gradle spécifique
  -cp, --classpath=<path>     Dossiers ou JARs additionnels à injecter dans le ClassLoader
  -c, --concurrency=<int>     Nombre de tests exécutés en parallèle (défaut: 4)
  -r, --rate-limit-ms=<int>   Délai minimum en ms entre deux requêtes vers un même hôte (défaut: 600)
  --max-retries=<int>         Nombre maximal de retries sur erreur 429 ou réseau (défaut: 3)
  -t, --timeout=<int>         Timeout réseau en secondes (défaut: 30)
  -p, --proxy=<url>           Proxy HTTP ou SOCKS5 (ex: socks5://127.0.0.1:9050)
  -u, --update-snapshots      Met à jour les snapshots attendus dans les specs JSON
  -f, --filter=<regex>        Regex pour filtrer les suites ou tests à exécuter
  -v, --verbose               Active les logs détaillés et stack traces
  -h, --help                  Affiche l'aide
```

### Exemples

Tester une extension spécifique :
```bash
./gradlew run --args="-s samples/animesama.json -e /path/to/anime-extensions-french/src/fr/animesama"
```

Cibler directement le `build.gradle` d'un module (résolution automatique de la classe FQN) :
```bash
./gradlew run --args="-s samples/animesama.json -e /path/to/src/fr/animesama/build.gradle"
```

Exécuter toutes les suites avec limitation de débit et proxy :
```bash
./gradlew run --args="-s 'samples/**/*.json' -e /path/to/src/fr -r 1000 -c 2 -p socks5://127.0.0.1:9050"
```

Mettre à jour les snapshots avec les résultats actuels :
```bash
./gradlew run --args="-s samples/animesama.json -e /path/to/src/fr/animesama -u"
```

---

## Format de Spécification JSON

```json
{
  "name": "Anime-Sama Suite",
  "extension": "eu.kanade.tachiyomi.animeextension.fr.animesama.AnimeSama",
  "preferences": {
    "early_fetch": true
  },
  "tests": [
    {
      "name": "Catalogue Populaire",
      "target": "GET_POPULAR",
      "input": { "page": 1 },
      "contract": {
        "minCount": 10,
        "requireNonBlankFields": ["title", "url", "thumbnail_url"]
      }
    },
    {
      "name": "Recherche Naruto avec Filtres",
      "target": "GET_SEARCH",
      "input": {
        "page": 1,
        "query": "Naruto",
        "filters": {
          "order": "2",
          "genres[]": ["Action", "Shonen"]
        }
      },
      "contract": {
        "minCount": 1,
        "containsTitles": ["Naruto"]
      }
    },
    {
      "name": "Fiche Détails Anime",
      "target": "GET_DETAILS",
      "input": {
        "anime": {
          "title": "Naruto",
          "url": "{\n    \"url\": \"/catalogue/naruto\",\n    \"titles\": [\"Naruto\"]\n}"
        }
      },
      "contract": {
        "requireNonBlankFields": ["description", "thumbnail_url", "genre"]
      }
    },
    {
      "name": "Liste des Épisodes",
      "target": "GET_EPISODE_LIST",
      "input": {
        "anime": {
          "title": "Naruto",
          "url": "{\n    \"url\": \"/catalogue/naruto\",\n    \"titles\": [\"Naruto\"]\n}"
        }
      },
      "contract": {
        "minCount": 220
      }
    }
  ]
}
```

### Cibles supportées (`target`)

| Cible | Méthode appelée |
|---|---|
| `GET_POPULAR` | `source.getPopularAnime(page)` |
| `GET_LATEST` | `source.getLatestUpdates(page)` |
| `GET_SEARCH` | `source.getSearchAnime(page, query, filters)` |
| `GET_DETAILS` | `source.getAnimeDetails(anime)` |
| `GET_SEASON_LIST` | `source.getSeasonList(anime)` |
| `GET_EPISODE_LIST` | `source.getEpisodeList(anime)` |
| `GET_HOSTER_LIST` | `source.getHosterList(episode)` |
| `GET_VIDEO_LIST` | `source.getVideoList(hoster/episode)` |
| `FETCH_RELATED` | `source.fetchRelatedAnimeList(anime)` |
| `POPULAR_PARSE` / `POPULAR_REQUEST` | Paires bas niveau `popularAnime*` |
| `SEARCH_PARSE` / `SEARCH_REQUEST` | Paires bas niveau `searchAnime*` |
| `DETAILS_PARSE` | Parsing fiche détails offline via `mockResponse` |

---

## Intégration CI (GitHub Actions)

Pour les tests réguliers sur un runner auto-hébergé (recommandé pour éviter les blocages Cloudflare/datacenter des IPs GitHub Actions) :

```yaml
name: Extension Regression Suite

on:
  schedule:
    - cron: '0 3 * * 0' # Hebdomadaire (Dimanche 03:00)
  workflow_dispatch:

jobs:
  test-extensions:
    runs-on: [self-hosted, linux]
    steps:
      - name: Checkout Extensions
        uses: actions/checkout@v4
        with:
          submodules: recursive

      - name: Setup Java 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Compile Modules
        run: |
          ./gradlew assembleDebug --parallel

      - name: Download & Run Tester
        run: |
          # Téléchargement de la dernière release ou exécution directe
          git clone --depth 1 https://github.com/bluecxt/anime-extension-tester.git /tmp/tester
          cd /tmp/tester
          ./gradlew run --args=" \
            -s '$GITHUB_WORKSPACE/samples/**/*.json' \
            -e '$GITHUB_WORKSPACE/src/fr' \
            -c 2 \
            -r 800"
```

Codes de retour :
- `0` : Tous les tests et contrats sont validés.
- `1` : Échec d'un ou plusieurs tests ou violation de contrat.

---

## Licence

Apache-2.0

