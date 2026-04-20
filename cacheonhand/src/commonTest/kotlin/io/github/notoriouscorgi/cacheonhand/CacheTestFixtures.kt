package io.github.notoriouscorgi.cacheonhand

import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput

data class FakeInput(
    val value: Int,
    val isError: Boolean = false,
) : QueryInput {
    override val identifier: String = "FakeInput"
}
