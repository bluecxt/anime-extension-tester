# 🧪 Anime Extension Tester

A blazing-fast, lightweight, pure JVM testing framework and CLI runner for **Aniyomi**, **AniZen**, and **Tachiyomi** anime extensions.

Eliminates the heavy overhead of Android runtime (`d8`/`r8`, DEX compilation, Robolectric shadow suites, or Android emulators). Runs directly on a standard JVM with thread-safe dependency injection, in-memory `SharedPreferences`, multi-version DTO mapping, and snapshot testing.

---

## ⚡ Key Highlights

- **Pure JVM Execution:** Runs compiled `.class` and `.jar` extension bytecodes without an Android device or emulator.
- **Dynamic Extension ClassLoading:** Automatically discovers compiled intermediate classes from multi-module Gradle builds (`core/` and `src/<lang>/<extension>/`).
- **Complete Method Coverage:**
  - **High-level Suspending Methods:** `getPopularAnime`, `getLatestUpdates`, `getSearchAnime`, `getAnimeDetails`, `getSeasonList` (v16+), `getEpisodeList`, `getHosterList` (v16+), `getVideoList`.
  - **Low-level Request/Parse Pairs:** `popularAnimeRequest`/`popularAnimeParse`, `latestUpdatesRequest`/`latestUpdatesParse`, `searchAnimeRequest`/`searchAnimeParse`, `episodeListParse`, `hosterListParse`, `videoListParse`.
- **Snapshot Testing & Contract Assertions:**
  - Snapshot persistence with `--update-snapshots` (`-u`).
  - Structural assertions (`minCount`, `maxCount`, `containsTitles`, `requireNonBlankFields`, `disallowedThumbnails`).
- **Offline Fixture Mocking:** Run full parser pipelines offline using recorded HTML/JSON response fixtures (`mockResponse`).
- **Concurrency & Rate Limiting:**
  - Per-host mutex rate-limiting.
  - Automatic exponential backoff on HTTP 429 (`Retry-After`).
  - Configurable worker concurrency (`-c`).
- **Network Proxying:** Full HTTP/HTTPS/SOCKS5 proxy support via `-p` / `--proxy`.
- **Zero-Pollution State:** Isolated in-memory `SharedPreferences` and clean Injekt singletons (`Application`, `Context`, `NetworkHelper`, `Json`) per test execution.

---

## 🚀 Installation & Prerequisites

- **Java JDK 21+**
- **Gradle 8.5+** (Gradle Wrapper included)

```bash
git clone https://github.com/bluecxt/anime-extension-tester.git
cd anime-extension-tester
./gradlew build
```

---

## 💻 CLI Usage

```text
Usage: anime-extension-tester [<options>]

Options:
  -s, --specs=<path>         Path(s) or glob pattern(s) to JSON test specs (e.g. tests/*.json) [required]
  -e, --extension-dir=<path> Directory of extension module to auto-resolve classes
  -cp, --classpath=<path>    Additional classpath directories or JARs
  -c, --concurrency=<int>    Max concurrent test executions (default: 4)
  -r, --rate-limit=<ms>      Minimum delay in milliseconds between requests to the same domain (default: 600)
  --timeout=<sec>            OkHttp connect/read/write timeout in seconds (default: 30)
  --max-retries=<int>        Max retries on HTTP 429 or transient connection errors (default: 3)
  -p, --proxy=<url>          HTTP or SOCKS5 proxy URL (e.g. http://127.0.0.1:8080)
  -u, --update-snapshots     Update expected snapshots in spec files with actual results
  -k, --filter=<regex>       Regex pattern to filter test cases by name
  -v, --verbose              Enable verbose debug logs and stack traces
  -h, --help                 Show this message and exit
```

### Examples

#### 1. Run live tests on an extension module
```bash
./gradlew run --args="-s samples/animesama.json -e /path/to/anime-extensions/src/fr/animesama"
```

#### 2. Update snapshots automatically
```bash
./gradlew run --args="-s samples/animesama.json -e /path/to/anime-extensions/src/fr/animesama -u"
```

#### 3. Run with a proxy and high concurrency
```bash
./gradlew run --args="-s 'specs/**/*.json' -e /path/to/extension -p http://127.0.0.1:8080 -c 8"
```

#### 4. Filter specific tests
```bash
./gradlew run --args="-s samples/animesama.json -e /path/to/extension -k 'Search.*'"
```

---

## 📄 Test Spec JSON Format

Test specs are simple, human-readable JSON documents:

```json
{
  "name": "Anime-Sama Suite",
  "extension": "eu.kanade.tachiyomi.animeextension.fr.animesama.AnimeSama",
  "preferences": {
    "early_fetch": true
  },
  "tests": [
    {
      "name": "Popular Anime Catalog Check",
      "target": "GET_POPULAR",
      "input": {
        "page": 1
      },
      "contract": {
        "minCount": 5,
        "requireNonBlankFields": ["title", "url"]
      }
    },
    {
      "name": "Search Query Check",
      "target": "GET_SEARCH",
      "input": {
        "page": 1,
        "query": "Naruto"
      },
      "contract": {
        "minCount": 1,
        "containsTitles": ["Naruto"]
      }
    },
    {
      "name": "Anime Details Check",
      "target": "GET_DETAILS",
      "input": {
        "anime": {
          "title": "Naruto",
          "url": "/catalogue/naruto/"
        }
      },
      "contract": {
        "requireNonBlankFields": ["description", "thumbnail_url"]
      }
    },
    {
      "name": "Offline Parsing with Fixture",
      "target": "DETAILS_PARSE",
      "input": {
        "anime": {
          "title": "Naruto",
          "url": "/catalogue/naruto/"
        }
      },
      "mockResponse": {
        "status": 200,
        "bodyFile": "fixtures/naruto.html"
      },
      "expected": {
        "anime": {
          "title": "Naruto",
          "description": "Dans le village de Konoha..."
        }
      }
    }
  ]
}
```

### Supported Targets

| Category | Target Enum | Invoked Method |
|---|---|---|
| **Suspending (Live/Replay)** | `GET_POPULAR` | `source.getPopularAnime(page)` |
| | `GET_LATEST` | `source.getLatestUpdates(page)` |
| | `GET_SEARCH` | `source.getSearchAnime(page, query, filters)` |
| | `GET_DETAILS` | `source.getAnimeDetails(anime)` |
| | `GET_SEASON_LIST` | `source.getSeasonList(anime)` |
| | `GET_EPISODE_LIST` | `source.getEpisodeList(anime)` |
| | `GET_HOSTER_LIST` | `source.getHosterList(episode)` |
| | `GET_VIDEO_LIST` | `source.getVideoList(hoster/episode)` |
| **Request Builders** | `POPULAR_REQUEST` | `source.popularAnimeRequest(page)` |
| | `LATEST_REQUEST` | `source.latestUpdatesRequest(page)` |
| | `SEARCH_REQUEST` | `source.searchAnimeRequest(page, query, filters)` |
| **Parsers (Offline Mockable)** | `POPULAR_PARSE` | `source.popularAnimeParse(response)` |
| | `LATEST_PARSE` | `source.latestUpdatesParse(response)` |
| | `SEARCH_PARSE` | `source.searchAnimeParse(response)` |
| | `DETAILS_PARSE` | `source.animeDetailsParse(response)` |
| | `SEASON_LIST_PARSE` | `source.seasonListParse(response)` |
| | `EPISODE_PARSE` | `source.episodeListParse(response)` |
| | `HOSTER_PARSE` | `source.hosterListParse(response)` |
| | `VIDEO_PARSE` | `source.videoListParse(response, hoster)` |

---

## 🛠 GitHub Actions CI Integration

```yaml
name: Extension Regression Tests

on:
  push:
    branches: [ main, dev ]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Compile Extension Bytecode
        run: ./gradlew :src:fr:animesama:assembleDebug

      - name: Run Extension Tester
        run: |
          ./gradlew -p anime-extension-tester run --args=" \
            -s tests/animesama.json \
            -e src/fr/animesama"
```

---

## ⚖️ License

Apache License 2.0. See [LICENSE](LICENSE) for details.
