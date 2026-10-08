#!/usr/bin/env bash
# Create one lossless DeepZoom ZIP that the RAPID server can serve in all modes.
set -euo pipefail

usage() {
    echo "Uso: $0 entrada.png salida.zip [--png-compression=0..9] [--tile-size=N]" >&2
    exit 2
}

[[ $# -ge 2 ]] || usage
input=$1
output=$2
shift 2
png_compression=0
tile_size=512
for option in "$@"; do
    case "$option" in
        --png-compression=*) png_compression=${option#*=} ;;
        --tile-size=*) tile_size=${option#*=} ;;
        *) usage ;;
    esac
done

[[ -f "$input" && "$output" == *.zip ]] || usage
[[ "$png_compression" =~ ^[0-9]$ ]] || usage
[[ "$tile_size" =~ ^[0-9]+$ ]] && (( tile_size >= 64 && tile_size <= 1024 )) || usage
[[ ! -e "$output" ]] || { echo "La salida ya existe: $output" >&2; exit 1; }

vips_bin=${VIPS_BIN:-vips}
command -v "$vips_bin" >/dev/null || {
    echo "Falta libvips (comando vips). Instala libvips-tools o define VIPS_BIN." >&2
    exit 1
}

output_dir=$(dirname -- "$output")
output_name=$(basename -- "$output")
mkdir -p -- "$output_dir"
temp_dir=$(mktemp -d "$output_dir/.vips-rapid.XXXXXX")
trap 'rm -rf -- "$temp_dir"' EXIT
temp_output="$temp_dir/$output_name"

started=$SECONDS
"$vips_bin" dzsave "${input}[unlimited]" "$temp_output" \
    --layout dz --suffix ".png[compression=$png_compression]" \
    --tile-size "$tile_size" --overlap 0 --depth onetile \
    --container zip --compression 0 --skip-blanks -1

[[ -s "$temp_output" ]] || { echo "libvips no generó el ZIP" >&2; exit 1; }
mv -- "$temp_output" "$output"
printf 'OK  %s -> %s  (%s bytes, %s s, tile=%s, PNG compression=%s)\n' \
    "$input" "$output" "$(stat -c %s -- "$output")" "$((SECONDS - started))" \
    "$tile_size" "$png_compression"
