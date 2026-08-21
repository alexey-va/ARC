package clib.net.kyori.adventure.text

/**
 * Citizens relocates Adventure at runtime but omits that downloaded class from
 * its published API test artifact. The metadata enum only needs the class token
 * during static initialization, so this test-only marker completes the fixture.
 */
interface Component
