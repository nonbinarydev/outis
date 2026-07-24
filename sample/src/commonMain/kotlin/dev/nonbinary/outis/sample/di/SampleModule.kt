/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.di

import dev.nonbinary.outis.sample.catalogue.CatalogueRepository
import dev.nonbinary.outis.sample.diagnostics.DiagnosticsLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * The sample's own wiring. The SDK ships no DI container — `:core` and `:ui` are constructed directly —
 * so nothing here is required to consume Outis. It exists because the demo doubles as a reference
 * integration, and a real app has a graph.
 */
val sampleModule = module {
    single {
        Json {
            // The catalogue carries a `$comment` key, and the schema is expected to gain fields before
            // the version is bumped. Additive changes must not break already-shipped builds, which is
            // the whole reason it is fetched rather than compiled in.
            ignoreUnknownKeys = true
        }
    }
    single {
        HttpClient {
            install(ContentNegotiation) { json(get<Json>()) }
        }
    }
    single { CatalogueRepository(get()) }
    single { DiagnosticsLog() }
}
