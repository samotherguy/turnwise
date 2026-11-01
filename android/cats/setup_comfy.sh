#!/bin/bash
# Installs ComfyUI into ~/.local/opt/ComfyUI with its own venv (CUDA torch), plus rembg for cutting cats out.
# Run with:  bash ~/cats-art/setup_comfy.sh
set -e
mkdir -p ~/.local/opt && cd ~/.local/opt
[ -d ComfyUI ] || git clone --depth 1 https://github.com/comfyanonymous/ComfyUI.git
cd ComfyUI
[ -x venv/bin/python ] || python3 -m venv venv
venv/bin/pip install -q --upgrade pip
venv/bin/pip install -q torch torchvision --index-url https://download.pytorch.org/whl/cu130 \
  || venv/bin/pip install -q torch torchvision --index-url https://download.pytorch.org/whl/cu128
venv/bin/pip install -q -r requirements.txt
venv/bin/pip install -q "rembg[cpu]" onnxruntime pillow requests
# model files downloaded separately into ~/cats-art/models; link them where ComfyUI looks
mkdir -p models/diffusion_models models/text_encoders models/vae
ln -sf ~/cats-art/models/flux1-dev-kontext_fp8_scaled.safetensors models/diffusion_models/
ln -sf ~/cats-art/models/clip_l.safetensors models/text_encoders/
ln -sf ~/cats-art/models/t5xxl_fp8_e4m3fn_scaled.safetensors models/text_encoders/
ln -sf ~/cats-art/models/ae.safetensors models/vae/
venv/bin/python -c "import torch;print('torch',torch.__version__,'cuda ok',torch.cuda.is_available())"
echo SETUP_DONE
