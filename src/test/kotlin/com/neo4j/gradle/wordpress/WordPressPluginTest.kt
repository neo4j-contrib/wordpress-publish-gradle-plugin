package com.neo4j.gradle.wordpress

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

class WordPressPluginTest {
    @Test fun `plugin registers task`() {
        // Create a test project and apply the plugin
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.neo4j.gradle.wordpress.WordPressPlugin")
        // Verify the result
        assertNotNull(project.extensions.findByName("wordpress"))
    }
}
