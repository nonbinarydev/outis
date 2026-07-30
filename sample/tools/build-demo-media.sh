#!/usr/bin/env bash
#
# build-demo-media.sh — regenerable demo/test media for the Outis player SDK.
#
# Turns a single Creative-Commons master (Big Buck Bunny, CC-BY 3.0, Blender Foundation) into a full
# cross-platform test asset: a labelled ABR ladder across AVC/HEVC/VP9/AV1, CMAF packaged as HLS *and*
# DASH from one set of segments, progressive MP4 + WebM, multiple audio tracks, WebVTT subtitles, chapters
# in every applicable form, and a ClearKey-encrypted variant. Emits a catalogue.json snippet + CREDITS.md.
#
# Everything derives from the master, so it is reproducible — nothing binary needs committing. Host the
# small text (manifests, vtt, catalogue) on GitHub Pages and the heavy segments on Cloudflare R2 (free
# egress); see docs. All content is CC-BY — ship the generated CREDITS.md.
#
# Requirements (macOS):
#   - ffmpeg with libfreetype + libx264/libx265/libvpx/libsvtav1/libopus  (the evermeet build works;
#     this script defaults to ~/bin/ffmpeg — override with FFMPEG=...).
#   - Shaka Packager (`brew install shaka-packager`) for the CMAF/HLS/DASH/ClearKey stages.
#   - A monospace font for the burned-in info board (macOS Menlo by default).
#
# Usage:
#   ./build-demo-media.sh                 # full run
#   STAGES="fetch encode" ./build-demo-media.sh    # run only some stages
#   MASTER_URL=.../bbb_sunflower_1080p_30fps_normal.mp4.zip ./build-demo-media.sh   # smaller/faster master (no 2160p)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # for committed source assets (real subtitles)

# ─── config ──────────────────────────────────────────────────────────────────────────────────────────
FFMPEG="${FFMPEG:-$HOME/bin/ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"
PACKAGER="${PACKAGER:-$HOME/bin/packager}"   # matches FFMPEG default; ~/bin may not be on your PATH
FONT="${FONT:-/System/Library/Fonts/Menlo.ttc}"
OUT="${OUT:-$PWD/demo-media-out}"          # gitignore this; segments are large
MASTER_URL="${MASTER_URL:-https://download.blender.org/demo/movies/BBB/bbb_sunflower_2160p_60fps_normal.mp4.zip}"  # 4K60 hero (671MB); override with the 1080p30 (275MB) to iterate fast
SEGDUR="${SEGDUR:-4}"                       # segment duration (s); keyframes are aligned to it
PRESET="${PRESET:-medium}"                  # x264/x265 preset (use veryfast to iterate, slow for quality)
THUMB_INTERVAL="${THUMB_INTERVAL:-5}"       # trickplay thumbnail interval (s) — raise it for longer content
THUMB_WIDTH="${THUMB_WIDTH:-240}"           # trickplay tile width (px); height follows the master's aspect
STAGES="${STAGES:-fetch chapters subs audio encode package clearkey progressive chaptered thumbnails catalogue credits}"

WORK="$OUT/work"; MED="$OUT/media"
mkdir -p "$WORK" "$MED"

# AVC ABR ladder — name  WxH  v-bitrate  maxrate  bufsize. Default master is 4K so 2160p is included; rungs
# taller than the master are auto-skipped (no upscaling). Keyframes are segment-aligned so all switch cleanly.
AVC_LADDER=(
  "240p    426x240    400k   500k   800k"
  "480p    854x480   1200k  1400k  2600k"
  "720p   1280x720   2800k  3100k  5600k"
  "1080p  1920x1080  5000k  5500k 10000k"
  "2160p  3840x2160 14000k 15000k 28000k"
)

# Verified chapter set (see build-demo-media notes / issue #46). start(ms)  title
CHAPTERS=(
  "0        A Peaceful Morning"
  "122400   The Rodent Trio"
  "268933   The Turning Point"
  "385600   Revenge"
  "495000   Credits"
  "618767   Post-Credits Gag"
)
DURATION_MS=634600

step(){ printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
has_stage(){ [[ " $STAGES " == *" $1 "* ]]; }
need(){ command -v "$1" >/dev/null 2>&1 || { echo "missing: $1 — $2"; exit 1; }; }

# ─── preflight ───────────────────────────────────────────────────────────────────────────────────────
[[ -x "$FFMPEG" ]] || { echo "no ffmpeg at $FFMPEG (set FFMPEG=...)"; exit 1; }
[[ "$("$FFMPEG" -hide_banner -filters 2>/dev/null)" == *drawtext* ]] || { echo "this ffmpeg lacks drawtext (no libfreetype)"; exit 1; }
[[ -f "$FONT" ]] || { echo "no font at $FONT (set FONT=...)"; exit 1; }
if has_stage package || has_stage clearkey; then
  command -v "$PACKAGER" >/dev/null 2>&1 || { echo "Shaka Packager not found — 'brew install shaka-packager' (or set PACKAGER=/path)"; exit 1; }
fi

# ─── fetch: download + unzip the master, once. Cached by SOURCE name, so changing MASTER_URL re-fetches
#     instead of silently reusing a stale master (e.g. the 1080p one after switching the default to 4K). ──
MASTER="$WORK/$(basename "${MASTER_URL%.zip}")"
if has_stage fetch && [[ ! -f "$MASTER" ]]; then
  step "Fetching master ($(basename "$MASTER_URL"))"
  zip="$WORK/$(basename "$MASTER_URL")"
  [[ -f "$zip" ]] || curl -fSL -o "$zip" "$MASTER_URL"
  unzip -o -q "$zip" -d "$WORK"   # extracts to exactly $MASTER (zip is named after its mp4)
fi
[[ -f "$MASTER" ]] || { echo "no master at $MASTER — run the fetch stage"; exit 1; }
read -r MW MH < <("$FFPROBE" -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$MASTER" | tr ',' ' ')
FPS_RAW=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$MASTER")
FPS=$(( ${FPS_RAW%/*} / ${FPS_RAW#*/} )); KEYINT=$(( SEGDUR * FPS ))   # keyframes every SEGDUR seconds, fps-correct
DUR_S=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$MASTER")
[[ -n "$DUR_S" ]] && DURATION_MS=$(awk "BEGIN{printf \"%d\", ($DUR_S)*1000}")   # last chapter's end; correct for any master
echo "master: ${MW}x${MH} @ ${FPS}fps, ${DURATION_MS}ms — segment $SEGDUR s → keyint $KEYINT"

# ─── chapters: ffmetadata (MP4 chpl/MKV native) + WebVTT sidecar ────────────────────────────────────────
gen_chapters(){
  local ffmeta="$WORK/chapters.ffmeta" vtt="$MED/text/chapters.vtt"; mkdir -p "$MED/text"
  echo ";FFMETADATA1" > "$ffmeta"; printf 'WEBVTT\n' > "$vtt"
  local i n=${#CHAPTERS[@]}
  for ((i=0;i<n;i++)); do
    local start title; read -r start title <<< "${CHAPTERS[i]}"
    local end=$DURATION_MS; (( i+1 < n )) && end=${CHAPTERS[i+1]%% *}
    printf '[CHAPTER]\nTIMEBASE=1/1000\nSTART=%s\nEND=%s\ntitle=%s\n' "$start" "$end" "$title" >> "$ffmeta"
    printf '\nChapter %s\n%s --> %s\n%s\n' "$((i+1))" "$(ms2vtt "$start")" "$(ms2vtt "$end")" "$title" >> "$vtt"
  done
}
ms2vtt(){ local ms=$1; printf '%02d:%02d:%02d.%03d' $((ms/3600000)) $((ms/60000%60)) $((ms/1000%60)) $((ms%1000)); }
if has_stage chapters; then step "Chapters (ffmetadata + WebVTT)"; gen_chapters; fi

# ─── subtitles: authored WebVTT (en, es, ar-RTL, ja-CJK, forced) ────────────────────────────────────────
gen_subs(){
  mkdir -p "$MED/text"
  _vtt(){ printf 'WEBVTT\n\n00:00:03.000 --> 00:00:08.000\n%s\n\n00:02:05.000 --> 00:02:10.000\n%s\n' "$1" "$2" > "$MED/text/$3"; }
  # English: real SDH captions (committed SRT → WebVTT). es/ar/ja stay short demo placeholders — we only
  # have a real English track, and their point is to demonstrate track *selection* (RTL/CJK rendering).
  local en_srt="$SCRIPT_DIR/assets/bbb.en.srt"
  if [[ -f "$en_srt" ]]; then
    "$FFMPEG" -y -v error -i "$en_srt" "$MED/text/subs.en.vtt"
  else
    _vtt "A giant rabbit wakes in the meadow." "The rodents arrive." subs.en.vtt
  fi
  _vtt "Un conejo gigante despierta en el prado." "Llegan los roedores." subs.es.vtt
  _vtt "أرنب عملاق يستيقظ في المرج." "وصل القوارض." subs.ar.vtt
  _vtt "巨大なウサギが草原で目を覚ます。" "げっ歯類が現れる。" subs.ja.vtt
  printf 'WEBVTT\n\n00:00:03.000 --> 00:00:06.000\n[Big Buck Bunny]\n' > "$MED/text/subs.forced.en.vtt"
}
if has_stage subs; then step "Subtitles (en/es/ar/ja + forced)"; gen_subs; fi

# ─── audio: English stereo (AAC), a surround track, a synthetic commentary ──────────────────────────────
gen_audio(){
  step "Audio tracks"
  # 1) English stereo AAC from the master's first audio stream
  "$FFMPEG" -y -v error -i "$MASTER" -map 0:a:0 -ac 2 -c:a aac -b:a 128k -vn "$WORK/a.en.mp4"
  # 2) Surround: the master's 2nd audio track (BBB ships stereo mp3 + ac3), transcoded to AAC so it plays
  #    everywhere incl. web. aformat normalises the source's 5.1(side) to plain 5.1 — without it the AAC
  #    lands with an *unknown* channel layout (no valid AAC channel configuration), which iOS AVPlayer
  #    rejects with CoreMediaErrorDomain -16170 the moment you switch to the track.
  if (( $("$FFPROBE" -v error -select_streams a -show_entries stream=index -of csv=p=0 "$MASTER" | wc -l) >= 2 )); then
    "$FFMPEG" -y -v error -i "$MASTER" -map 0:a:1 -af "aformat=channel_layouts=5.1" -c:a aac -b:a 256k -vn "$WORK/a.surround.mp4"
  fi
  # 3) Synthetic "commentary" — a steady tone so track *selection* is obvious by ear
  "$FFMPEG" -y -v error -f lavfi -i "sine=frequency=330:duration=$(( DURATION_MS/1000 ))" -c:a aac -b:a 96k "$WORK/a.commentary.mp4"
  # Opus copy of English for the WebM path
  "$FFMPEG" -y -v error -i "$WORK/a.en.mp4" -c:a libopus -b:a 128k "$WORK/a.en.opus.webm"
}
has_stage audio && gen_audio

# ─── encode: labelled renditions. make_fg writes a per-rung info board; the filtergraph goes in a script
#     file (dodges shell %-mangling) and uses colon-free %{pts}/%{n} (dodges the filtergraph : clash). ────
make_fg(){ # w h codec bitrate  -> writes label_$TAG.txt + fg_$TAG.txt in $WORK, echoes fg path
  local w=$1 h=$2 codec=$3 br=$4
  local tag="${codec}_${h}"
  printf 'BBB   %s   %sx%s   %s' "$codec" "$w" "$h" "$br" > "$WORK/label_$tag.txt"
  cat > "$WORK/fg_$tag.txt" <<EOF
scale=$w:$h,drawtext=fontfile=$FONT:textfile=label_$tag.txt:x=24:y=24:fontsize=$(( h/22 )):fontcolor=white:box=1:boxcolor=black@0.5:boxborderw=12,drawtext=fontfile=$FONT:text=t=%{pts} s   f=%{n}:x=24:y=h-$(( h/16 )):fontsize=$(( h/22 )):fontcolor=white:box=1:boxcolor=black@0.5:boxborderw=12
EOF
  echo "fg_$tag.txt"
}
enc(){ # runs ffmpeg from $WORK so the relative textfile= in the fg resolves
  ( cd "$WORK" && "$FFMPEG" "$@" ); }
KF=(-force_key_frames "expr:gte(t,n_forced*$SEGDUR)")

encode_all(){
  step "Encoding labelled renditions (AVC ladder + HEVC/VP9/AV1)"
  local row name wh br mr bs w h fg
  # HEVC_ONLY=1 re-encodes just the HEVC rungs (e.g. to re-tune their VBV) without redoing the slow AVC 2160
  # and AV1 passes; package globs whatever v.* files exist, so the untouched rungs carry through unchanged.
  if [[ -z "${HEVC_ONLY:-}" ]]; then
  for row in "${AVC_LADDER[@]}"; do
    read -r name wh br mr bs <<<"$row"; w=${wh%x*}; h=${wh#*x}
    if (( h > MH )); then echo "  skip AVC $name — master is only ${MH}p (no upscaling)"; continue; fi
    fg=$(make_fg "$w" "$h" AVC "$br")
    echo "  AVC $name"
    enc -y -v error -i "$MASTER" -filter_script:v "$fg" \
      -c:v libx264 -profile:v high -preset "$PRESET" -b:v "$br" -maxrate "$mr" -bufsize "$bs" \
      -pix_fmt yuv420p "${KF[@]}" -an -movflags +faststart "$WORK/v.avc.$h.mp4"
  done
  fi
  # HEVC 1080 (hvc1 tag for Apple). VBV-capped like the AVC ladder: without -maxrate/-bufsize, x265's peak
  # runs ~3x the average, and AVPlayer's ABR reads the HLS BANDWIDTH (peak) attribute — an unbounded peak
  # makes it refuse to climb. Keep peak < 2x average (Apple HLS Authoring Spec).
  fg=$(make_fg 1920 1080 HEVC 4000k); echo "  HEVC 1080p"
  enc -y -v error -i "$MASTER" -filter_script:v "$fg" -c:v libx265 -tag:v hvc1 -preset "$PRESET" \
    -x265-params "keyint=$KEYINT:min-keyint=$KEYINT:scenecut=0" \
    -b:v 4000k -maxrate 5000k -bufsize 8000k -pix_fmt yuv420p -an "$WORK/v.hevc.1080.mp4"
  # HEVC 2160 — 4K for Apple devices, which decode 4K HEVC but NOT our 4K60 AVC rung. Guarded like the AVC
  # ladder so a 1080p master doesn't upscale. Chrome (no HEVC) still gets 4K from the AVC 2160 rung in the
  # same master, so each engine picks its decodable 4K.
  # Peak is held to 10M (not the ~15M the content would take) so the jump from the 1080p rung is ~2x, not 3x:
  # native ABRs (AVPlayer especially) won't climb a 3x gap, so a friendlier peak is what lets iOS reach 4K.
  if (( MH >= 2160 )); then
    fg=$(make_fg 3840 2160 HEVC 8000k); echo "  HEVC 2160p"
    enc -y -v error -i "$MASTER" -filter_script:v "$fg" -c:v libx265 -tag:v hvc1 -preset "$PRESET" \
      -x265-params "keyint=$KEYINT:min-keyint=$KEYINT:scenecut=0" \
      -b:v 8000k -maxrate 10000k -bufsize 16000k -pix_fmt yuv420p -an "$WORK/v.hevc.2160.mp4"
  fi
  if [[ -z "${HEVC_ONLY:-}" ]]; then
  # VP9 1080 -> webm
  fg=$(make_fg 1920 1080 VP9 3500k); echo "  VP9 1080p"
  # constrained quality (-crf caps size, -b:v caps rate); -row-mt/-cpu-used keep libvpx-vp9 from crawling
  enc -y -v error -i "$MASTER" -filter_script:v "$fg" -c:v libvpx-vp9 -crf 32 -b:v 3500k -g $KEYINT \
    -keyint_min $KEYINT -deadline good -cpu-used 3 -row-mt 1 -pix_fmt yuv420p -an "$WORK/v.vp9.1080.webm"
  # AV1 720 (SVT-AV1; kept at 720 because AV1 encode is slow)
  fg=$(make_fg 1280 720 AV1 1800k); echo "  AV1 720p (slow)"
  enc -y -v error -i "$MASTER" -filter_script:v "$fg" -c:v libsvtav1 -preset 8 -crf 32 \
    -svtav1-params "keyint=$KEYINT" -g $KEYINT -pix_fmt yuv420p -an "$WORK/v.av1.720.mp4"
  fi
}
has_stage encode && encode_all

# ─── package: CMAF fMP4 → HLS + DASH from ONE set of segments (Shaka Packager) ──────────────────────────
pkg_video_streams(){ # AVC + HEVC only — the codecs HLS carries. VP9/AV1 ship as WebM/progressive (below),
  local f tag                              # since putting them in an HLS master is non-conformant.
  for f in "$WORK"/v.avc.*.mp4 "$WORK"/v.hevc.*.mp4; do
    [[ -f "$f" ]] || continue
    tag=$(basename "$f"); tag=${tag#v.}; tag=${tag%.*}   # e.g. avc.1080
    tag=${tag/./_}
    printf ' in=%s,stream=video,init_segment=%s/cmaf/%s/init.mp4,segment_template=%s/cmaf/%s/$Number$.m4s' \
      "$f" "$MED" "$tag" "$MED" "$tag"
  done
}
# Shaka Packager writes the init/segment paths it is given verbatim into the manifests; the absolute
# paths above therefore make the DASH .mpd and HLS media playlists reference /Users/… segment URLs, which
# 404 when the manifest is served from anywhere but this machine. Rewrite each manifest's segment refs to
# be relative to its own directory ($MED/dash/manifest.mpd → ../cmaf/…), leaving the already-relative
# master-playlist and stream references untouched.
relativize_manifests(){
  local d m base
  for d in "$@"; do
    while IFS= read -r m; do
      base=$(cd "$(dirname "$m")/.." && pwd)
      sed -i '' "s#${base}/#../#g" "$m"
    done < <(find "$d" \( -name '*.mpd' -o -name '*.m3u8' \))
  done
}
package_streams(){
  step "Packaging CMAF → HLS + DASH"
  local args; args=$(pkg_video_streams)
  # audio (English) + subtitles as HLS/DASH renditions
  args+=" in=$WORK/a.en.mp4,stream=audio,language=en,init_segment=$MED/cmaf/a_en/init.mp4,segment_template=$MED/cmaf/a_en/\$Number\$.m4s,hls_group_id=audio,hls_name=English"
  args+=" in=$WORK/a.commentary.mp4,stream=audio,language=en,init_segment=$MED/cmaf/a_com/init.mp4,segment_template=$MED/cmaf/a_com/\$Number\$.m4s,hls_group_id=audio,hls_name=Commentary"
  [[ -f "$WORK/a.surround.mp4" ]] && args+=" in=$WORK/a.surround.mp4,stream=audio,language=en,init_segment=$MED/cmaf/a_sur/init.mp4,segment_template=$MED/cmaf/a_sur/\$Number\$.m4s,hls_group_id=audio,hls_name=Surround"
  local s up
  for s in en es ar ja; do
    up=$(printf '%s' "$s" | tr '[:lower:]' '[:upper:]')   # ${s^^} is bash 4+; macOS ships bash 3.2
    args+=" in=$MED/text/subs.$s.vtt,stream=text,language=$s,segment_template=$MED/cmaf/text_$s/\$Number\$.vtt,hls_group_id=subs,hls_name=$up"
  done
  # shellcheck disable=SC2086
  # --generate_static_live_mpd: with segment_template set, Shaka defaults to a *dynamic* (live) MPD, which
  # players open at the live edge (i.e. the end). This forces a static VOD MPD with mediaPresentationDuration.
  "$PACKAGER" $args --segment_duration "$SEGDUR" --generate_static_live_mpd \
    --hls_master_playlist_output "$MED/hls/master.m3u8" \
    --mpd_output "$MED/dash/manifest.mpd"
  relativize_manifests "$MED/hls" "$MED/dash"
}
has_stage package && package_streams

# ─── clearkey: a self-hostable encrypted variant (web Chrome/FF + Android; iOS is FairPlay-only) ─────────
clearkey_variant(){
  step "ClearKey-encrypted variant"
  local kid key; kid=$(openssl rand -hex 16); key=$(openssl rand -hex 16)
  mkdir -p "$MED/clearkey"
  printf '{ "keyId": "%s", "key": "%s", "note": "ClearKey — deliver these to the player (Shaka clearKeys / ExoPlayer). Demo only." }\n' \
    "$kid" "$key" > "$MED/clearkey/keys.json"
  "$PACKAGER" \
    "in=$WORK/v.avc.1080.mp4,stream=video,init_segment=$MED/clearkey/cmaf/v/init.mp4,segment_template=$MED/clearkey/cmaf/v/\$Number\$.m4s" \
    "in=$WORK/a.en.mp4,stream=audio,init_segment=$MED/clearkey/cmaf/a/init.mp4,segment_template=$MED/clearkey/cmaf/a/\$Number\$.m4s" \
    --enable_raw_key_encryption --keys "label=:key_id=$kid:key=$key" --protection_scheme cbcs --clear_lead 0 \
    --generate_static_live_mpd \
    --hls_master_playlist_output "$MED/clearkey/hls/master.m3u8" \
    --mpd_output "$MED/clearkey/dash/manifest.mpd"
  relativize_manifests "$MED/clearkey/hls" "$MED/clearkey/dash"
  echo "  key written to $MED/clearkey/keys.json"
}
has_stage clearkey && clearkey_variant

# ─── progressive: single-file MP4 (AVC1080 + audio + chapters + faststart) and WebM (VP9 + Opus) ─────────
progressive(){
  step "Progressive MP4 + WebM"
  mkdir -p "$MED/progressive"
  "$FFMPEG" -y -v error -i "$WORK/v.avc.1080.mp4" -i "$WORK/a.en.mp4" -i "$WORK/chapters.ffmeta" \
    -map 0:v -map 1:a -map_chapters 2 -c copy -movflags +faststart "$MED/progressive/bbb-1080p.mp4"
  "$FFMPEG" -y -v error -i "$WORK/v.vp9.1080.webm" -i "$WORK/a.en.opus.webm" \
    -map 0:v -map 1:a -c copy "$MED/progressive/bbb-1080p.webm"
  # AV1 as a progressive MP4 (its rung isn't in the HLS/DASH ladder), so it's delivered rather than orphaned
  [[ -f "$WORK/v.av1.720.mp4" ]] && "$FFMPEG" -y -v error -i "$WORK/v.av1.720.mp4" -i "$WORK/a.en.mp4" \
    -map 0:v -map 1:a -c copy -movflags +faststart "$MED/progressive/bbb-720p-av1.mp4"
}
has_stage progressive && progressive

# ─── chaptered: native chapters for the local-files SDK path (mp4 chpl + mkv) ────────────────────────────
chaptered(){
  step "Chaptered MP4 + MKV (native container chapters)"
  mkdir -p "$MED/chapters"
  "$FFMPEG" -y -v error -i "$MED/progressive/bbb-1080p.mp4" -i "$WORK/chapters.ffmeta" \
    -map 0:v -map 0:a -map_metadata 1 -map_chapters 1 -c copy "$MED/chapters/bbb-chapters.mp4"
  "$FFMPEG" -y -v error -i "$MED/progressive/bbb-1080p.mp4" -i "$WORK/chapters.ffmeta" \
    -map 0:v -map 0:a -map_metadata 1 -map_chapters 1 -c copy "$MED/chapters/bbb-chapters.mkv"
  "$FFPROBE" -v error -show_chapters -of csv=p=0 "$MED/chapters/bbb-chapters.mp4" | awk -F, '{printf "  %8.1fs  %s\n",$4,$7}'
}
has_stage chaptered && chaptered

# ─── thumbnails: trickplay sprite sheets + WebVTT, one frame per THUMB_INTERVAL from the CLEAN master ─────
# Not tied to the video ladder — these are frame grabs, so re-encoding is unnecessary to (re)generate them.
# The WebVTT #xywh crops are the de-facto trickplay format (Shaka-native); a chapter thumbnail is just the
# tile nearest a chapter start, so this doubles as chapter artwork.
gen_thumbnails(){
  local dir="$MED/thumbnails"; mkdir -p "$dir"
  local cols=10 rows=10 per=$((10 * 10))
  # One JPEG per interval, scaled to THUMB_WIDTH (aspect-preserved, even height), tiled cols×rows per sheet.
  # -start_number 0 so sheets are sheet_000.jpg… (image2 defaults to 1), matching the 0-based VTT below.
  "$FFMPEG" -y -v error -i "$MASTER" \
    -vf "fps=1/${THUMB_INTERVAL},scale=${THUMB_WIDTH}:-2,tile=${cols}x${rows}" \
    -start_number 0 "$dir/sheet_%03d.jpg"
  # Derive the true tile size from a rendered sheet, so #xywh matches ffmpeg's aspect rounding exactly.
  local sw sh
  read -r sw sh < <("$FFPROBE" -v error -select_streams v:0 -show_entries stream=width,height \
    -of csv=p=0 "$dir/sheet_000.jpg" | tr ',' ' ')
  local w=$((sw / cols)) h=$((sh / rows))
  local vtt="$dir/thumbnails.vtt"; printf 'WEBVTT\n' > "$vtt"
  local total=$(( (DURATION_MS / 1000 + THUMB_INTERVAL - 1) / THUMB_INTERVAL )) i
  for ((i = 0; i < total; i++)); do
    local start=$((i * THUMB_INTERVAL * 1000)) end=$(((i + 1) * THUMB_INTERVAL * 1000))
    (( end > DURATION_MS )) && end=$DURATION_MS
    local pos=$((i % per)) x y
    x=$(( (pos % cols) * w )); y=$(( (pos / cols) * h ))
    printf '\n%s --> %s\nsheet_%03d.jpg#xywh=%d,%d,%d,%d\n' \
      "$(ms2vtt "$start")" "$(ms2vtt "$end")" "$((i / per))" "$x" "$y" "$w" "$h" >> "$vtt"
  done
  echo "  ${total} thumbs @ ${w}x${h}, every ${THUMB_INTERVAL}s → $(ls "$dir"/sheet_*.jpg | wc -l | tr -d ' ') sheet(s)"
}
has_stage thumbnails && { step "Trickplay thumbnails (${THUMB_INTERVAL}s sprites + WebVTT)"; gen_thumbnails; }

# ─── catalogue: a ready-to-paste catalogue.json entry ────────────────────────────────────────────────────
catalogue(){
  step "catalogue.json snippet"
  { echo '{'
    echo '  "id": "bbb-demo",'
    echo '  "title": "Big Buck Bunny (Outis demo build)",'
    echo '  "label": "Self-hosted BBB — labelled ABR ladder, multi-audio, subs, chapters",'
    echo '  "url": "https://<PAGES-OR-R2>/media/hls/master.m3u8",'
    echo '  "mimeType": "HLS",'
    echo '  "chapters": ['
    local i n=${#CHAPTERS[@]}
    for ((i=0;i<n;i++)); do
      local start title; read -r start title <<< "${CHAPTERS[i]}"
      printf '    { "startMs": %s, "title": "%s" }%s\n' "$start" "$title" "$([[ $i -lt $((n-1)) ]] && echo ,)"
    done
    echo '  ],'
    echo '  "note": "CC-BY 3.0 Blender Foundation. Chapters need SDK #46 on streams/web; native chapters (mp4/mkv) work today on Android/iOS local."'
    echo '}'
  } > "$MED/catalogue.entry.json"
  cat "$MED/catalogue.entry.json"
}
has_stage catalogue && catalogue

# ─── credits: CC-BY attribution (required — Blender content) ─────────────────────────────────────────────
credits(){
  step "CREDITS.md"
  cat > "$MED/CREDITS.md" <<'EOF'
# Demo media credits

All demo media is derived from **Big Buck Bunny** © 2008 Blender Foundation, licensed
**CC-BY 3.0** (https://creativecommons.org/licenses/by/3.0/). Source: https://peach.blender.org.

**Modifications:** re-encoded to AVC/HEVC/VP9/AV1; packaged as CMAF HLS/DASH, progressive MP4/WebM;
a technical info board (resolution/codec/bitrate/timecode) is burned into the video; synthetic audio and
subtitle tracks and chapter markers were added for testing. These modifications are shared under CC-BY 3.0.
EOF
  cat "$MED/CREDITS.md"
}
has_stage credits && credits

step "Done — output under $OUT/media"
echo "Next: host media/{hls,dash,cmaf,text,progressive,clearkey,thumbnails} (segments → R2, manifests/vtt → Pages),"
echo "paste media/catalogue.entry.json into sample/catalogue.json, and ship media/CREDITS.md."
