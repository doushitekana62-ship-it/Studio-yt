# Architecture and implementation status

## Current boundaries

- `storyboard/`: storyboard models, deterministic draft generation, timeline validation, UI state.
- `motion/`: normalized camera transform calculations. This module does not draw frames or encode video by itself.
- `render/`: renderer contracts and progress/result types. A concrete encoder implementation is not present yet.
- `ui/`: Compose theme and the initial studio screen.

## Next engineering milestone: rendered comic motion

The next implementation should create a bounded-memory frame pipeline and an Android-supported video encoder adapter. Avoid creating a bitmap for every frame up front. For each frame timestamp:

1. Resolve the active scene from the validated timeline.
2. Load/reuse its source artwork.
3. Evaluate camera motion at normalized scene progress.
4. Draw background, character layers, overlays, and subtitle into the current frame surface.
5. Submit the frame to a hardware/software encoder.
6. Mix or mux audio on a separate bounded pipeline.
7. validate encoded duration, dimensions, frame rate, and audio track before reporting success.

Do not report MP4 export as complete until a real output file has been generated and playback-tested on supported Android devices.

## Current limitations

- No local LLM runtime or model is bundled.
- No image-generation runtime or model is bundled.
- No TTS runtime or voice model is bundled.
- No SFX/music assets are bundled.
- Motion transforms are math utilities; the Compose UI does not yet animate scene artwork.
- Render contracts are interfaces only; no MP4 encoder adapter exists.
- Device profiling and memory estimates are not implemented.
- The project has not yet been built or tested on an Android device from this environment.
