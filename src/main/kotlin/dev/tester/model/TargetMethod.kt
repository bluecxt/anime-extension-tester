package dev.tester.model

import kotlinx.serialization.Serializable

@Serializable
enum class TargetMethod {
    // High-level suspending methods (Network live or with replay interceptor)
    GET_POPULAR,
    GET_LATEST,
    GET_SEARCH,
    GET_DETAILS,
    GET_SEASON_LIST,
    GET_EPISODE_LIST,
    GET_HOSTER_LIST,
    GET_VIDEO_LIST,
    FETCH_RELATED,

    // Low-level Request builders
    POPULAR_REQUEST,
    LATEST_REQUEST,
    SEARCH_REQUEST,

    // Low-level Parse methods (Can run offline with mock_response file)
    POPULAR_PARSE,
    LATEST_PARSE,
    SEARCH_PARSE,
    DETAILS_PARSE,
    SEASON_LIST_PARSE,
    EPISODE_PARSE,
    HOSTER_PARSE,
    VIDEO_PARSE,
}
