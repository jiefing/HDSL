package com.hdsl.account;

/** Convenience defaults; the selected Harness runtime determines actual support. */
public record ProviderPreset(String id, String name, String baseUrl, String model) { }
