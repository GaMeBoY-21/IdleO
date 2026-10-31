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
  - name: VALKEY_PASSWORD
    valueFrom:
      secretKeyRef:
        name: {{ .Values.valkey.secret }}
        key: password
{{- end }}

{{/* The ingress's pods (Traefik), as a network policy's peer: the only way in for browsers and API clients. */}}
{{- define "idleo.ingressPeer" -}}
- namespaceSelector:
    matchLabels:
      kubernetes.io/metadata.name: {{ .Values.networkPolicy.ingressNamespace }}
  podSelector:
    matchLabels:
      {{- toYaml .Values.networkPolicy.ingressPodLabels | nindent 6 }}
{{- end }}

{{/* The cluster's DNS (CoreDNS), as a network policy's peer. */}}
{{- define "idleo.dnsPeer" -}}
- namespaceSelector:
    matchLabels:
      kubernetes.io/metadata.name: {{ .Values.networkPolicy.dns.namespace }}
  podSelector:
    matchLabels:
      {{- toYaml .Values.networkPolicy.dns.podLabels | nindent 6 }}
{{- end }}

{{/* The internet, as a network policy's peer: every address but the server's and the cluster's own networks and the
cloud's metadata address (ADR 0048). */}}
{{- define "idleo.internetPeer" -}}
- ipBlock:
    cidr: 0.0.0.0/0
    except:
      {{- toYaml .Values.networkPolicy.internetExcept | nindent 6 }}
{{- end }}

{{/* The Kubernetes API, as network policy rules: its Service's address, and the server's own port behind it, which is
what a policy sees once the Service has been resolved. */}}
{{- define "idleo.apiServerEgress" -}}
- to:
    - ipBlock:
        cidr: {{ .Values.networkPolicy.apiServer.serviceAddress }}/32
  ports:
    - { port: 443, protocol: TCP }
- to:
    - ipBlock:
        cidr: 0.0.0.0/0
  ports:
    - { port: {{ .Values.networkPolicy.apiServer.port }}, protocol: TCP }
{{- end }}

{{/* What the control plane's own pods, and operator commands (the same application), may reach (ADR 0048,
templates/network.yaml): the database, Valkey, DNS, the internet on HTTPS and the mail server; and on the Mac,
development's stand-ins (development.yaml). */}}
{{- define "idleo.controlPlaneClient" -}}
idleo.in/database-client: "true"
idleo.in/valkey-client: "true"
idleo.in/dns-client: "true"
idleo.in/internet-client: "true"
idleo.in/mail-client: "true"
idleo.in/development-client: "true"
{{- end }}
