output "public_ip" {
  value = azurerm_public_ip.main.ip_address
}

output "ssh" {
  value = "ssh ${var.admin_username}@${azurerm_public_ip.main.ip_address}"
}

output "ftp" {
  value = "ftp://${azurerm_public_ip.main.ip_address}:2121"
}

output "admin_web_tunnel" {
  description = "Mo tunnel roi vao http://localhost:8080"
  value       = "ssh -N -L 8080:127.0.0.1:8080 ${var.admin_username}@${azurerm_public_ip.main.ip_address}"
}

output "admin_web" {
  value = var.admin_web_allowed_cidr == null ? "dong (dung admin_web_tunnel)" : "http://${azurerm_public_ip.main.ip_address}:8080"
}
