# VM rieng chi chay PostgreSQL. Khong co Public IP: Ansible vao qua VM ung dung (ProxyJump),
# ra Internet (apt) bang default outbound access cua subnet.

variable "db_vm_size" {
  description = "B2pts_v2 (ARM, 2 vCPU / 1 GB): 750 gio mien phi rieng, khong chung voi B2ats_v2 cua VM ung dung"
  type        = string
  default     = "Standard_B2pts_v2"
}

variable "db_private_ip" {
  description = "IP noi bo co dinh cua VM Postgres (app ket noi toi day)"
  type        = string
  default     = "10.20.1.10"
}

resource "azurerm_network_security_group" "db" {
  name                = "nsg-${var.prefix}-db"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  security_rule {
    name                       = "postgres-from-app"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "5432"
    source_address_prefix      = azurerm_network_interface.main.private_ip_address
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "ssh-from-app"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = azurerm_network_interface.main.private_ip_address
    destination_address_prefix = "*"
  }

  # Chan moi truy cap khac trong VNet (mac dinh Azure cho phep VNet -> VNet)
  security_rule {
    name                       = "deny-vnet-other"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_interface" "db" {
  name                = "nic-${var.prefix}-db"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.main.id
    private_ip_address_allocation = "Static"
    private_ip_address            = var.db_private_ip
  }
}

resource "azurerm_network_interface_security_group_association" "db" {
  network_interface_id      = azurerm_network_interface.db.id
  network_security_group_id = azurerm_network_security_group.db.id
}

resource "azurerm_linux_virtual_machine" "db" {
  name                            = "vm-${var.prefix}-db"
  location                        = azurerm_resource_group.main.location
  resource_group_name             = azurerm_resource_group.main.name
  size                            = var.db_vm_size
  admin_username                  = var.admin_username
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.db.id]
  tags                            = var.tags

  admin_ssh_key {
    username   = var.admin_username
    public_key = file(pathexpand(var.ssh_public_key_path))
  }

  # 2 o P6 mien phi da dung cho VM ung dung -> HDD Standard 30 GB (re nhat)
  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
    disk_size_gb         = 30
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server-arm64"
    version   = "latest"
  }
}

resource "azurerm_dev_test_global_vm_shutdown_schedule" "db" {
  count                 = var.auto_shutdown_time == null ? 0 : 1
  virtual_machine_id    = azurerm_linux_virtual_machine.db.id
  location              = azurerm_resource_group.main.location
  enabled               = true
  daily_recurrence_time = var.auto_shutdown_time
  timezone              = "SE Asia Standard Time"
  tags                  = var.tags

  notification_settings {
    enabled = false
  }
}

output "db_private_ip" {
  value = var.db_private_ip
}
