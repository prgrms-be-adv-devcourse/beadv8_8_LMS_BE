#!/usr/bin/env bash
set -euo pipefail
# Explicit context: never install into EC2 or an ambient current-context.
ctx=k3d-hapbang
kubectl --context "$ctx" get nodes >/dev/null
kubectl --context "$ctx" apply -k k8s/gateway/install/envoy-gateway
kubectl --context "$ctx" wait --for=create crd/gateways.gateway.networking.k8s.io --timeout=180s
kubectl --context "$ctx" wait --for=condition=Established crd/gateways.gateway.networking.k8s.io --timeout=180s
kubectl --context "$ctx" -n envoy-gateway-system wait --for=create deployment/envoy-gateway --timeout=300s
kubectl --context "$ctx" -n envoy-gateway-system rollout status deployment/envoy-gateway --timeout=300s
kubectl --context "$ctx" apply -k k8s/gateway/install/cert-manager
kubectl --context "$ctx" wait --for=create crd/certificates.cert-manager.io --timeout=180s
kubectl --context "$ctx" -n cert-manager wait --for=create deployment/cert-manager --timeout=300s
kubectl --context "$ctx" -n cert-manager rollout status deployment/cert-manager --timeout=300s
kubectl --context "$ctx" -n cert-manager rollout status deployment/cert-manager-cainjector --timeout=300s
kubectl --context "$ctx" -n cert-manager rollout status deployment/cert-manager-webhook --timeout=300s
kubectl --context "$ctx" label node k3d-hapbang-server-0 svccontroller.k3s.cattle.io/enablelb=true --overwrite
kubectl --context "$ctx" apply -k k8s/gateway/overlays/local
kubectl --context "$ctx" -n envoy-gateway-system wait --for=condition=Ready certificate/hapbang-tls --timeout=180s
kubectl --context "$ctx" -n envoy-gateway-system wait --for=condition=Programmed gateway/hapbang --timeout=180s
