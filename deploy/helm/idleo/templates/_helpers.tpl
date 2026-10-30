{{/*
An image of ours. Pinned by digest when the environment gives one (staging and production give one for every image,
deploy/clusters/<env>/release.yaml): <registry>/idleo-<name>@<digest>, so only the bytes that were signed run.
Without one, <registry>/idleo-<name>:<tag> (the Mac's cluster). The tag is required either way: it names the release.
*/}}
{{- define "idleo.image" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 -}}
{{- $tag := required "images.tag is required: the git commit the images were built from" $root.Values.images.tag -}}
{{- $digest := index ($root.Values.images.digests | default dict) $name | default "" -}}
{{- if $digest -}}
{{- if not (regexMatch "^sha256:[0-9a-f]{64}$" $digest) -}}
{{- fail (printf "images.digests.%s must be sha256:<64 hex digits>, not %s" $name $digest) -}}
{{- end -}}
{{- printf "%s/idleo-%s@%s" $root.Values.images.registry $name $digest -}}
{{- else -}}
{{- printf "%s/idleo-%s:%s" $root.Values.images.registry $name $tag -}}
{{- end -}}
{{- end }}

{{- define "idleo.labels" -}}
app.kubernetes.io/name: {{ index . 1 }}
app.kubernetes.io/instance: {{ (index . 0).Release.Name }}
app.kubernetes.io/part-of: idleo
app.kubernetes.io/managed-by: {{ (index . 0).Release.Service }}
{{- end }}

{{- define "idleo.selector" -}}
app.kubernetes.io/name: {{ index . 1 }}
app.kubernetes.io/instance: {{ (index . 0).Release.Name }}
{{- end }}

{{/*
Every pod's own settings (rule 6, ADR 0045): a fixed non-root user, the runtime's default seccomp profile, no
service-account token (nothing here calls the Kubernetes API) and no service links in its environment.
*/}}
{{- define "idleo.podSecurity" -}}
automountServiceAccountToken: false
enableServiceLinks: false
securityContext:
  runAsNonRoot: true
  runAsUser: {{ . }}
  runAsGroup: {{ . }}
  fsGroup: {{ . }}
  seccompProfile:
    type: RuntimeDefault
{{- end }}

{{/* Every container's: a read-only root, every capability dropped, no way to gain privileges. */}}
{{- define "idleo.containerSecurity" -}}
securityContext:
  runAsNonRoot: true
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  privileged: false
  capabilities:
    drop: [ALL]
  seccompProfile:
    type: RuntimeDefault
{{- end }}

{{/* The control plane's environment, shared by its pods, the migration Job and operator commands. */}}
{{- define "idleo.controlPlaneEnv" -}}
envFrom:
  - configMapRef:
      name: idleo-control-plane
  - secretRef:
      name: {{ .Values.controlPlane.secret }}
env:
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: {{ .Values.database.applicationSecret }}
        key: password
{{- end }}
