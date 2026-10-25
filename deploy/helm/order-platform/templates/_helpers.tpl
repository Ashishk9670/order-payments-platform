{{/* Name of the resources for one service of the release. Args: dict "root" $ "name" <service> */}}
{{- define "op.fullname" -}}
{{- printf "%s-%s" .root.Release.Name .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "op.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "op.labels" -}}
{{ include "op.selectorLabels" . }}
app.kubernetes.io/part-of: order-platform
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end -}}

{{/* In-cluster base URL of another service of this release. Args: dict "root" $ "name" <service> */}}
{{- define "op.url" -}}
{{- $svc := index .root.Values.services .name -}}
{{- printf "http://%s:%d" (include "op.fullname" .) (int $svc.port) -}}
{{- end -}}

{{/* Environment of one service. Args: dict "root" $ "name" <service> "svc" <its values> */}}
{{- define "op.env" -}}
{{- $v := .root.Values -}}
- { name: SERVER_SHUTDOWN, value: graceful }
- { name: TRACING_SAMPLING, value: {{ $v.tracing.samplingProbability | quote }} }
{{- if $v.tracing.otlpEndpoint }}
- { name: OTLP_TRACING_ENDPOINT, value: {{ $v.tracing.otlpEndpoint | quote }} }
{{- end }}
{{- if .svc.database }}
- { name: DB_URL, value: {{ printf "jdbc:postgresql://%s:%v/%s?%s" $v.global.postgres.host $v.global.postgres.port .svc.database $v.global.postgres.jdbcParams | quote }} }
- { name: DB_USER, value: {{ $v.global.postgres.user | quote }} }
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef: { name: {{ $v.global.postgres.existingSecret }}, key: {{ $v.global.postgres.passwordKey }} }
{{- end }}
{{- if ne .name "api-gateway" }}
{{- if ne .name "catalog-service" }}
- { name: KAFKA_BOOTSTRAP, value: {{ $v.global.kafka.bootstrapServers | quote }} }
{{- end }}
{{- end }}
{{- if ne .name "api-gateway" }}
{{- if $v.security.issuerUri }}
- { name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI, value: {{ $v.security.issuerUri | quote }} }
{{- end }}
{{- if $v.security.jwkSetUri }}
- { name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI, value: {{ $v.security.jwkSetUri | quote }} }
{{- end }}
{{- if $v.security.jwt.existingSecret }}
- name: JWT_SECRET
  valueFrom:
    secretKeyRef: { name: {{ $v.security.jwt.existingSecret }}, key: {{ $v.security.jwt.secretKey }} }
{{- end }}
{{- end }}
{{- if or (eq .name "order-service") (eq .name "inventory-service") (eq .name "api-gateway") }}
- { name: REDIS_HOST, value: {{ $v.global.redis.host | quote }} }
{{- end }}
{{- if eq .name "api-gateway" }}
- { name: ORDER_SERVICE_URL, value: {{ include "op.url" (dict "root" .root "name" "order-service") | quote }} }
- { name: GATEWAY_RATE_REPLENISH, value: {{ $v.rateLimit.gateway.replenishPerSecond | int64 | quote }} }
- { name: GATEWAY_RATE_BURST, value: {{ $v.rateLimit.gateway.burst | int64 | quote }} }
{{- end }}
{{- if eq .name "order-service" }}
- { name: CATALOG_URL, value: {{ include "op.url" (dict "root" .root "name" "catalog-service") | quote }} }
- { name: RATELIMIT_REQUESTS_PER_MINUTE, value: {{ $v.rateLimit.orderServicePerIpPerMinute | int64 | quote }} }
- { name: CUSTOMER_ID_CLAIM, value: {{ $v.security.customerIdClaim | quote }} }
- { name: DEV_TOKEN_ENABLED, value: {{ $v.security.devTokenEnabled | quote }} }
{{- if $v.security.serviceAccount.tokenUri }}
- { name: SERVICE_ACCOUNT_TOKEN_URI, value: {{ $v.security.serviceAccount.tokenUri | quote }} }
- { name: SERVICE_ACCOUNT_CLIENT_ID, value: {{ $v.security.serviceAccount.clientId | quote }} }
- name: SERVICE_ACCOUNT_CLIENT_SECRET
  valueFrom:
    secretKeyRef: { name: {{ required "security.serviceAccount.existingSecret is required with a tokenUri" $v.security.serviceAccount.existingSecret }}, key: {{ $v.security.serviceAccount.secretKey }} }
{{- end }}
{{- end }}
{{- if eq .name "payment-service" }}
- { name: PAYMENT_GATEWAY, value: {{ $v.payment.gateway | quote }} }
{{- if eq $v.payment.gateway "http" }}
- { name: PSP_URL, value: {{ required "payment.psp.url is required when payment.gateway=http" $v.payment.psp.url | quote }} }
{{- if $v.payment.psp.existingSecret }}
- name: PSP_API_KEY
  valueFrom:
    secretKeyRef: { name: {{ $v.payment.psp.existingSecret }}, key: {{ $v.payment.psp.apiKeyKey }} }
{{- end }}
{{- end }}
{{- if $v.payment.psp.webhookExistingSecret }}
- name: PSP_WEBHOOK_SECRET
  valueFrom:
    secretKeyRef: { name: {{ $v.payment.psp.webhookExistingSecret }}, key: {{ $v.payment.psp.webhookSecretKey }} }
{{- end }}
{{- end }}
{{- end -}}
