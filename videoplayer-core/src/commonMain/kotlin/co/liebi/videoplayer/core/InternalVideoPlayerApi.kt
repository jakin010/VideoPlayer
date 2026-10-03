package co.liebi.videoplayer.core

/** Connects `videoplayer-core` to `videoplayer-ui`. Not meant for apps. */
@RequiresOptIn(
    message = "Used by the videoplayer-ui module. Apps use the composables it provides, such as FullscreenHost.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class InternalVideoPlayerApi
