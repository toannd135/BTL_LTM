#!/usr/bin/env bash
# Sao luu du lieu SeaweedFS (VM-B) bang snapshot data disk cua Azure. Chay tu may co `az login`.
# Dung: scripts/backup-seaweedfs.sh [prefix=ftp] [so-ban-giu-lai=7]
# Snapshot nam trong cung resource group => `terraform destroy` van xoa chung. Muon giu sau destroy: sao chep sang RG khac
# (az snapshot create --source <snapshot-id> -g <rg-khac>) hoac chuyen sang storage account.
# Nen dung (consistent) hon: dung seaweedfs 1 phut truoc khi snapshot (ssh vao VM-B: docker compose stop seaweedfs).
set -euo pipefail
PREFIX="${1:-ftp}"
KEEP="${2:-7}"
RG="rg-${PREFIX}"
DISK="disk-${PREFIX}-storage"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

az snapshot create -g "$RG" -n "snap-${PREFIX}-seaweedfs-${STAMP}" \
  --source "$(az disk show -g "$RG" -n "$DISK" --query id -o tsv)" \
  --incremental true --tags purpose=seaweedfs-backup -o none
echo "Da tao snapshot snap-${PREFIX}-seaweedfs-${STAMP}"

# giu lai KEEP ban moi nhat
az snapshot list -g "$RG" --query "[?tags.purpose=='seaweedfs-backup'] | sort_by(@, &timeCreated) | [].name" -o tsv \
  | head -n -"$KEEP" | while read -r old; do
      [ -n "$old" ] && az snapshot delete -g "$RG" -n "$old" && echo "Xoa snapshot cu $old"
    done
