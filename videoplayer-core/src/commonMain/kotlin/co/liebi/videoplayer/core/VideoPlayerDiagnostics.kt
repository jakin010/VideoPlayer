package co.liebi.videoplayer.core

/** Hooks for leak checks in test tooling such as the sample's Checks tab (§17). Not meant for apps. */
@InternalVideoPlayerApi
public object VideoPlayerDiagnostics {
    /**
     * Called on the main thread with every native player the library creates: an `ExoPlayer` on Android, an
     * `AVPlayer` on iOS. A test can hold it weakly and check that it is freed once its controller is released.
     */
    public var onNativePlayerCreated: ((nativePlayer: Any) -> Unit)? = null
}
