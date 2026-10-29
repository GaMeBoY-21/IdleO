{{/* An image of ours: <registry>/idleo-<name>:<tag>. */}}
{{- define "idleo.image" -}}
{{- $root := index . 0 -}}
{{- required "images.tag is required: the git commit the images were built from" $root.Values.images.tag | printf "%s/idleo-%s:%s" $root.Values.images.registry (index . 1) -}}
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
        name: {{ .Values.postgres.secret }}
        key: IDLEO_APPLICATION_PASSWORD
{{- end }}
