#!/bin/sh
# Imports the images baked into the distribution into containerd, once (idleo-images.service, after containerd starts
# on the distribution's first boot): only the GPU-check image for now, the self-test's container check.
set -eu
name=docker.io/idleo/gpu-check:local
if ctr --namespace default images ls --quiet | grep -qx "$name"; then
  exit 0
fi
ctr --namespace default images import --platform "linux/$(dpkg --print-architecture)" /opt/idleo/images/gpu-check.tar
