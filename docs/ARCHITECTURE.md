# Architecture and implementation status

## Current boundaries

- `storyboard/`: storyboard models, deterministic draft generation, timeline validation, UI state.
- `motion/`: normalized camera transform calculations. This module does not draw frames or encode video by itself.
- `render/`: placeholder comic frame composition, EGL/GLES camera transforms, MediaCodec H.264 encoding, MediaMuxer MP4 container output, and render progress/result types.
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

Although an H.264/MP4 implementation now exists in code, do not treat device support as verified until an output file has been generated and playback-tested on real Android devices.

## Current limitations

- No local LLM runtime or model is bundled.
- No image-generation runtime or model is bundled.
- No TTS runtime or voice model is bundled.
- No SFX/music assets are bundled.
- The renderer currently draws generic silhouette-based comic panels from text rather than using user artwork or AI-generated images.
- Export currently contains video only; there is no audio track, SRT sidecar, gallery save, or Sharesheet integration.
- Camera motion is applied to the generated scene texture through GLES; scene transitions and layered parallax are not implemented.
- Device profiling, memory estimates, and device playback tests are not implemented.
- The project has not yet been built or tested on an Android device from this environment.
