#!/bin/bash
# EC2를 처음 만들 때 cloud-init이 root로 한 번 실행한다. 로그: /var/log/cloud-init-output.log
# docs/deploy/server-setup.md의 사전 준비와 1단계다. 2단계(설정 파일)부터는 사람이 이어서 한다.
set -euxo pipefail

# 첫 부팅 때 unattended-upgrades가 apt 잠금을 잡고 있을 수 있다.
APT="apt-get -o DPkg::Lock::Timeout=300"

timedatectl set-timezone Asia/Seoul

# Docker Engine + Compose 플러그인: Docker 공식 apt 저장소(snap 버전은 /opt 마운트가 막혀 쓸 수 없다)
$APT update
$APT install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
$APT update
$APT install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 1. 계정·디렉터리·네트워크
useradd --create-home --shell /bin/bash --groups docker deploy
install -d -o deploy -g deploy /opt/hapbang /opt/hapbang/env
docker network create hapbang-edge
