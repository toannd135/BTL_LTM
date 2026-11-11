# Sao luu tu dong o du lieu SeaweedFS (VM-B) bang snapshot incremental.
# Azure for Students khong tao duoc Backup vault (Microsoft.DataProtection tra 406 NotAcceptable o eastasia,
# region khac bi policy chan) => VM-B tu chup: cron (Ansible role storage, scripts/disk-snapshot.sh) dung
# seaweedfs vai giay, goi Azure REST bang managed identity cua VM de tao snapshot, roi xoa ban cu hon N ban.
# - Snapshot nam o RG rieng rg-<prefix>-backup: xoa/tao lai VM-B khong mat ban sao luu.
# - Khoi phuc: az disk create -g rg-<prefix> -n disk-restore --source <snapshot-id>, roi gan vao VM-B tai LUN 0.

resource "azurerm_resource_group" "backup" {
  name     = "rg-${var.prefix}-backup"
  location = azurerm_resource_group.main.location
  tags     = var.tags
}

# VM-B doc duoc disk nguon (beginGetAccess) va tao/xoa snapshot trong RG backup — khong hon
resource "azurerm_role_assignment" "storage_disk_backup_reader" {
  scope                = azurerm_managed_disk.storage.id
  role_definition_name = "Disk Backup Reader"
  principal_id         = azurerm_linux_virtual_machine.storage.identity[0].principal_id
}

resource "azurerm_role_assignment" "storage_snapshot_contributor" {
  scope                = azurerm_resource_group.backup.id
  role_definition_name = "Disk Snapshot Contributor"
  principal_id         = azurerm_linux_virtual_machine.storage.identity[0].principal_id
}
