# PostgreSQL dung dich vu Azure Database for PostgreSQL Flexible Server.
# Goi mien phi Azure for Students: B1ms (Burstable) 750 gio/thang + 32 GB luu tru + 32 GB backup.
# Truy cap cong khai co firewall: chi IP public cua VM ung dung duoc vao; bat buoc TLS (sslmode=require).

variable "db_sku" {
  description = "B_Standard_B1ms nam trong goi mien phi"
  type        = string
  default     = "B_Standard_B1ms"
}

variable "db_version" {
  type    = string
  default = "16"
}

variable "db_allowed_cidr_extra" {
  description = "Them 1 IP (vd may ca nhan) duoc vao Postgres de psql/debug. null = chi VM"
  type        = string
  default     = null
}

resource "random_string" "db_suffix" {
  length  = 6
  upper   = false
  special = false
}

# Azure yeu cau mat khau co >= 3 trong 4 loai ky tu; khong dung ky tu dac biet de khoi escape trong URL/.env
resource "random_password" "db" {
  length      = 32
  special     = false
  min_upper   = 2
  min_lower   = 2
  min_numeric = 2
}

resource "azurerm_postgresql_flexible_server" "main" {
  name                          = "psql-${var.prefix}-${random_string.db_suffix.result}" # ten DNS toan cau
  resource_group_name           = azurerm_resource_group.main.name
  location                      = azurerm_resource_group.main.location
  version                       = var.db_version
  sku_name                      = var.db_sku
  storage_mb                    = 32768
  storage_tier                  = "P4"
  auto_grow_enabled             = false
  backup_retention_days         = 7
  geo_redundant_backup_enabled  = false
  public_network_access_enabled = true
  administrator_login           = "ftp"
  administrator_password        = random_password.db.result
  tags                          = var.tags

  lifecycle {
    # Azure tu chon zone khi tao; khong de Terraform doi lai
    ignore_changes = [zone, high_availability[0].standby_availability_zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "ftpserver" {
  name      = "ftpserver"
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "vm" {
  name             = "vm-app"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = azurerm_public_ip.main.ip_address
  end_ip_address   = azurerm_public_ip.main.ip_address
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "extra" {
  count            = var.db_allowed_cidr_extra == null ? 0 : 1
  name             = "extra"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = cidrhost(var.db_allowed_cidr_extra, 0)
  end_ip_address   = cidrhost(var.db_allowed_cidr_extra, -1)
}

output "db_host" {
  value = azurerm_postgresql_flexible_server.main.fqdn
}

output "db_password" {
  value     = random_password.db.result
  sensitive = true
}
