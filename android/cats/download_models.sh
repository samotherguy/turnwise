#!/bin/bash
# FLUX.1 Kontext dev (fp8, ComfyUI repack), text encoders and VAE. ~17 GB. Resumable.
cd ~/cats-art/models
dl() { curl -L -C - --retry 5 --retry-delay 5 -o "$2" "$1" && echo "done $2"; }
dl "https://huggingface.co/Comfy-Org/flux1-kontext-dev_ComfyUI/resolve/main/split_files/diffusion_models/flux1-dev-kontext_fp8_scaled.safetensors" flux1-dev-kontext_fp8_scaled.safetensors &
dl "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/clip_l.safetensors" clip_l.safetensors &
dl "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/t5xxl_fp8_e4m3fn_scaled.safetensors" t5xxl_fp8_e4m3fn_scaled.safetensors &
dl "https://huggingface.co/Comfy-Org/Lumina_Image_2.0_Repackaged/resolve/main/split_files/vae/ae.safetensors" ae.safetensors &
wait
ls -l
echo DOWNLOADS_DONE
