#!/usr/bin/env bash
#
# build-sollevante-media.sh — Dolby Vision + HDR10 + SDR demo encode for Sol Levante
# (Netflix Open Content, CC BY 4.0 — credit "Sol Levante / Netflix" in the catalogue).
#
# DELIBERATELY SEPARATE from build-demo-media.sh (Big Buck Bunny): the DV/HDR chain is a different beast
# and BBB must stay reproducible. Nothing here touches that script or its output dir.
#
# Produces ONE HLS + DASH package with graceful fallback, via Dolby Vision **Profile 8.1** (a single
# HEVC-Main10 stream that is a valid HDR10 base AND carries the DV RPU):
#   • DV device (Safari/iOS, DV-capable Android) → Dolby Vision
#   • HDR10 device (no DV)                        → HDR10 (same Profile 8.1 track, RPU ignored)
#   • everything else                             → the SDR ladder
# Ladder (lean, to keep size down): DV/HDR10 720/1080/2160 + SDR(AVC) 720/1080.
#
# Toolchain beyond build-demo-media's ffmpeg + packager:
#   dovi_tool  — synthesize + inject the DV Profile-8.1 RPU        →  brew install dovi_tool
#   MP4Box     — mux the DV elementary stream into mp4 with a dvcC →  brew install gpac
#
# FIRST TIME: run `STAGES=selftest ./build-sollevante-media.sh` — it exercises the whole DV chain on a
# 5-second synthetic HDR clip (no 37 GB download) and greps the manifest for the DV signaling. Only once
# that's green is it worth pulling the masters.
#
# The DV-specific incantations (x265 master-display values, the dovi_tool `generate` JSON schema, and the
# MP4Box DV import) are marked VERIFY below — they can vary by tool version and are shaken out by selftest.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ─── config ──────────────────────────────────────────────────────────────────────────────────────────
FFMPEG="${FFMPEG:-$HOME/bin/ffmpeg}"
FFPROBE="${FFPROBE:-ffprobe}"
PACKAGER="${PACKAGER:-$HOME/bin/packager}"
DOVI_TOOL="${DOVI_TOOL:-dovi_tool}"
MP4BOX="${MP4BOX:-MP4Box}"
OUT="${OUT:-$PWD/sollevante-media-out}"          # gitignore this; segments are large
BASE="https://s3.amazonaws.com/download.opencontent.netflix.com/SolLevante"
HDR_MASTER_URL="${HDR_MASTER_URL:-$BASE/hdr10/SolLevante_HDR10_r2020_ST2084_UHD_24fps_1000nit.mov}"  # ~37 GB ProRes
SDR_MASTER_URL="${SDR_MASTER_URL:-$BASE/sdr/SolLevante_SDR_UHD_24fps.mov}"                            # ~15 GB ProRes
# 1 = tone-map the SDR fallback from the HDR master (single ~37 GB download — fits a ~60 GB disk).
# 0 = fetch the real 15 GB SDR grade too (needs ~57 GB; delete the HDR master before the SDR fetch to be safe).
SDR_FROM_HDR="${SDR_FROM_HDR:-1}"
SEGDUR="${SEGDUR:-4}"
PRESET="${PRESET:-medium}"                        # x265/x264 preset (veryfast to iterate, slow for quality)
STAGES="${STAGES:-fetch audio hdr dv sdr package}"  # 'selftest' is opt-in and needs no master

# Mastering-display + MaxCLL for the x265 HDR10 signaling. Sol Levante is P3-D65, ST2084, 1000 nit.
# VERIFY against the master's own metadata: `ffprobe -show_frames -read_intervals %+#1 <master>` (look for
# 'Mastering display metadata' / 'Content light level'). Defaults below are standard P3-D65 @ 1000 nit.
MASTER_DISPLAY="${MASTER_DISPLAY:-G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)}"
MAX_CLL="${MAX_CLL:-1000,400}"

WORK="$OUT/work"; MED="$OUT/media"
mkdir -p "$WORK" "$MED"

# DV/HDR10 base ladder (from the HDR master):  name  WxH  v-bitrate maxrate bufsize
HDR_LADDER=(
  "720p   1280x720   4000k  5000k  8000k"
  "1080p  1920x1080  8000k 10000k 16000k"
  "2160p  3840x2160 18000k 22000k 36000k"
)
# SDR fallback ladder (from the real SDR master), AVC, lean — no 4K (HDR-capable devices get the HDR ladder):
SDR_LADDER=(
  "720p   1280x720   2800k  3100k  5600k"
  "1080p  1920x1080  5000k  5500k 10000k"
)

step(){ printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
has_stage(){ [[ " $STAGES " == *" $1 "* ]]; }
KF=(-force_key_frames "expr:gte(t,n_forced*$SEGDUR)")

# ─── preflight ───────────────────────────────────────────────────────────────────────────────────────
[[ -x "$FFMPEG" ]] || { echo "no ffmpeg at $FFMPEG (set FFMPEG=...)"; exit 1; }
if has_stage dv || has_stage selftest; then
  command -v "$DOVI_TOOL" >/dev/null 2>&1 || { echo "dovi_tool not found — 'brew install dovi_tool' (Profile 8.1 RPU)"; exit 1; }
  command -v "$MP4BOX"    >/dev/null 2>&1 || { echo "MP4Box not found — 'brew install gpac' (dvcC muxing)"; exit 1; }
fi
if has_stage package || has_stage selftest; then
  command -v "$PACKAGER" >/dev/null 2>&1 || { echo "Shaka Packager not found (set PACKAGER=...)"; exit 1; }
fi

# x265 HDR10 elementary-stream encoder (Annex-B .hevc, ready for dovi_tool). $1 w  $2 h  $3 br  $4 mr  $5 bs  $6 out
enc_hdr10(){
  "$FFMPEG" -y -v error -i "$HDR_MASTER" -an -vf "scale=$1:$2:flags=lanczos" \
    -c:v libx265 -pix_fmt yuv420p10le -preset "$PRESET" -b:v "$3" -maxrate "$4" -bufsize "$5" "${KF[@]}" \
    -x265-params "profile=main10:hdr10=1:hdr10-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=$MASTER_DISPLAY:max-cll=$MAX_CLL:keyint=$KEYINT:min-keyint=$KEYINT:scenecut=0" \
    -f hevc "$6"
}

# ─── selftest: whole DV chain on a synthetic 5 s HDR clip — validates the tooling before any big download ─
selftest(){
  step "Self-test: synthetic HDR10 → DV 8.1 → package (no master needed)"
  local t="$WORK/selftest"; rm -rf "$t"; mkdir -p "$t/out"
  "$FFMPEG" -y -v error -f lavfi -i "testsrc2=size=1280x720:rate=24:duration=5" \
    -c:v libx265 -pix_fmt yuv420p10le -preset ultrafast \
    -x265-params "profile=main10:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=$MASTER_DISPLAY:max-cll=$MAX_CLL:keyint=48:min-keyint=48" \
    -f hevc "$t/v.hevc"
  # VERIFY: dovi_tool `generate` JSON schema (fields/`profile` handling) varies by version — see `dovi_tool generate --help`.
  cat > "$t/dv81.json" <<EOF
{ "cm_version": "V29", "length": 120, "level6": { "max_display_mastering_luminance": 1000, "min_display_mastering_luminance": 1, "max_content_light_level": ${MAX_CLL%,*}, "max_frame_average_light_level": ${MAX_CLL#*,} } }
EOF
  "$DOVI_TOOL" generate --json "$t/dv81.json" --rpu-out "$t/rpu.bin"
  "$DOVI_TOOL" inject-rpu -i "$t/v.hevc" --rpu-in "$t/rpu.bin" -o "$t/v.dv.hevc"
  "$MP4BOX" -add "$t/v.dv.hevc" -new "$t/v.dv.mp4"   # VERIFY: GPAC should auto-detect the RPU and write dvcC
  "$PACKAGER" in="$t/v.dv.mp4",stream=video,init_segment="$t/out/init.mp4",segment_template="$t/out/\$Number\$.m4s" \
    --use_dovi_supplemental_codecs --generate_static_live_mpd \
    --hls_master_playlist_output "$t/out/master.m3u8" --mpd_output "$t/out/manifest.mpd"
  step "Self-test result — the master playlist should carry DV signaling:"
  grep -iE "SUPPLEMENTAL-CODECS|dvh1|dvhe|VIDEO-RANGE" "$t/out/master.m3u8" \
    && echo "✅ DV signaling present — the chain works." \
    || echo "❌ no DV signaling — adjust the dovi_tool/MP4Box step (see VERIFY notes) before the full run."
}
if has_stage selftest; then selftest; exit 0; fi

# ─── fetch: both ProRes masters (large — HDR ~37 GB, SDR ~15 GB), cached by name ────────────────────────
fetch_master(){ local url=$1 dst="$WORK/$(basename "$1")"; [[ -f "$dst" ]] || { step "Fetching $(basename "$url") (large — be patient)"; curl -fSL -o "$dst" "$url"; }; echo "$dst"; }
if has_stage fetch; then
  HDR_MASTER=$(fetch_master "$HDR_MASTER_URL")
  has_stage sdr && [[ "$SDR_FROM_HDR" == 0 ]] && SDR_MASTER=$(fetch_master "$SDR_MASTER_URL")
fi
HDR_MASTER="${HDR_MASTER:-$WORK/$(basename "$HDR_MASTER_URL")}"
SDR_MASTER="${SDR_MASTER:-$WORK/$(basename "$SDR_MASTER_URL")}"
[[ -f "$HDR_MASTER" ]] || { echo "no HDR master at $HDR_MASTER — run the fetch stage"; exit 1; }

# fps → segment-aligned keyframes (Sol Levante is 24 fps)
FPS_RAW=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$HDR_MASTER")
FPS=$(( ${FPS_RAW%/*} / ${FPS_RAW#*/} )); KEYINT=$(( SEGDUR * FPS ))
echo "HDR master: $HDR_MASTER @ ${FPS}fps — segment ${SEGDUR}s → keyint $KEYINT"

# ─── hdr: HDR10 base ladder (10-bit HEVC, PQ, BT.2020) ─────────────────────────────────────────────────
encode_hdr(){
  step "HDR10 base ladder (10-bit HEVC PQ/BT.2020)"
  local row name wh br mr bs w h
  for row in "${HDR_LADDER[@]}"; do
    read -r name wh br mr bs <<<"$row"; w=${wh%x*}; h=${wh#*x}
    echo "  HDR10 $name"; enc_hdr10 "$w" "$h" "$br" "$mr" "$bs" "$WORK/v.hdr.$h.hevc"
  done
}
has_stage hdr && encode_hdr

# ─── dv: Dolby Vision Profile 8.1 — synthesize a static 8.1 RPU, inject into each rung, mux with dvcC ───
make_dv(){
  step "Dolby Vision Profile 8.1 (dovi_tool RPU + dvcC mux)"
  local first_h; read -r _ first_wh _ _ _ <<<"${HDR_LADDER[0]}"; first_h=${first_wh#*x}
  [[ -f "$WORK/v.hdr.$first_h.hevc" ]] || { echo "run the 'hdr' stage first"; exit 1; }
  # RPU length must equal the encoded frame count exactly. Count it off the smallest rung (fast).
  local nframes; nframes=$("$FFPROBE" -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$WORK/v.hdr.$first_h.hevc")
  cat > "$WORK/dv81.json" <<EOF
{ "cm_version": "V29", "length": $nframes, "level6": { "max_display_mastering_luminance": 1000, "min_display_mastering_luminance": 1, "max_content_light_level": ${MAX_CLL%,*}, "max_frame_average_light_level": ${MAX_CLL#*,} } }
EOF
  "$DOVI_TOOL" generate --json "$WORK/dv81.json" --rpu-out "$WORK/dv81.rpu.bin"
  local row wh h
  for row in "${HDR_LADDER[@]}"; do
    read -r _ wh _ _ _ <<<"$row"; h=${wh#*x}
    "$DOVI_TOOL" inject-rpu -i "$WORK/v.hdr.$h.hevc" --rpu-in "$WORK/dv81.rpu.bin" -o "$WORK/v.dv.$h.hevc"
    rm -f "$WORK/v.dv.$h.mp4"
    "$MP4BOX" -add "$WORK/v.dv.$h.hevc" -new "$WORK/v.dv.$h.mp4"   # GPAC auto-detects the DV RPU → dvcC
  done
}
has_stage dv && make_dv

# ─── sdr: fallback ladder (AVC) straight from the real SDR master — no tone-mapping ────────────────────
encode_sdr(){
  local src tonemap=""
  if [[ "$SDR_FROM_HDR" == 0 ]]; then
    step "SDR fallback ladder (AVC, from the real SDR master)"
    [[ -f "$SDR_MASTER" ]] || { echo "no SDR master at $SDR_MASTER — run fetch with SDR_FROM_HDR=0"; exit 1; }
    src="$SDR_MASTER"
  else
    step "SDR fallback ladder (AVC, tone-mapped from the HDR master — no separate 15 GB download)"
    src="$HDR_MASTER"
    tonemap="zscale=t=linear:npl=100,tonemap=hable,zscale=t=bt709:m=bt709:p=bt709:r=tv,"
  fi
  local row name wh br mr bs w h
  for row in "${SDR_LADDER[@]}"; do
    read -r name wh br mr bs <<<"$row"; w=${wh%x*}; h=${wh#*x}
    echo "  SDR $name"
    "$FFMPEG" -y -v error -i "$src" -an -vf "${tonemap}scale=$w:$h:flags=lanczos,format=yuv420p" \
      -c:v libx264 -profile:v high -preset "$PRESET" -b:v "$br" -maxrate "$mr" -bufsize "$bs" \
      "${KF[@]}" -movflags +faststart "$WORK/v.sdr.$h.mp4"
  done
}
has_stage sdr && encode_sdr

# ─── audio: AAC stereo from the master's embedded track (true Atmos needs Dolby's licensed encoder). ───
gen_audio(){
  step "Audio (AAC stereo)"
  if "$FFPROBE" -v error -select_streams a:0 -show_entries stream=index -of csv=p=0 "$HDR_MASTER" | grep -q .; then
    "$FFMPEG" -y -v error -i "$HDR_MASTER" -map 0:a:0 -ac 2 -c:a aac -b:a 128k -vn "$WORK/a.en.mp4"
  else
    # Master carries no embedded audio (its mix lives in SolLevante/protools/ + the Atmos ADM). Keep the
    # package valid with silence for now; wire a real down-mix from the ProTools/ADM bed later.
    echo "  (no embedded audio in the master — writing silence; TODO: down-mix from protools/ or the ADM)"
    local dur; dur=$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$HDR_MASTER")
    "$FFMPEG" -y -v error -f lavfi -i "anullsrc=r=48000:cl=stereo" -t "${dur:-257}" -c:a aac -b:a 96k "$WORK/a.en.mp4"
  fi
}
has_stage audio && gen_audio

# ─── package: one HLS + DASH, DV signaled via supplemental codecs ──────────────────────────────────────
relativize_manifests(){ local d m base; for d in "$@"; do while IFS= read -r m; do base=$(cd "$(dirname "$m")/.." && pwd); sed -i '' "s#${base}/#../#g" "$m"; done < <(find "$d" \( -name '*.mpd' -o -name '*.m3u8' \)); done; }
package(){
  step "Packaging CMAF → HLS + DASH (DV supplemental codecs)"
  local args="" row wh h
  for row in "${HDR_LADDER[@]}"; do read -r _ wh _ _ _ <<<"$row"; h=${wh#*x}
    args+=" in=$WORK/v.dv.$h.mp4,stream=video,init_segment=$MED/cmaf/dv_$h/init.mp4,segment_template=$MED/cmaf/dv_$h/\$Number\$.m4s"; done
  for row in "${SDR_LADDER[@]}"; do read -r _ wh _ _ _ <<<"$row"; h=${wh#*x}
    args+=" in=$WORK/v.sdr.$h.mp4,stream=video,init_segment=$MED/cmaf/sdr_$h/init.mp4,segment_template=$MED/cmaf/sdr_$h/\$Number\$.m4s"; done
  args+=" in=$WORK/a.en.mp4,stream=audio,language=en,init_segment=$MED/cmaf/a_en/init.mp4,segment_template=$MED/cmaf/a_en/\$Number\$.m4s,hls_group_id=audio,hls_name=English"
  # shellcheck disable=SC2086
  "$PACKAGER" $args --use_dovi_supplemental_codecs --segment_duration "$SEGDUR" --generate_static_live_mpd \
    --hls_master_playlist_output "$MED/hls/master.m3u8" --mpd_output "$MED/dash/manifest.mpd"
  relativize_manifests "$MED/hls" "$MED/dash"
}
has_stage package && package

step "Done → $MED   (catalogue: hls/master.m3u8 + dash/manifest.mpd; credit 'Sol Levante / Netflix', CC BY 4.0)"
