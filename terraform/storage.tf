# VM-B: SeaweedFS (kho tep) + clamd (quet ma doc). VM-A (1 GB) chi chay FTP server + admin-web.
# Chi nhan ket noi tu VM-A: SSH (Ansible di qua VM-A bang ProxyCommand), S3 8333, clamd 3310.

variable "storage_vm_size" {
  description = "Size VM-B. clamd can ~1-1.5 GB RAM + SeaweedFS ~0.3 GB => toi thieu 2 GB, nen 4 GB (Standard_B2als_v2: 2 vCPU/4 GB). Standard_B2s (v1) KHONG co o eastasia cho tai khoan Student (da kiem tra az vm list-skus 2026-11)"
  type        = string
  default     = "Standard_B2als_v2"
}

variable "storage_disk_type" {
  description = "Loai o dia VM-B. Goi mien phi (2 x P6) da dung het cho VM-A nen VM-B dung StandardSSD"
  type        = string
  default     = "StandardSSD_LRS"
}

variable "storage_data_disk_size_gb" {
  description = "O du lieu VM-B (SeaweedFS + chu ky ClamAV), mount tai /srv/ftp"
  type        = number
  default     = 64
}

variable "storage_vm_public_ip" {
  description = "Gan public IP chi de VM-B ra Internet (apt, docker pull, freshclam). Khong co quy tac NSG cho phep vao tu Internet. Dat false neu da co NAT gateway"
  type        = bool
  default     = true
}

locals {
  subnet_cidr = azurerm_subnet.main.address_prefixes[0]
  app_ip      = cidrhost(local.subnet_cidr, 10) # VM-A, tinh de NSG cua VM-B tham chieu on dinh qua lan deallocate
  storage_ip  = cidrhost(local.subnet_cidr, 11) # VM-B
}

resource "azurerm_public_ip" "storage" {
  count               = var.storage_vm_public_ip ? 1 : 0
  name                = "pip-${var.prefix}-storage"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = var.tags
}

resource "azurerm_network_security_group" "storage" {
  name                = "nsg-${var.prefix}-storage"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  security_rule {
    name                       = "from-app"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_ranges    = ["22", "8333", "3310"]
    source_address_prefix      = local.app_ip
    destination_address_prefix = "*"
  }

  # moi thu khac trong VNet bi chan (rule mac dinh AllowVnetInBound se cho vao neu khong co rule nay)
  security_rule {
    name                       = "deny-other-vnet"
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

resource "azurerm_network_interface" "storage" {
  name                = "nic-${var.prefix}-storage"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.main.id
    private_ip_address_allocation = "Static"
    private_ip_address            = local.storage_ip
    public_ip_address_id          = var.storage_vm_public_ip ? azurerm_public_ip.storage[0].id : null
  }
}

resource "azurerm_network_interface_security_group_association" "storage" {
  network_interface_id      = azurerm_network_interface.storage.id
  network_security_group_id = azurerm_network_security_group.storage.id
}

resource "azurerm_linux_virtual_machine" "storage" {
  name                            = "vm-${var.prefix}-storage"
  location                        = azurerm_resource_group.main.location
  resource_group_name             = azurerm_resource_group.main.name
  size                            = var.storage_vm_size
  admin_username                  = var.admin_username
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.storage.id]
  tags                            = var.tags

  admin_ssh_key {
    username   = var.admin_username
    public_key = file(pathexpand(var.ssh_public_key_path))
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = var.storage_disk_type
    disk_size_gb         = 30
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }
}

resource "azurerm_managed_disk" "storage" {
  name                 = "disk-${var.prefix}-storage"
  location             = azurerm_resource_group.main.location
  resource_group_name  = azurerm_resource_group.main.name
  storage_account_type = var.storage_disk_type
  create_option        = "Empty"
  disk_size_gb         = var.storage_data_disk_size_gb
  tags                 = var.tags
}

resource "azurerm_virtual_machine_data_disk_attachment" "storage" {
  managed_disk_id    = azurerm_managed_disk.storage.id
  virtual_machine_id = azurerm_linux_virtual_machine.storage.id
  lun                = 0 # Ansible tim o theo /dev/disk/azure/scsi1/lun0
  caching            = "ReadWrite"
}

resource "azurerm_dev_test_global_vm_shutdown_schedule" "storage" {
  count                 = var.auto_shutdown_time == null ? 0 : 1
  virtual_machine_id    = azurerm_linux_virtual_machine.storage.id
  location              = azurerm_resource_group.main.location
  enabled               = true
  daily_recurrence_time = var.auto_shutdown_time
  timezone              = "SE Asia Standard Time"
  tags                  = var.tags

  notification_settings {
    enabled = false
  }
}
