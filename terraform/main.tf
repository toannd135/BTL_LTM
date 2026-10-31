resource "azurerm_resource_group" "main" {
  name     = "rg-${var.prefix}"
  location = var.location
  tags     = var.tags
}

resource "azurerm_virtual_network" "main" {
  name                = "vnet-${var.prefix}"
  address_space       = ["10.20.0.0/16"]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags
}

resource "azurerm_subnet" "main" {
  name                 = "snet-${var.prefix}"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.20.1.0/24"]
}

resource "azurerm_public_ip" "main" {
  name                = "pip-${var.prefix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static" # IP co dinh vi ghi vao pasv.address
  sku                 = "Standard"
  tags                = var.tags
}

# Trang quan tri (8080) KHONG mo ra ngoai: chi bind 127.0.0.1, vao qua SSH tunnel
resource "azurerm_network_security_group" "main" {
  name                = "nsg-${var.prefix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  security_rule {
    name                       = "ssh"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = var.ssh_allowed_cidr
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "ftp-control"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "2121"
    source_address_prefix      = var.ftp_allowed_cidr
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "ftp-passive-data"
    priority                   = 120
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "50000-50100"
    source_address_prefix      = var.ftp_allowed_cidr
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_interface" "main" {
  name                = "nic-${var.prefix}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.main.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.main.id
  }
}

resource "azurerm_network_interface_security_group_association" "main" {
  network_interface_id      = azurerm_network_interface.main.id
  network_security_group_id = azurerm_network_security_group.main.id
}

resource "azurerm_linux_virtual_machine" "main" {
  name                            = "vm-${var.prefix}"
  location                        = azurerm_resource_group.main.location
  resource_group_name             = azurerm_resource_group.main.name
  size                            = var.vm_size
  admin_username                  = var.admin_username
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.main.id]
  tags                            = var.tags

  admin_ssh_key {
    username   = var.admin_username
    public_key = file(pathexpand(var.ssh_public_key_path))
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = var.disk_type
    disk_size_gb         = 64 # P6: nam trong goi mien phi (2 x 64 GB Premium SSD)
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }
}

# O du lieu rieng: xoa/tao lai VM khong mat ftproot va Postgres
resource "azurerm_managed_disk" "data" {
  name                 = "disk-${var.prefix}-data"
  location             = azurerm_resource_group.main.location
  resource_group_name  = azurerm_resource_group.main.name
  storage_account_type = var.disk_type
  create_option        = "Empty"
  disk_size_gb         = var.data_disk_size_gb
  tags                 = var.tags
}

resource "azurerm_virtual_machine_data_disk_attachment" "data" {
  managed_disk_id    = azurerm_managed_disk.data.id
  virtual_machine_id = azurerm_linux_virtual_machine.main.id
  lun                = 0 # Ansible tim o theo /dev/disk/azure/scsi1/lun0
  caching            = "ReadWrite"
}

# Tiet kiem: VM tat (deallocate) thi khong tinh tien CPU. Bat lai: az vm start -g rg-<prefix> -n vm-<prefix>
resource "azurerm_dev_test_global_vm_shutdown_schedule" "main" {
  count                 = var.auto_shutdown_time == null ? 0 : 1
  virtual_machine_id    = azurerm_linux_virtual_machine.main.id
  location              = azurerm_resource_group.main.location
  enabled               = true
  daily_recurrence_time = var.auto_shutdown_time
  timezone              = "SE Asia Standard Time"
  tags                  = var.tags

  notification_settings {
    enabled = false
  }
}

# Sinh inventory cho Ansible
resource "local_file" "ansible_inventory" {
  filename        = "${path.module}/../ansible/inventory/hosts.ini"
  file_permission = "0644"
  content         = <<-EOT
    # Sinh boi Terraform, khong sua tay
    [ftpserver]
    ${azurerm_public_ip.main.ip_address} ansible_user=${var.admin_username} ansible_ssh_private_key_file=${pathexpand(var.ssh_private_key_path)}

    [ftpserver:vars]
    ftp_public_ip=${azurerm_public_ip.main.ip_address}
  EOT
}
