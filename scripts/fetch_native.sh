#!/usr/bin/env bash
# Pobiera źródła llama.cpp i whisper.cpp do modułów natywnych.
set -e
cd "$(dirname "$0")/.."
[ -d llama/src/main/cpp/llama.cpp ] || git clone --depth 1 https://github.com/ggml-org/llama.cpp llama/src/main/cpp/llama.cpp
[ -d whisper/src/main/cpp/whisper.cpp ] || git clone --depth 1 https://github.com/ggml-org/whisper.cpp whisper/src/main/cpp/whisper.cpp
echo "OK"
