package com.ccidea.plugin.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProjectKeyFormatTest {

    @Test
    fun `nested path slug returns last two segments`() {
        // Original: /Users/foam/product/yiya/writing/backend/service
        val slug = "-Users-foam------product-yiya-writing-backend-service"
        assertThat(ProjectKeyFormat.shortName(slug)).isEqualTo("backend/service")
    }

    @Test
    fun `simple path returns last two segments after stripping Users-foam`() {
        val slug = "-Users-foam------blog-myblog"
        assertThat(ProjectKeyFormat.shortName(slug)).isEqualTo("blog/myblog")
    }

    @Test
    fun `short single-segment slug returned verbatim`() {
        // Path that ends at /Users/foam (just the home dir).
        val slug = "-Users-foam"
        assertThat(ProjectKeyFormat.shortName(slug)).isEqualTo("foam")
    }

    @Test
    fun `blank slug returns unknown placeholder`() {
        assertThat(ProjectKeyFormat.shortName("")).isEqualTo("(unknown)")
        assertThat(ProjectKeyFormat.shortName("---")).isEqualTo("(unknown)")
    }

    @Test
    fun `home variants are stripped`() {
        val slug = "-home-alice-projects-dashboard"
        assertThat(ProjectKeyFormat.shortName(slug)).isEqualTo("projects/dashboard")
    }
}
