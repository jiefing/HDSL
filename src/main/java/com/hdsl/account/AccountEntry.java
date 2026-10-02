package com.hdsl.account;

/** Public account metadata. No secret or partial secret is exposed to the UI. */
public record AccountEntry(String id, String name, String provider, String baseUrl, String model,
                           boolean hasKey, String status) { }
