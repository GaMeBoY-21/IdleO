#!/bin/sh
# The runner the image will carry once the real one exists (docs/plan/host-experience.md §6, item 2): it starts each job
# as a container with the GPU and the sandbox. Until then this says so, and fails, so nothing can mistake the image for
# one that runs jobs.
echo "idleo-runner: not installed yet; this image has no job runner" >&2
exit 1
