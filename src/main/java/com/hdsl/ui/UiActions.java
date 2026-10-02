package com.hdsl.ui;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Asynchronous boundary between the desktop interface and launcher services. */
@FunctionalInterface
public interface UiActions {
    CompletableFuture<UiState> dispatch(String action, Map<String, String> arguments);
}
