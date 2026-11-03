package me.zssu.ime.ime

/** The standalone neural composer must commit ASCII and its space in the same key press. */
internal object StandaloneSpace {
    enum class Target { EDITOR_SPACE, COMMIT_AND_SPACE, NEURAL_CONVERSION }
    fun route(composing: Boolean, japanese: Boolean): Target = when {
        !composing -> Target.EDITOR_SPACE
        !japanese -> Target.COMMIT_AND_SPACE
        else -> Target.NEURAL_CONVERSION
    }
}
