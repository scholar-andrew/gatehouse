# OpenJFX bug report: HE-AAC in MP4 plays an octave low on Linux

A report ready to file with OpenJFX (R80). Gatehouse works around it for files it plays itself (`HeAac.java`), but
not for video a web view streams straight from a site, such as YouTube's player in chat.

**Where to file it:** <https://bugreport.java.com/> (no account needed), product "JavaFX", component "media".
OpenJFX's tracker (bugs.openjdk.org) takes reports only from people with an author account; the form above is the way
in for everyone else. Copy the sections below into it.

---

**Synopsis:** HE-AAC audio in MP4 plays at half speed and an octave low on Linux (sample rate taken from the stsd
entry instead of the decoder's output)

**Platform:** Linux x86_64 (seen on SteamOS 3 / Steam Deck and desktop Linux), OpenJFX 23.0.2 and 21.0.6, system
FFmpeg 6 and 7 (libavcodec). Windows and macOS play the same files correctly.

**Description:** An MP4 (or M4A, MOV) whose audio is HE-AAC (AAC-LC core plus SBR, object type 5 or 29 in the
AudioSpecificConfig, typically 24 kHz core / 48 kHz output, or 22.05 / 44.1 kHz) plays its sound at half speed and an
octave low through `javafx.scene.media.MediaPlayer` on Linux; the picture keeps normal time, so sound and picture
drift apart and chunks are skipped. The same happens inside `javafx.scene.web.WebView` for HTML `<video>`, for example
YouTube's embedded player (which serves a progressive MP4, with HE-AAC sound for some videos, to a browser without
Media Source Extensions).

**Steps to reproduce:**
1. Make or get an MP4 with HE-AAC v1 sound, for example
   `ffmpeg -i in.wav -c:a libfdk_aac -profile:a aac_he -b:a 48k out.m4a` (an FFmpeg built with libfdk_aac), or any
   AAC file whose AudioSpecificConfig has object type 5.
2. On Linux, play it with `new MediaPlayer(new Media(file.toURI().toString())).play()`.

**Expected:** normal pitch and speed, as on Windows and macOS.

**Actual:** an octave low, half speed.

**Cause, as far as we could trace it** (sources under `modules/javafx.media/src/main/native/gstreamer`):
- `qtdemux.c` sets the audio caps' `rate` from the sample description (`stsd`) unless the codec data is the basic
  two-byte AudioSpecificConfig; for HE-AAC the config is longer, and `stsd` holds the *core* rate (24 kHz).
- The AAC decoder element in `audiodecoder.c` (backed by the system's libavcodec) configures its output once from
  those caps and doesn't look again, while libavcodec decodes the SBR and puts out the *full* rate (48 kHz). Twice as
  many samples per second are then played at half the rate.

**Suggested fix:** in the decoder, take the output rate (and channel count) from the decoded frame
(`AVFrame.sample_rate` / `AVCodecContext.sample_rate` after the first frame) and renegotiate the caps when they differ
from the input caps; or, in `qtdemux.c`, parse the AudioSpecificConfig's extension (SBR) rate for object types 5 and
29 and use it for the caps.

**Workaround:** for local files, rewrite the four bytes of the `stsd` sample rate to the AudioSpecificConfig's output
rate before playing (what Gatehouse does). There's no workaround for streamed media in WebView.
