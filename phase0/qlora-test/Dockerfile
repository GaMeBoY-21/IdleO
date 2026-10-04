# Phase 0 QLoRA test job, and the first batch template.
# Base: pytorch 2.8.0 with CUDA 12.8 and cuDNN 9, pinned by digest (amd64).
FROM pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime@sha256:417bd75df6365104c283ea4c1651fb3530d9eb5a4c2fafa51943cff2a94e6385

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

COPY requirements.txt /app/requirements.txt
RUN pip install -r /app/requirements.txt

COPY train.py /app/train.py

# Uid 10001 has no home and no passwd entry, and the root filesystem is
# read-only at runtime: point HOME and the user name somewhere harmless.
# train.py sends the Hugging Face and other caches into /work or /tmp.
ENV HOME=/tmp \
    USER=renter \
    LOGNAME=renter

USER 10001:10001
WORKDIR /work
ENTRYPOINT ["python", "/app/train.py"]
