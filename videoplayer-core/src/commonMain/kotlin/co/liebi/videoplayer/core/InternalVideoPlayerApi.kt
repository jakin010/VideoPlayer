package co.liebi.videoplayer.core

/** Connects `videoplayer-core` to `videoplayer-ui` and to test tooling. Not meant for apps. */
@RequiresOptIn(
    message = "Used by the videoplayer-ui module and test tooling. Apps use the composables videoplayer-ui provides, such as FullscreenHost.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class InternalVideoPlayerApi
