variable "subscription_id" {
  description = "Azure subscription ID (null = lay tu ARM_SUBSCRIPTION_ID / az login)"
  type        = string
  default     = null
}

variable "prefix" {
  description = "Tien to ten tai nguyen"
  type        = string
  default     = "ftpsrv"
}

variable "location" {
  description = "Vung Azure"
  type        = string
  default     = "eastasia" # Azure for Students chi cho: eastasia, japaneast, koreacentral, malaysiawest, uaenorth
}

variable "vm_size" {
  description = "Kich thuoc VM. B2ats_v2 (2 vCPU AMD / 1 GB) mien phi 750 gio/thang 12 thang dau; tai khoan Student khong duoc cap B1s/B1ms. Ansible them swap 2 GB"
  type        = string
  default     = "Standard_B2ats_v2"
}

variable "admin_username" {
  description = "Tai khoan SSH tren VM"
  type        = string
  default     = "azureuser"
}

variable "ssh_public_key_path" {
  description = "Duong dan khoa cong khai SSH"
  type        = string
  default     = "~/.ssh/id_rsa.pub"
}

variable "ssh_private_key_path" {
  description = "Khoa bi mat tuong ung, chi ghi vao inventory Ansible"
  type        = string
  default     = "~/.ssh/id_rsa"
}

variable "ssh_allowed_cidr" {
  description = "Dai IP duoc SSH vao VM (dat IP may minh, vd 1.2.3.4/32)"
  type        = string
}

variable "ftp_allowed_cidr" {
  description = "Dai IP duoc dung FTP (cong 2121 va 50000-50100)"
  type        = string
  default     = "0.0.0.0/0"
}

variable "data_disk_size_gb" {
  description = "Dung luong o du lieu (ftproot, Postgres, log), gan vao /srv/ftp. 64 = P6, nam trong goi mien phi"
  type        = number
  default     = 64
}

variable "tags" {
  type    = map(string)
  default = { project = "BTL_LTM", app = "ftpserver" }
}

variable "disk_type" {
  description = "Loai o dia. Premium_LRS 64 GB (P6) mien phi 2 o; Standard_LRS/StandardSSD_LRS bi tinh tien"
  type        = string
  default     = "Premium_LRS"
}

variable "auto_shutdown_time" {
  description = "Gio tu tat VM moi ngay (HHMM, gio VN), giu credit khi het goi mien phi; null = khong tu tat"
  type        = string
  default     = "2300"
}
