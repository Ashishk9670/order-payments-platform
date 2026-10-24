# GitOps (Argo CD)

Git is the source of truth for what runs in the cluster; nobody runs `helm upgrade` by hand.

```
kubectl apply -n argocd -f deploy/gitops/argocd/project.yaml
kubectl apply -n argocd -f deploy/gitops/argocd/app-staging.yaml     # auto-syncs, self-heals
kubectl apply -n argocd -f deploy/gitops/argocd/app-production.yaml  # manual sync
```

Promotion flow: CI passes on `main` -> the Release workflow pushes, scans (Trivy) and signs (cosign) `sha-<commit>` images ->
a pull request sets `image.tag` in `app-staging.yaml`, then (after staging is healthy) in `app-production.yaml`.
Verify a signature before promoting:

```
cosign verify ghcr.io/ashishk9670/order-payments-platform/order-service:sha-<commit> \
  --certificate-identity-regexp 'https://github.com/Ashishk9670/order-payments-platform/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Secrets (DB password, PSP key, client secret) are referenced by name (`existingSecret`) and managed outside Git
(External Secrets, Sealed Secrets or your cloud's secret manager). Edit the issuer URLs in `values-*.yaml` for your domain.
